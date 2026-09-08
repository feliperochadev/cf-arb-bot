#!/bin/bash
# cf-arb-bot instance bootstrap -- cf-arb-bot-plan.md §7.
set -euo pipefail

# --- Corretto 25 aarch64 ---
rpm --import https://yum.corretto.aws/corretto.key
curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
dnf install -y java-25-amazon-corretto-devel

# --- AWS CLI (needed by the ExecStartPre secret-fetch script below; not guaranteed present on
# every AL2023 image variant) ---
dnf install -y awscli

# --- Clock discipline is NOT optional (§7): signed requests carry a timestamp validated against
# recvWindow, and every latency number this project publishes is a claim that needs evidence. ---
dnf install -y chrony
cat > /etc/chrony.conf <<'CHRONY'
server 169.254.169.123 prefer iburst minpoll 4 maxpoll 4
driftfile /var/lib/chrony/drift
makestep 1.0 3
rtcsync
CHRONY
systemctl enable --now chronyd
# Prefer the Nitro PTP hardware clock if present (recorder-service-plan.md §6's precedent).
if [ -e /dev/ptp0 ]; then
  echo "refclock PHC /dev/ptp0 poll 2 prefer" >> /etc/chrony.conf
  systemctl restart chronyd
fi

# --- Kernel/network tuning ---
echo never > /sys/kernel/mm/transparent_hugepage/enabled || true
cat > /etc/sysctl.d/99-cf-arb-bot.conf <<'SYSCTL'
net.ipv4.tcp_slow_start_after_idle = 0
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
SYSCTL
sysctl --system

# --- Service user: non-root, no shell (security rule S13) ---
useradd --system --no-create-home --shell /sbin/nologin cfarbbot || true

# --- Secret retrieval: cf-arb-bot-review-plan.md Tier 2 step 2.3. Previously this ran ONLY here, at
# cloud-init/first-boot time -- a rotated MEXC key would never be picked up short of re-running
# cloud-init by hand. Install a root-owned ExecStartPre script instead, so `systemctl restart
# cf-arb-bot` alone re-fetches current secrets from SSM on every start. Still fetched once here too,
# so the very first `systemctl start` (before any restart) also has secrets in place. ---
mkdir -p /etc/cf-arb-bot
cat > /usr/local/sbin/cf-arb-bot-fetch-secrets.sh <<'FETCHSCRIPT'
#!/bin/bash
set -euo pipefail
umask 0377 # never let the file be briefly world/group-readable between create and chmod
OUT=/etc/cf-arb-bot/secrets.env
MEXC_API_KEY=$(aws ssm get-parameter --name "${mexc_api_key_param_name}" --with-decryption \
  --region "${aws_region}" --query 'Parameter.Value' --output text)
MEXC_API_SECRET=$(aws ssm get-parameter --name "${mexc_api_secret_param_name}" --with-decryption \
  --region "${aws_region}" --query 'Parameter.Value' --output text)
# REVIEW.md MED-08: the previous unquoted <<ENVFILE heredoc let bash parameter-expand the secret
# values themselves -- a MEXC secret containing "$" or "\" would be silently corrupted or truncated
# before it ever reached the file. printf with %s performs no expansion on its arguments.
printf 'MEXC_API_KEY=%s\nMEXC_API_SECRET=%s\n' "$MEXC_API_KEY" "$MEXC_API_SECRET" > "$OUT"
chmod 0400 "$OUT"
chown cfarbbot:cfarbbot "$OUT"
FETCHSCRIPT
chmod 0500 /usr/local/sbin/cf-arb-bot-fetch-secrets.sh
chown root:root /usr/local/sbin/cf-arb-bot-fetch-secrets.sh

