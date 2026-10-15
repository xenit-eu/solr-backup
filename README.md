# Solr backup

This module builds an artifact which can be used to backup and restore solr indexes into S3 and (some of the)
S3-compatible storage endpoints.

The original code for the S3 backup was taken
from: https://github.com/athrog/solr/tree/solr-15089/solr/contrib/s3-repository.  
It has been packported to solr6, since this is the version used by Alfresco currently. It cannot be backported further
to solr4, because the only backup repository-storage supported back then was the filesystem.  
Backported version is available in src/main/java/org/apache/solr/s3.

Since that version does not work fully even with Amazon S3, an adapted version has been implemented in
src/main/java/eu/xenit/solr/backup/s3.

This new version has been tested against S3-compatible backends:

* localstack s3

Integration tests follow the same line:

* wait for solr to be populated with data, tracking alfresco
* trigger a backup /solr/alfresco/replication?command=backup&repository=s3&location=s3:///&numberToKeep=3
* check if the backup finished in a certain timeout (3 minutes) by following the output of
  /solr/alfresco/replication?command=details
* trigger a restore /solr/alfresco/replication?command=restore&repository=s3&location=s3:///
* check if the restore was successful in a certain timeout (3 minutes) by following the output of
  /solr/alfresco/replication?command=restorestatus
## Setup

you need to put the solr.xml file under /opt/alfresco-search-services/solrhome/
```
<?xml version='1.0' encoding='UTF-8'?>
<solr>
    <str name="adminHandler">${adminHandler:org.alfresco.solr.AlfrescoCoreAdminHandler}</str>
    <backup>
        <repository name="s3" class="eu.xenit.solr.backup.s3.S3BackupRepository" default="false">
            <str name="s3.bucket.name">${S3_BUCKET_NAME:}</str>
            <str name="s3.endpoint">${S3_ENDPOINT:http://s3.eu-central-1.amazonaws.com}</str>
            <str name="s3.region">${S3_REGION:eu-central-1}</str>
            <str name="s3.access.key">${S3_ACCESS_KEY:}</str>
            <str name="s3.secret.key">${S3_SECRET_KEY:}</str>
            <str name="s3.proxy.host">${S3_PROXY_HOST:}</str>
            <int name="s3.proxy.port">${S3_PROXY_PORT:0}</int>
            <bool name="s3.path.style.access.enabled">${S3_PATH_STYLE_ACCESS_ENABLED:false}</bool>
            <bool name="s3.incremental.enabled">${S3_INCREMENTAL_ENABLED:false}</bool>
        </repository>
    </backup>
</solr>
```

and specify the Environment or Java variables 

## Variables

all of these variable can be set as environment variable or as a system property so that it is substituted in solr.xml

| Environment variable         | Java system property           | Default                              | required |
|------------------------------|--------------------------------|--------------------------------------|----------|
| S3_ENDPOINT                  | -DS3_ENDPOINT                  | http://s3.eu-central-1.amazonaws.com | false    |
| S3_BUCKET_NAME               | -DS3_BUCKET_NAME               |                                      | true     |
| S3_REGION                    | -DS3_REGION                    | eu-central-1                         | false    |
| S3_ACCESS_KEY                | -DS3_ACCESS_KEY                |                                      | false    |
| S3_SECRET_KEY                | -DS3_SECRET_KEY                |                                      | false    |
| S3_PROXY_HOST                | -DS3_PROXY_HOST                |                                      | false    |
| S3_PROXY_PORT                | -DS3_PROXY_PORT                |                                      | false    |
| S3_PATH_STYLE_ACCESS_ENABLED | -DS3_PATH_STYLE_ACCESS_ENABLED | false                                | false    |
| S3_INCREMENTAL_ENABLED       | -DS3_INCREMENTAL_ENABLED       | false                                | false    |

## Incremental backups (optional)

By default every backup uploads every file of the index. When `s3.incremental.enabled` is `true`
(`S3_INCREMENTAL_ENABLED`), files that are unchanged since the latest previous `snapshot.<timestamp>` are copied
server-side within the bucket instead of being uploaded again. This is intended to reduce the backup duration, but
the improvement has not been measured yet and depends on the storage backend and the index.
Every snapshot remains a complete, self-contained set of index files, so restore and `numberToKeep`
behave as before.

A file is reused when its name, length and the checksum recorded in its Lucene footer match those of the file in the
previous snapshot. The stored checksum is read with a ranged GET of the last 16 bytes of the object. Reused files are
not read or validated locally again. Files that differ from, or are missing in, the previous snapshot are uploaded as
usual, as are files larger than 5 GB. If a request for reusing a file fails unexpectedly (for example because of
missing permissions or because the storage doesn't support it), a warning is logged once and the remaining files of
that snapshot are uploaded without trying to reuse them. The backup itself is not affected.

The target storage must support server-side copy (`CopyObject`) and ranged GET requests. This is expected for AWS S3,
but should be verified before enabling it on any other S3-compatible storage. Measure the backup duration with and
without the option on your own data before relying on it.

### Required permissions

Besides the permissions needed for ordinary backups (listing the bucket, and reading, writing and deleting objects),
reusing files needs:

* `s3:GetObject` on the previous snapshot, to read the last 16 bytes of its files and to use them as the source of
  the copy;
* `s3:PutObject` on the new snapshot, for the copy itself.

If the bucket uses SSE-KMS encryption, the copy also needs `kms:Decrypt` on the key of the previous snapshot's objects
and `kms:GenerateDataKey` on the key used for the new objects. No encryption settings are specified for the copy, so
the new objects get the default encryption of the bucket, like uploaded files. If the keys differ, permissions on both
are needed. These requirements are based on the AWS documentation. SSE-KMS and the permission setup are not covered by
this project's tests, so verify them on your own configuration.

## Testing against DataCore Swarm docker

    ./gradlew integration-tests:solr6:integrationTestSwarmDocker

Solr waits for the Swarm environment to be started and then requests an ACCESS_KEY via an init script, which is further
used to authenticate.

## Testing LocalStack S3 or any other s3  bucket

Before running the tests, following variables need to be set to an S3 bucket and working credentials.

    export S3_ENDPOINT=...
    export S3_REGION=...
    export S3_BUCKET_NAME=...
    export S3_ACCESS_KEY=...
    export S3_SECRET_KEY=...
    
    ./gradlew integration-tests:solr6:integrationTest


