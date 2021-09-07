/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.xenit.solr.backup.s3.swarm;

import eu.xenit.solr.backup.s3.S3BackupRepositoryConfig;
import eu.xenit.solr.backup.s3.S3StorageClient;
import org.apache.solr.common.util.NamedList;

public class SwarmS3BackupRepositoryConfig extends S3BackupRepositoryConfig {

    public static final String BUCKET_NAME = "blob.s3.bucket.name";
    public static final String ENDPOINT = "blob.s3.endpoint";

    private final String bucketName;
    private final String endpoint;

    public SwarmS3BackupRepositoryConfig(NamedList<?> args) {
        super(args);
        NamedList<?> config = args.clone();

        bucketName = getStringConfig(config, BUCKET_NAME);
        endpoint = getStringConfig(config, ENDPOINT);
    }

    /**
     * Construct a {@link S3StorageClient} from the provided config
     */
    public SwarmS3StorageClient buildClient() {
        return new SwarmS3StorageClient(bucketName, endpoint);
    }
}
