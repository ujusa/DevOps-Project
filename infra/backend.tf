terraform {
  backend "s3" {
    bucket         = "taski-devops-terraform-state"
    key            = "nodejs-app/terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "taski-devops-terraform-locks"
    encrypt        = true
  }
}