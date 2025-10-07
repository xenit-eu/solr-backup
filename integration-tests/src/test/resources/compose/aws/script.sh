#!/bin/bash
echo "--- Creating S3 bucket for Solr backups ---"
awslocal s3 mb s3://bucket

echo "--- Uploading Solr snapshot 'my-test-name-x' to S3 ---"
awslocal s3 sync /backups/snapshot s3://bucket/opt/alfresco-search-services/data/solr6Backup/alfresco/snapshot.my-alfresco-backup-20251006