# --- Application deploy: out of scope for this Terraform (CI/CD is not part of this plan's
# Phase 1-4), but the CONTRACT this unit assumes is explicit -- REVIEW.md MAJ-07: the deploy
# pipeline must sync the ENTIRE `target/quarkus-app/` directory tree into /opt/cf-arb-bot/, not only
# quarkus-run.jar. Quarkus 3.x's fast-jar layout is quarkus-run.jar (a thin bootstrap runner) PLUS
# sibling lib/, app/, and quarkus/ directories it loads at startup; copying the jar alone crashes
# with ClassNotFoundException: io.quarkus.bootstrap.runner.QuarkusEntryPoint. Concretely:
#   rsync -a target/quarkus-app/ ec2-host:/opt/cf-arb-bot/
# (or an equivalent that preserves quarkus-run.jar, lib/, app/, and quarkus/ as SIBLINGS under
# /opt/cf-arb-bot/ -- ExecStart below expects exactly that layout). See ops/cf-arb-bot.service and
# README.md for the same note.
#
# cf-arb-bot-review-plan.md Tier 2 step 2.2: mexc_filters.json is bundled inside the jar's classpath
# (src/main/resources/config/), so no separate filter-file deploy step is required; /opt/cf-arb-bot/config
# remains available as the expected location for cf-bot.filters-path if an operator ever needs to
# override the bundled snapshot without rebuilding. ---
mkdir -p /opt/cf-arb-bot /opt/cf-arb-bot/config /var/lib/cf-arb-bot/journal
chown -R cfarbbot:cfarbbot /opt/cf-arb-bot /var/lib/cf-arb-bot

# --- Journal logrotate: REVIEW.md MED-09. EventJournal rotates NDJSON hourly under
# /var/lib/cf-arb-bot/journal/ with no S3 sync yet (needs the AWS SDK + a real bucket to test
# against -- CLAUDE.md's documented gap); on a 20GB root volume, unbounded local retention alone
# will eventually exhaust disk and crash the service. This is a stopgap, not a substitute for the
# real S3 sync. ---
cat > /etc/logrotate.d/cf-arb-bot <<'LOGROTATE'
/var/lib/cf-arb-bot/journal/*.ndjson {
  daily
  rotate 7
  compress
  missingok
  notifempty
  su cfarbbot cfarbbot
}
LOGROTATE

# --- systemd unit (installed here; content mirrored at ops/cf-arb-bot.service for review outside
# the templated user_data.sh) ---
cat > /etc/systemd/system/cf-arb-bot.service <<'UNIT'
[Unit]
Description=cf-arb-bot -- MEXC triangular arbitrage PoC
After=network-online.target chronyd.service
Wants=network-online.target

[Service]
Type=simple
User=cfarbbot
Group=cfarbbot
WorkingDirectory=/opt/cf-arb-bot
# cf-arb-bot-review-plan.md Tier 2 step 2.3: fetch secrets fresh on EVERY start, not only once at
# cloud-init time -- the "+" prefix runs this one line as root, unsandboxed, before the sandboxed
# main process (below) drops to the unprivileged cfarbbot user.
ExecStartPre=+/usr/local/sbin/cf-arb-bot-fetch-secrets.sh
EnvironmentFile=/etc/cf-arb-bot/secrets.env
Environment=CF_BOT_JOURNAL_DIR=/var/lib/cf-arb-bot/journal
ExecStart=/usr/bin/java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=256m \
  -jar /opt/cf-arb-bot/quarkus-run.jar
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/cf-arb-bot
ProtectHome=true
# cf-arb-bot-review-plan.md Tier 2 step 2.3 / Grok Medium 10: ProtectSystem=strict makes /tmp
# read-only from this unit's view; the JVM (hsperfdata) and Quarkus both want a writable temp dir.
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable cf-arb-bot.service

# --- First-boot secret population: REVIEW.md MAJ-05. Moved to the END of this script (after the
# unit is installed and enabled) and made NON-FATAL. Previously this ran early, under `set -euo
# pipefail`, BEFORE /opt/cf-arb-bot or the systemd unit existed -- if an operator ran `terraform
# apply` before the SSM parameters were populated (the documented, expected out-of-band order in
# main.tf's own comment), aws ssm get-parameter's failure aborted the ENTIRE script right there:
# no /opt/cf-arb-bot directory, no systemd unit, cloud-init marked failed. Fail-closed behavior is
# fully preserved by ExecStartPre in the unit above -- a non-zero exit THERE still fails the unit
# and blocks the service from starting with no credentials, which is the correct place for that
# gate. This first-boot call is just a convenience so the very first `systemctl start` (once the
# jar is deployed) already has secrets in place if they happen to exist yet.
/usr/local/sbin/cf-arb-bot-fetch-secrets.sh \
  || echo "WARNING: SSM secrets not yet populated (see main.tf's 'populated out of band' note) -- \
cloud-init will still finish successfully; ExecStartPre will fetch them on the first 'systemctl \
start cf-arb-bot' once they exist" >&2

# NOT started here -- the jar isn't deployed yet at first boot (see the deploy-contract note above).
# Start manually once the full quarkus-app tree exists at /opt/cf-arb-bot: `systemctl start cf-arb-bot`.
