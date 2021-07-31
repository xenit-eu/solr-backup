# Solr backup

This module builds an artifact which can be used to backup and restore solr indexes into S3 and S3-compatible storage endpoints.

The original code for the S3 backup was taken from: https://github.com/athrog/solr/tree/solr-15089/solr/contrib/s3-repository.  
It has been packported to solr6, since this is the version used by Alfresco currently. It cannot be backported further to solr4, because the only repository supported back then was the filesystem.

Caringo Swarm is a backend system which is compatible with the S3 protocol. However, Caringo Swarm does not have a hierarchical structure and therefore the code for the S3 repository cannot be fully used.  
New classes have been implemented for Caringo Swarm repository.

## Testing locally

### S3

Starting up solr (using the mock from https://github.com/adobe/S3Mock):

    ./gradlew integration-tests:solr6:s3CU

Create a backup in the bucket TEST_BUCKET in S3:

    curl "http://localhost:8080/solr/alfresco/replication?command=backup&repository=s3&location=s3:/&name=test"

Wait until backup is finished - look for property snapshotCompletedAt:

    curl "http://localhost:8080/solr/alfresco/replication?command=details"

Restore from the backup:

    curl "http://localhost:8080/solr/alfresco/replication?command=restore&repository=s3&location=s3:/&name=test"

Check that the restore has been successful:

    curl "http://localhost:8080/solr/alfresco/replication?command=restorestatus"

### Swarm

Starting up solr with swarm repository (will need credentials + access to a real Swarm, replace relevant properties in docker-compose-swarm.yml):

    ./gradlew integration-tests:solr6:cU

Create a backup in the bucket test-solr-backup in swarm:

    curl "http://localhost:8080/solr/alfresco/replication?command=backup&repository=swarm&location=swarm:///&name=test"

Wait until backup is finished - status can be seen via:

    curl "http://localhost:8080/solr/alfresco/replication?command=details"

Restore from the backup:

    curl "http://localhost:8080/solr/alfresco/replication?command=restore&repository=swarm&location=swarm:///&name=test"

Check that the restore has been successful:

    curl "http://localhost:8080/solr/alfresco/replication?command=restorestatus"
