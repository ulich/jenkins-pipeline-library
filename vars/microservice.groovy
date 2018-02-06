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
                dir('infrastructure') {
                    stage('Terraform plan') {
                        planFile = "planfile-$env.BUILD_TAG"

                        sh "terraform init"
                        hasChanges = terraformPlan(planFile, appName)
                    }

                    stage('Terraform apply') {
                        if (hasChanges) {
                            input message: 'Do you wish to apply the plan?'
                            sh "terraform apply ${planFile}"
                        }
                    }
                }

                stage('Deploy') {
                    eb_deploy(appName)
                }
            }
        }
    }

}

def terraformPlan(planFile, app) {
    exit_code = sh (script: "terraform plan -var 'app_name=${app}' -detailed-exitcode -out ${planFile}", returnStatus: true)
    if (exit_code == 1) {
        error('Plan failed. Please review')
    }
    return exit_code == 2
}

def eb_deploy(app) {
    sh """
        mkdir deploy || true
        cp -r .ebextensions deploy/ || true
        cp build/libs/*.jar deploy/
    """
    dir('deploy') {
        generateEbConfigFile(app)
        sh "eb deploy ${app}"
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
