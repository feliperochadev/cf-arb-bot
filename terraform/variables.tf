variable "aws_region" {
  description = "cf-arb-bot-plan.md §3 Gate 0 step 1: region choice pending an actual RTT probe from ap-northeast-1 and ap-southeast-1 (both MEXC endpoints are CDN-fronted -- edge proximity is not origin proximity). Defaults to ap-northeast-1 per the project's existing Tokyo convention (recorder-service-plan.md §6); override once the probe module below has real numbers."
  type        = string
  default     = "ap-northeast-1"
}

variable "instance_type" {
  description = "cf-arb-bot-plan.md §7: c7g.medium (dedicated vCPU, no burst-credit cliff) at ~$31/mo. t4g.small (~$13/mo) is the fallback if the $30-40/mo budget proves tight, at the cost of burst-credit throttling risk mid-cycle -- do not use Spot (a reclaimed instance mid-cycle leaves a broken triangle holding a non-anchor asset)."
  type        = string
  default     = "c7g.medium"
}

variable "root_volume_gb" {
  description = "gp3 root volume size. Journal rotates to S3; nothing large stays local."
  type        = number
  default     = 20
}

variable "mexc_api_key_param_name" {
  description = "SSM Parameter Store SecureString name holding the MEXC API key (cf-arb-bot-plan.md §6.2). The key itself is never in Terraform state -- only the parameter NAME is a Terraform input; the value is populated out-of-band via `aws ssm put-parameter --type SecureString`."
  type        = string
  default     = "/cf-arb-bot/mexc-api-key"
}

variable "mexc_api_secret_param_name" {
  description = "SSM Parameter Store SecureString name holding the MEXC API secret. Same out-of-band population requirement as mexc_api_key_param_name."
  type        = string
  default     = "/cf-arb-bot/mexc-api-secret"
}

variable "journal_bucket_name" {
  description = "S3 bucket for the append-only NDJSON event journal (cf-arb-bot-plan.md §8). Must be globally unique."
  type        = string
}

variable "alert_email" {
  description = "SNS email subscription for CloudWatch alarms (cheap; run-recorder.sh's email-alert chain is the existing precedent in this project)."
  type        = string
}
