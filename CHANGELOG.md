---
title: Changelog - Solr Backup

# Alfresco Backup Changelog
## v0.0.10 - 30-11-2023

* DOCKER-444 fix solr backup using filesystem instead of cache for restoring data

## v0.0.9 - 28-11-2023

* DOCKER-442 fix solr backup numberToLive

## v0.0.8 - 20-11-2023

* DOCKER-441 improve solr backup documentation and add new env

## v0.0.5 - 3-11-2023

* OUPDAUNTLE-54 drop aws keys and use integrated env variables


## v0.0.4 - 28-2-2023

* OUPDAUNTLE-54 fix solr backup to s3
  * drop swarm and aws specific stuff
  * create folder on first call if they don't exist


## v0.0.3 - 28-09-2021


* Second iteration [#2]
  * Keep single implementation for S3 and Swarm
  * Add integration tests
  * Test against various setups
  * Build and publish via github actions

[#2]: https://github.com/xenit-eu/solr-backup/pull/2

### Added
* [ALFREDOPS-764] First version [#1]

[#1]: https://github.com/xenit-eu/solr-backup/pull/1


