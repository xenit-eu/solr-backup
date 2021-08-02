pipeline {
    agent any

    stages {
        stage("Integration test") {
            steps {
                sh "./gradlew integrationTest"
            }
        }
    

        stage('Publish') {
            when {
                anyOf {
                    branch "master*"
                    branch "release*"
                }
            }
            environment {
                SONATYPE_CREDENTIALS = credentials('sonatype')
                GPGPASSPHRASE = credentials('gpgpassphrase')
            }
            steps {
                script {
                    sh "./gradlew publish -Ppublish_username=${SONATYPE_CREDENTIALS_USR} -Ppublish_password=${SONATYPE_CREDENTIALS_PSW} -PkeyId=DF8285F0 -Ppassword=${GPGPASSPHRASE} -PsecretKeyRingFile=/var/jenkins_home/secring.gpg"
                }
            }
        }
    }

    post {
        always {
            sh "./gradlew composeDownForced"
        }
    }
}
