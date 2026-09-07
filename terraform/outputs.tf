output "instance_id" {
  value = aws_instance.bot.id
}

output "elastic_ip" {
  description = "Allowlist this IP on the MEXC API key (cf-arb-bot-plan.md §6.2) -- it does not change across reboots."
  value       = aws_eip.bot.public_ip
}

output "journal_bucket" {
  value = aws_s3_bucket.journal.bucket
}

output "ssm_session_command" {
  description = "How to reach the instance -- there is no SSH."
  value       = "aws ssm start-session --target ${aws_instance.bot.id} --region ${var.aws_region}"
}
