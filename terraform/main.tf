# cf-arb-bot infrastructure -- cf-arb-bot-plan.md §7.
#
# NOT APPLIED: this environment has no AWS credentials configured (verified 2026-09-07 -- `aws sts
# get-caller-identity` fails with no CLI/no credentials). This file is written to the plan's exact
# spec and is ready for `terraform plan`/`apply` once an AWS account and credentials are available,
# but it has never been run. Treat it as reviewed-by-inspection, not verified-by-execution.
#
# Budget target $30-40/month (§7): a NAT gateway (~$45/mo alone) and an ALB (~$20/mo) would each
# blow that budget on their own, so neither appears here. The instance sits in a public subnet with
# an Elastic IP -- required because the MEXC API key is IP-allowlisted (§6.2), so a changing IP on
# reboot would silently break trading.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  # S3 backend + DynamoDB lock (§7: "don't lose state on a laptop"). Bucket/table must be created
  # manually before first `terraform init` -- chicken-and-egg problem intentionally left to the
  # operator, since bootstrapping remote state from Terraform itself is its own well-known footgun.
  #
  # cf-arb-bot-review-plan.md Tier 2 step 2.1: the one-time bootstrap (adjust names/region):
  #   aws s3api create-bucket --bucket cf-arb-bot-tfstate --region ap-northeast-1 \
  #     --create-bucket-configuration LocationConstraint=ap-northeast-1
  #   aws s3api put-bucket-versioning --bucket cf-arb-bot-tfstate \
  #     --versioning-configuration Status=Enabled
  #   aws s3api put-bucket-encryption --bucket cf-arb-bot-tfstate --server-side-encryption-configuration \
  #     '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
  #   aws dynamodb create-table --table-name cf-arb-bot-tflock --billing-mode PAY_PER_REQUEST \
  #     --attribute-definitions AttributeName=LockID,AttributeType=S \
  #     --key-schema AttributeName=LockID,KeyType=HASH --region ap-northeast-1
  # Then: terraform init -backend-config="bucket=cf-arb-bot-tfstate" \
  #   -backend-config="key=cf-arb-bot/terraform.tfstate" -backend-config="region=ap-northeast-1" \
  #   -backend-config="dynamodb_table=cf-arb-bot-tflock"
  #
  # bucket, key, region, dynamodb_table: fill in via -backend-config or a backend.hcl, not
  # hardcoded here -- keeps this file usable across environments (e.g. the Gate-0 probe run vs.
  # the real long-lived deployment) without editing committed code.
  backend "s3" {
  }
}

provider "aws" {
  region = var.aws_region
}

# cf-arb-bot-review-plan.md Tier 2 step 2.1: constrain the AMI selector to the intended published
# base image. The previous "al2023-ami-*-arm64" glob also matches AL2023's "minimal" and ECS-optimized
# variants (e.g. "al2023-ami-minimal-*-arm64", "al2023-ami-ecs-hvm-*-arm64"), which "most_recent" could
# select depending on publish timing -- neither carries the standard package set user_data.sh assumes
# (dnf, chrony, etc. are present on minimal too, but this pins to the specific published line rather
# than accepting whatever glob-matches first).
data "aws_ami" "al2023_arm64" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-2*-kernel-*-arm64"]
  }
  filter {
    name   = "architecture"
    values = ["arm64"]
  }
  filter {
    name   = "image-type"
    values = ["machine"]
  }
}

# --- Networking: 1 VPC, 1 public subnet, IGW. No NAT, no ALB (budget). ---

