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

def deploy(appName, environmentName) {
    def planFile = "planfile-${environmentName}-$env.BUILD_TAG"
    def terraformWorkspace = environmentName == "staging" ? "default" : environmentName

    dir('infrastructure') {
        stage("Terraform plan ${environmentName}") {
            sh """
                terraform init
                terraform workspace select ${terraformWorkspace}
            """
            hasChanges = terraformPlan(planFile, appName)
        }

        stage("Terraform apply ${environmentName}") {
            if (hasChanges) {
                input message: 'Do you wish to apply the plan?'
                sh "terraform apply ${planFile}"
            }
        }
    }

    stage("Deploy ${environmentName}") {
        eb_deploy(appName, environmentName)
    }
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
        sh "eb deploy ${app}-${environment}"
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
