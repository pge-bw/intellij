provider "aws" {
  region = "us-east-1"
}

variable "vpc_id" {}

resource "aws_key_pair" "ij_build_access_key" {
  key_name   = "ij_build_access_key"
  public_key = "ssh-rsa <YOUR PUBLIC KEY HERE>"
}

data "aws_subnet_ids" "nat" {
  vpc_id = var.vpc_id

  tags = {
    Tier = "natCoreInfra"
  }
}

resource "aws_security_group" "ij_build_sg" {
  name   = "ij_build_sg"
  vpc_id = var.vpc_id"

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "build_plugin_ec2" {
  ami                  = "ami-09e67e426f25ce0d7"
  instance_type        = "t2.medium"
  iam_instance_profile = "Buildbot"
  key_name             = "ij_build_access_key"
  subnet_id            = element(tolist(data.aws_subnet_ids.nat.ids), 0)
  security_groups      = [aws_security_group.ij_build_sg.id]
  root_block_device {
    volume_type = "gp2"
    volume_size = 100
  }
  user_data = <<EOF
#! /bin/bash
sudo apt-get update && sudo apt-get install --yes awscli docker.io
sudo usermod -aG docker ubuntu
EOF
}

output "connection_string" {
  value = "ssh ubuntu@${aws_instance.build_plugin_ec2.private_ip}"
}