resource "aws_vpc" "main" {
  cidr_block           = "10.42.0.0/24"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "cf-arb-bot" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "cf-arb-bot" }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.42.0.0/26"
  map_public_ip_on_launch = true
  availability_zone       = data.aws_availability_zones.available.names[0]
  tags                    = { Name = "cf-arb-bot-public" }
}

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = { Name = "cf-arb-bot-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# --- Security group: egress-only for market data/exec/DNS. No ingress at all -- the read-only API
# is loopback-only (application.properties' %prod.quarkus.http.host=127.0.0.1) and reached exclusively
# via SSM port forwarding, never a routed security-group rule (cf-arb-bot-review-plan.md Tier 2 step
# 2.1: all three independent reviewers flagged the previous posture as contradictory -- this SG opened
# 443 inbound while Quarkus never bound anything but loopback, so nothing was ever actually reachable
# through it; removing the rule instead of adding a TLS/auth layer keeps the $30-40/mo budget and the
# "no ALB" constraint, and SSM Session Manager already requires an authenticated AWS principal).
#
# Operator access: `aws ssm start-session --target <instance-id> --region ${var.aws_region} \
#   --document-name AWS-StartPortForwardingSession --parameters '{"portNumber":["8080"],"localPortNumber":["8080"]}'`
# then browse http://localhost:8080/api/v1/state, /q/health/ready, /q/metrics locally.
resource "aws_security_group" "bot" {
  name        = "cf-arb-bot"
  description = "cf-arb-bot: egress to MEXC (443), DNS (53), NTP (123/udp) only; no ingress -- API reached via SSM port forwarding"
  vpc_id      = aws_vpc.main.id

  egress {
    description = "HTTPS to MEXC REST/WS and SSM"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    description = "NTP (chrony, if not using the Amazon Time Sync Service link-local 169.254.169.123 path exclusively)"
    from_port   = 123
    to_port     = 123
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  # cf-arb-bot-review-plan.md Tier 1 step 2.1 / all three reviewers' finding: egress was 443+123
  # only, so the instance could never resolve api.mexc.com, SSM's endpoints, or the Corretto/dnf
  # package repos -- user_data.sh would fail at its very first `curl`/`dnf install`.
  egress {
    description = "DNS to the VPC's Amazon-provided resolver (base+2, e.g. 10.42.0.2)"
    from_port   = 53
    to_port     = 53
    protocol    = "udp"
    cidr_blocks = ["${cidrhost(aws_vpc.main.cidr_block, 2)}/32"]
  }
  egress {
    description = "DNS to the VPC's Amazon-provided resolver (TCP fallback for large responses)"
    from_port   = 53
    to_port     = 53
    protocol    = "tcp"
    cidr_blocks = ["${cidrhost(aws_vpc.main.cidr_block, 2)}/32"]
  }

  tags = { Name = "cf-arb-bot" }
}

# --- IAM: least privilege -- SSM Session Manager, the two secret params, the journal bucket. ---

resource "aws_iam_role" "bot" {
  name = "cf-arb-bot-instance-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.bot.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "secrets_and_journal" {
  name = "cf-arb-bot-secrets-and-journal"
  role = aws_iam_role.bot.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadMexcApiCredentials"
        Effect = "Allow"
        Action = ["ssm:GetParameter"]
        Resource = [
          "arn:aws:ssm:${var.aws_region}:*:parameter${var.mexc_api_key_param_name}",
          "arn:aws:ssm:${var.aws_region}:*:parameter${var.mexc_api_secret_param_name}",
        ]
      },
      {
        # cf-arb-bot-review-plan.md Tier 2 step 2.1: scope kms:Decrypt to requests originating FROM
        # SSM specifically, rather than every KMS key in the account -- Resource stays "*" because
        # the default aws/ssm key's ARN is only known after the SecureString parameters exist (a
        # chicken-and-egg problem this Terraform doesn't create the parameters to solve, matching
        # main.tf's existing "populated out of band" convention for the secrets themselves), but the
        # condition narrows the grant to exactly the SSM decrypt-on-read path this instance needs,
        # not an open-ended kms:Decrypt against any key the role happens to be able to reach.
        Sid    = "DecryptSecureStringParams"
        Effect = "Allow"
        Action = ["kms:Decrypt"]
        Resource = "*"
        Condition = {
          StringEquals = {
            "kms:ViaService" = "ssm.${var.aws_region}.amazonaws.com"
          }
        }
      },
      {
        Sid      = "WriteJournal"
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = "${aws_s3_bucket.journal.arn}/*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "bot" {
  name = "cf-arb-bot"
  role = aws_iam_role.bot.name
}

# --- Secrets: SSM Parameter Store SecureString. Values populated OUT OF BAND, never here. ---
# `aws ssm put-parameter --name /cf-arb-bot/mexc-api-key --type SecureString --value '<key>'`
# Terraform manages the parameter's existence/type for review purposes but the actual secret value
# must never pass through `terraform plan`/state -- see cf-arb-bot-plan.md §6.2.
#
# REVIEW.md MAJ-05: `terraform apply` no longer depends on these parameters existing first --
# user_data.sh's first-boot secret fetch is at the END of the script and non-fatal, so an `apply`
# run before these `put-parameter` commands still finishes cloud-init successfully (with a WARNING
# in its log). The unit's `ExecStartPre` is the real, fail-closed gate: the service will not START
# without valid secrets, but the INSTANCE will boot regardless of ordering. Populating these before
# `terraform apply` remains the recommended order (no WARNING, no extra boot-then-retry step), just
# no longer a hard prerequisite for the instance to come up at all.

# --- Journal bucket ---

resource "aws_s3_bucket" "journal" {
  bucket = var.journal_bucket_name
}

resource "aws_s3_bucket_lifecycle_configuration" "journal" {
  bucket = aws_s3_bucket.journal.id
  rule {
    id     = "expire-90d"
    status = "Enabled"
    filter {} # applies to the whole bucket -- explicit empty filter, required since provider ~5.0
    expiration { days = 90 }
  }
}

# cf-arb-bot-review-plan.md Tier 2 step 2.1: the journal bucket had no explicit encryption or
# versioning configuration -- both are cheap and standard hardening for a bucket that (eventually)
# holds every fired/rejected opportunity and every fill this bot ever produces.
resource "aws_s3_bucket_server_side_encryption_configuration" "journal" {
  bucket = aws_s3_bucket.journal.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "journal" {
  bucket = aws_s3_bucket.journal.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "journal" {
  bucket                  = aws_s3_bucket.journal.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# --- Elastic IP -- required, not optional: the MEXC key is IP-allowlisted (§6.2). ---

resource "aws_eip" "bot" {
  domain = "vpc"
  tags   = { Name = "cf-arb-bot" }
  # cf-arb-bot-review-plan.md Tier 2 step 2.1: an EIP allocated/associated before the IGW exists can
  # fail association in some account/region states. Explicit for clarity even though the
  # aws_eip_association -> aws_instance -> aws_subnet -> aws_route_table_association -> igw chain
  # already provides an implicit ordering in practice.
  depends_on = [aws_internet_gateway.main]
}

resource "aws_eip_association" "bot" {
  instance_id   = aws_instance.bot.id
  allocation_id = aws_eip.bot.id
}

# --- The instance ---

resource "aws_instance" "bot" {
  ami                    = data.aws_ami.al2023_arm64.id
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.bot.id]
  iam_instance_profile   = aws_iam_instance_profile.bot.name
  monitoring             = true

  root_block_device {
    volume_size           = var.root_volume_gb
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/user_data.sh", {
    mexc_api_key_param_name    = var.mexc_api_key_param_name
    mexc_api_secret_param_name = var.mexc_api_secret_param_name
    journal_bucket_name        = var.journal_bucket_name
    aws_region                 = var.aws_region
  })

  tags = { Name = "cf-arb-bot" }
}

# --- Alerting: CloudWatch instance status + a custom heartbeat metric -> SNS email. ---
# The custom heartbeat metric itself (cf-arb-bot pushing to CloudWatch) is a Phase 1 code addition,
# not implemented in this pass -- see cf-arb-bot-plan.md §7's "not in scope" note on what's deferred.

resource "aws_sns_topic" "alerts" {
  name = "cf-arb-bot-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_cloudwatch_metric_alarm" "instance_status" {
  alarm_name          = "cf-arb-bot-instance-status-check-failed"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "StatusCheckFailed"
  namespace           = "AWS/EC2"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  dimensions          = { InstanceId = aws_instance.bot.id }
  alarm_actions       = [aws_sns_topic.alerts.arn]
}
