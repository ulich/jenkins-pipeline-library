def region = "us-east-1"

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
                stage('Deploy') {
                    eb_deploy(appName)
                }
            }
        }
    }

}

def eb_deploy(app) {
    generateEbConfigFile(app)
    
    sh "docker run --rm -v `pwd`:/deploy --workdir /deploy coxauto/aws-ebcli eb deploy ${app}"
}

def generateEbConfigFile(app){
    writeFile file: ".elasticbeanstalk/config.yml", text: """
global:
  application_name: ${app}
  default_region: ${region}
  profile: null
  sc: null
"""
}
