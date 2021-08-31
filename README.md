# Solr backup

This module builds an artifact which can be used to backup and restore solr indexes into S3 and S3-compatible storage endpoints.

The original code for the S3 backup was taken from: https://github.com/athrog/solr/tree/solr-15089/solr/contrib/s3-repository.  
It has been packported to solr6, since this is the version used by Alfresco currently. It cannot be backported further to solr4, because the only repository supported back then was the filesystem.

Caringo Swarm is a backend system which is compatible with the S3 protocol. However, Caringo Swarm does not have a hierarchical structure and therefore the code for the S3 repository cannot be fully used.  
New classes have been implemented for Caringo Swarm repository.

## Variables

| Environment variable                    | Java system property                           | Comments                               |
| --------------------------- | --------------------------------- | -------------------------------------- |
|                             | -DBLOB_S3_ENDPOINT | Example in docker-compose: JAVA_OPTS_S3_ENDPOINT=-DBLOB_S3_ENDPOINT=http://backup.swarm-s3.service.hetzner-nbg.consul:8090. Needs to be set as a system property, so that it is substituted in solr.xml |
|                             | -DBLOB_S3_BUCKET_NAME | Needs to be set as a system property, so that it is substituted in solr.xml |
| AWS_ACCESS_KEY_ID | | Access key to access the S3 enpoint |
| AWS_SECRET_KEY | | Secret key to access the S3 endpoint |


## Testing locally

### Swarm

Starting up solr with swarm repository (will need credentials + access to a real Swarm, replace relevant properties in docker-compose-swarm.yml):

    ./gradlew integration-tests:solr6:cU

Create a backup in the bucket test-solr-backup in swarm:

    curl "http://localhost:8080/solr/alfresco/replication?command=backup&repository=swarm&location=swarm:///&name=test"

Wait until backup is finished - look for property snapshotCompletedAt:

    curl "http://localhost:8080/solr/alfresco/replication?command=details"

Restore from the backup:

    curl "http://localhost:8080/solr/alfresco/replication?command=restore&repository=swarm&location=swarm:///&name=test"

Check that the restore has been successful:

    curl "http://localhost:8080/solr/alfresco/replication?command=restorestatus"

If the name of the backup is not given when backing up, it will be automatically created as a timestamp. In this case, an additional parameter can be added to the url, specifying how many snapshots are to be kept: <numberToKeep>. In the case of a restore, parameter name is mandatory.

### S3

Starting up solr (using the mock from https://github.com/adobe/S3Mock):

    ./gradlew integration-tests:solr6:s3CU

Create a backup in the bucket TEST_BUCKET in S3:

    curl "http://localhost:8080/solr/alfresco/replication?command=backup&repository=s3&location=s3:/&name=test"

Wait until backup is finished:

    curl "http://localhost:8080/solr/alfresco/replication?command=details"

Restore from the backup:

    curl "http://localhost:8080/solr/alfresco/replication?command=restore&repository=s3&location=s3:/&name=test"

Check that the restore has been successful:

    curl "http://localhost:8080/solr/alfresco/replication?command=restorestatus"

