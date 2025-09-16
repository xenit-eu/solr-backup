#!/bin/bash
echo "--- Creating S3 bucket for Solr backups ---"
awslocal s3 mb s3://bucket

echo "--- Uploading Solr snapshot 'my-test-name-x' to S3 ---"
awslocal s3 sync /backups/snapshot.my-test-name-1 s3://bucket/snapshot.my-test-name-1/
awslocal s3 sync /backups/snapshot.my-test-name-0 s3://bucket/snapshot.my-test-name-0/
awslocal s3 sync /backups/snapshot.my-test-name-2 s3://bucket/snapshot.my-test-name-2/