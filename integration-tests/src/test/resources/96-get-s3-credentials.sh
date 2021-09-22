#!/bin/bash

set -e

echo "Solr get s3 credentials start"
if [ -z ${AWS_ACCESS_KEY_ID} ]
then
    # assuming docker setup for caringo swarm, with content gateway available at http://backup42:4284, having credentials caringoadmin/caringo
    AWS_ACCESS_KEY_ID=$(curl -fsS --resolve backup42:172.17.0.1 -o - -u caringoadmin@:caringo -X POST --data-binary '' -H 'X-User-Secret-Key-Meta: secret' -H 'X-User-Token-Expires-Meta: +90' http://backup42:4284/.TOKEN/ | cut -d ' ' -f 2)
    echo "AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}"
    while [ -z $AWS_ACCESS_KEY_ID ]
    do
	echo "Waiting 30 sec, s3 endpoint not yet available"
	sleep 30
	AWS_ACCESS_KEY_ID=$(curl -fsS --resolve backup42:172.17.0.1 -o - -u caringoadmin@:caringo -X POST --data-binary '' -H 'X-User-Secret-Key-Meta: secret' -H 'X-User-Token-Expires-Meta: +90' http://backup42:4284/.TOKEN/ | cut -d ' ' -f 2)
	echo "AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}"
    done

    # since it is not possible to set a global environment variable, set credentials as system properties
    setJavaOption "aws.accessKeyId" "-Daws.accessKeyId=${AWS_ACCESS_KEY_ID}"
    setJavaOption "aws.secretKey" "-Daws.secretKey=secret"
    echo "exec gosu ${user} ${SOLR_INSTALL_HOME}/solr/bin/solr start -f -m ${JAVA_XMX} -p ${PORT} -h ${SOLR_HOST} -s ${SOLR_DIR_ROOT} -a \"${JAVA_OPTS}\"" >"${SOLR_INSTALL_HOME}/startup.sh"
else
    echo "Access key already set via environment variable"
fi
echo "Solr get s3 credentials end"
