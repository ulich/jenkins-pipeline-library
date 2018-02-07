def call(options) {
    def appName = options.name

    properties([
        buildDiscarder(logRotator(numToKeepStr: '5')),
        disableConcurrentBuilds()
    ])

    ansiColor('xterm') {
        node {
            deleteDir()

            stage('Checkout') {
                checkout scm
            }

            stage('Build') {
                sh "./gradlew build"
            }

            if (env.BRANCH_NAME == "master") {
                deploy(appName, "staging")

                stage("Approve") {
                    input message: 'Deploy to production?'
                }

                deploy(appName, "prod")
            }
        }
    }

}

def deploy(appName, environment) {
    def planFile = "planfile-${environment}-$env.BUILD_TAG"
    def terraformWorkspace = environment == "staging" ? "default" : environment

    dir('infrastructure') {
        stage("Terraform plan ${environment}") {
            generateTerraformConfig(appName)
            sh """
                terraform init
                terraform workspace select ${terraformWorkspace} || terraform workspace new ${terraformWorkspace}
            """
            hasChanges = terraformPlan(planFile, appName)
        }

        stage("Terraform apply ${environment}") {
            if (hasChanges) {
                input message: 'Do you wish to apply the plan?'
                sh "terraform apply ${planFile}"
            }
        }
    }

    stage("Deploy ${environment}") {
        eb_deploy(appName, environment)
    }
}

def generateTerraformConfig(app) {
    writeFile file: 'config.tf', text: """
variable "app_name" {
}

provider "aws" {
    region = "us-east-1"
}

terraform {
  backend "s3" {
    bucket = "dip-infrastructure"
    key = "terraform-states/${app}.tfstate"
    encrypt = false
    region = "eu-central-1"
  }
}
"""
}

def terraformPlan(planFile, app) {
    exit_code = sh (script: "terraform plan -var 'app_name=${app}' -detailed-exitcode -out ${planFile}", returnStatus: true)
    if (exit_code == 1) {
        error('Plan failed. Please review')
    }
    return exit_code == 2
}

def eb_deploy(app, environment) {
    sh """
        mkdir deploy || true
        cp -r .ebextensions deploy/ || true
        cp build/libs/*.jar deploy/
    """
    dir('deploy') {
        generateEbConfigFile(app)

        if (environment == "staging") {
            sh "eb deploy ${app}-${environment}"
        } else {
            sh """eb deploy ${app}-${environment} --version \$(eb status ${app}-staging | grep "Deployed Version:" | sed "s/.*Deployed Version://")"""
        }
    }
}

def generateEbConfigFile(app){
    writeFile file: ".elasticbeanstalk/config.yml", text: """
global:
  application_name: ${app}
  default_region: us-east-1
  profile: null
  sc: null
"""
}
