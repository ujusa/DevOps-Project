resource "aws_secretsmanager_secret" "app_secret" {
  name = "${var.project_name}/${var.environment}/app-secret"

  tags = {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "Terraform"
  }
}