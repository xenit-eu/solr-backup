pipeline {
    agent any

    stages {
        // run the integration tests against Caringo Swarm @Hetzner and against Amazon S3
        stage("Integration test") {
            environment {
               HETZNER_S3_ENDPOINT = credentials('HETZNER_SWARM_S3_ENDPOINT')
               HETZNER_S3_ACCESS_KEY = credentials('HETZNER_SWARM_S3_ACCESS_KEY')
               HETZNER_S3_SECRET_KEY = credentials('HETZNER_SWARM_S3_SECRET_KEY')
               AWS_S3_BUCKET_NAME = credentials('AWS_S3_SOLRBACKUP_BUCKET_NAME')
               AWS_S3_ACCESS_KEY = credentials('AWS_S3_ACCESS_KEY')
               AWS_S3_SECRET_KEY = credentials('AWS_S3_SECRET_KEY')
            }
            steps {
                sh "./gradlew integration-tests:solr6:integrationTestSwarmHetzner"
                sh "./gradlew integration-tests:solr6:integrationTestAwsS3"
            }
        }
    }

    post {
        always {
            sh "./gradlew composeDownForced"
        }
    }
}
