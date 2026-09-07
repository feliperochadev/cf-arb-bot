# Gate 0 step 1 (cf-arb-bot-plan.md §3): a throwaway instance to measure REAL round-trip latency to
# MEXC from a candidate region, since both MEXC endpoints are CDN-fronted (Akamai/CloudFront) and
# edge proximity is not origin proximity -- a TCP handshake completing quickly proves nothing about
# the actual REST/WS round trip through to MEXC's matching engine.
#
# NEVER APPLIED in this build (no AWS credentials in this environment). Usage once credentials
# exist, run once per candidate region and destroy after collecting results:
#
#   terraform apply -var="region=ap-northeast-1" -var="admin_cidr=<your IP>/32"
#   aws ssm start-session --target <instance-id> --region ap-northeast-1
#   cat /var/log/cf-arb-probe.log     # p50/p99 REST TTFB, WS connect-to-first-frame, clock skew
#   terraform apply -var="region=ap-southeast-1" ...   # repeat for the second candidate
#   terraform destroy                                   # after both regions are measured

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

variable "region" {
  type = string
}

variable "admin_cidr" {
  type = string
}

provider "aws" {
  region = var.region
}

data "aws_ami" "al2023_arm64" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-arm64"]
  }
  filter {
    name   = "architecture"
    values = ["arm64"]
  }
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# cf-arb-bot-review-plan.md Tier 2 step 2.1: this instance only ever curls MEXC's REST API, connects
# one WebSocket, and needs DNS/SSM to do either -- an unrestricted -1/0.0.0.0/0 egress rule was never
# required even for a short-lived throwaway probe.
resource "aws_security_group" "probe" {
  name   = "cf-arb-probe"
  vpc_id = data.aws_vpc.default.id
  egress {
    description = "HTTPS to MEXC REST/WS and SSM"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    description = "DNS to the default VPC's Amazon-provided resolver"
    from_port   = 53
    to_port     = 53
    protocol    = "udp"
    cidr_blocks = ["${cidrhost(data.aws_vpc.default.cidr_block, 2)}/32"]
  }
  egress {
    description = "DNS to the default VPC's Amazon-provided resolver (TCP fallback)"
    from_port   = 53
    to_port     = 53
    protocol    = "tcp"
    cidr_blocks = ["${cidrhost(data.aws_vpc.default.cidr_block, 2)}/32"]
  }
  tags = { Name = "cf-arb-probe" }
}

resource "aws_iam_role" "probe" {
  name = "cf-arb-probe-${var.region}"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Action = "sts:AssumeRole", Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.probe.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "probe" {
  name = "cf-arb-probe-${var.region}"
  role = aws_iam_role.probe.name
}

resource "aws_instance" "probe" {
  ami                    = data.aws_ami.al2023_arm64.id
  instance_type          = "t4g.nano" # cheapest possible -- this instance lives for a few hours, not months
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.probe.id]
  iam_instance_profile   = aws_iam_instance_profile.probe.name

  user_data = <<-EOT
    #!/bin/bash
    set -euo pipefail
    LOG=/var/log/cf-arb-probe.log
    echo "=== region: ${var.region} ===" > "$LOG"
    echo "=== clock skew vs MEXC serverTime ===" >> "$LOG"
    for i in 1 2 3 4 5; do
      local_ms=$(date +%s%3N)
      server_ms=$(curl -s https://api.mexc.com/api/v3/time | grep -o '[0-9]*')
      echo "local=$local_ms server=$server_ms skew_ms=$((local_ms - server_ms))" >> "$LOG"
    done
    echo "=== REST TTFB (30 samples, /api/v3/ping -- unauthenticated liveness only) ===" >> "$LOG"
    for i in $(seq 1 30); do
      curl -s -o /dev/null -w '%%{time_starttransfer}\n' https://api.mexc.com/api/v3/ping >> "$LOG"
    done
    # cf-arb-bot-review-plan.md Tier 2 step 2.1: /api/v3/ping never reaches the order-processing
    # pipeline at all -- it is the wrong proxy for "how fast can MEXC reject something." A signed,
    # deliberately-rejected order needs real API credentials this credential-free throwaway probe
    # does not have (and should not be given -- CLAUDE.md #8/#9's "never hit a live exchange in an
    # automated path with real keys" applies here too). The nearest measurement possible WITHOUT
    # credentials is POST /api/v3/order with no params at all: MEXC's auth/parameter-validation
    # layer rejects it (400, missing signature/mandatory fields) before ever reaching matching, which
    # is close to (though not identical to) the specified signed-rejected-order round trip. Treat
    # this as a lower bound, not a substitute -- the real measurement still requires a manually-run,
    # credentialed probe per CLAUDE.md's live-probe discipline, outside this Terraform.
    echo "=== REST TTFB (30 samples, unauthenticated POST /api/v3/order -- rejected before matching, lower bound only) ===" >> "$LOG"
    for i in $(seq 1 30); do
      curl -s -o /dev/null -w '%%{time_starttransfer}\n' -X POST https://api.mexc.com/api/v3/order >> "$LOG"
    done
    echo "=== WS connect-to-first-frame (aggre.depth@10ms, BTCUSDT) ===" >> "$LOG"
    python3 -c "
import asyncio, json, time
async def main():
    import websockets
    t0 = time.monotonic()
    async with websockets.connect('wss://wbs-api.mexc.com/ws') as ws:
        connected = time.monotonic()
        await ws.send(json.dumps({'method':'SUBSCRIPTION','params':['spot@public.aggre.depth.v3.api.pb@10ms@BTCUSDT']}))
        while True:
            msg = await ws.recv()
            if isinstance(msg, bytes):
                print(f'connect_ms={(connected-t0)*1000:.1f} first_frame_ms={(time.monotonic()-t0)*1000:.1f}')
                break
asyncio.run(main())
" >> "$LOG" 2>&1 || echo "python3/websockets not preinstalled -- install manually via SSM to run this step" >> "$LOG"
    echo "probe complete -- fetch $LOG via SSM" >> "$LOG"
  EOT

  tags = { Name = "cf-arb-probe-${var.region}" }
}

output "instance_id" {
  value = aws_instance.probe.id
}

output "fetch_results_command" {
  value = "aws ssm start-session --target ${aws_instance.probe.id} --region ${var.region} --document-name AWS-StartInteractiveCommand --parameters command='cat /var/log/cf-arb-probe.log'"
}
