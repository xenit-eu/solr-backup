# Solr backup

This module builds an artifact which can be used to backup and restore solr indexes into S3 and (some of the) S3-compatible storage endpoints.

The original code for the S3 backup was taken from: https://github.com/athrog/solr/tree/solr-15089/solr/contrib/s3-repository.  
It has been packported to solr6, since this is the version used by Alfresco currently. It cannot be backported further to solr4, because the only backup repository-storage supported back then was the filesystem.  
Backported version is available in src/main/java/org/apache/solr/s3.

Since that version does not work fully even with Amazon S3, an adapted version has been implemented in src/main/java/eu/xenit/solr/backup/s3.

This new version has been tested against multiple S3-compatible backends:

* Amazon S3
* DataCore Swarm docker environment (see https://caringo.atlassian.net/servicedesk/customer/portal/1/article/1335885844): Swarm 12.0.1, Content Gateway 7.5
* DataCore Swarm Xenit's environment: Swarm 11.0.2, Content Gateway 6.2.0
* Minio

Integration tests follow the same line:

* wait for solr to be populated with data, tracking alfresco
* trigger a backup /solr/alfresco/replication?command=backup&repository=s3&location=s3:///&numberToKeep=3
* check if the backup finished in a certain timeout (3 minutes) by following the output of /solr/alfresco/replication?command=details
* trigger a restore /solr/alfresco/replication?command=restore&repository=s3&location=s3:///
* check if the restore was successful in a certain timeout (3 minutes) by following the output of /solr/alfresco/replication?command=restorestatus

## Variables

| Environment variable                    | Java system property                           | Default                                         |     Comments                               |
| --------------------------------------- | ---------------------------------------------- | ------------------------------------------------| ------------------------------------------ |
|                                         | -DS3_ENDPOINT                                  |  http://s3.eu-central-1.amazonaws.com           | Needs to be set as a system property, so that it is substituted in solr.xml |
|                                         | -DS3_BUCKET_NAME                               |                                                 | Needs to be set as a system property, so that it is substituted in solr.xml |
|                                         | -DS3_REGION                                    |  eu-central-1                                   | Needs to be set as a system property, so that it is substituted in solr.xml |
| AWS_ACCESS_KEY_ID                       |                                                |                                                 | Access key to access the S3 enpoint |
| AWS_SECRET_KEY                          |                                                |                                                 | Secret key to access the S3 endpoint |
| AWS_SHARED_CREDENTIALS_FILE             |                                                |                                                 | File with credentials (if they are not set via environment variables)                |



## Testing against DataCore Swarm docker

    ./gradlew integration-tests:solr6:integrationTestSwarmDocker

 Solr waits for the Swarm environment to be started and then requests an ACCESS_KEY via an init script, which is further used to authenticate.

## Testing against Amazon S3

Before running the tests, following variables need to be set to an S3 bucket and working credentials.

    export AWS_S3_BUCKET_NAME=...
    export AWS_S3_ACCESS_KEY=...
    export AWS_S3_SECRET_KEY=...
    
    ./gradlew integration-tests:solr6:integrationTestAwsS3


## Testing against Xenit's Swarm environment at Hetzner

Before running the tests, following variables need to be set to an S3 bucket and working credentials.

    export HETZNER_S3_ACCESS_KEY=...
    export HETZNER_S3_SECRET_KEY=...
    
    ./gradlew integration-tests:solr6:integrationTestSwarmHetzner


# Testing against Minio

Before running the tests, following variables need to be set to an S3 bucket and working credentials.

    ./gradlew integration-tests:solr6:integrationTestMinio

These tests fail, likely because Minio does not support S3 compatible folder structure: https://github.com/minio/minio/issues/10160


