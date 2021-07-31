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
package eu.xenit.solr.backup.s3;

import org.apache.solr.common.util.NamedList;

import java.util.Locale;

/**
 * Class representing the {@code backup} blob config bundle specified in solr.xml. All user-provided config can be
 * overridden via environment variables (use uppercase, with '_' instead of '.'), see {@link S3BackupRepositoryConfig#toEnvVar}.
 */
public class S3BackupRepositoryConfig {

    public static final String BUCKET_NAME = "blob.s3.bucket.name";
    public static final String REGION = "blob.s3.region";
    public static final String ENDPOINT = "blob.s3.endpoint";
    public static final String PROXY_HOST = "blob.s3.proxy.host";
    public static final String PROXY_PORT = "blob.s3.proxy.port";
    public static final String S3MOCK = "blob.s3.mock";

    private final String bucketName;
    private final String region;
    private final String proxyHost;
    private final int proxyPort;
    private final String endpoint;
    private final boolean s3mock;

    public S3BackupRepositoryConfig(NamedList<?> args) {
        NamedList<?> config = args.clone();

        region = getStringConfig(config, REGION);
        bucketName = getStringConfig(config, BUCKET_NAME);
        proxyHost = getStringConfig(config, PROXY_HOST);
        proxyPort = getIntConfig(config, PROXY_PORT);
        endpoint = getStringConfig(config, ENDPOINT);
        s3mock = getBooleanConfig(config, S3MOCK);
    }

    /**
     * Construct a {@link S3StorageClient} from the provided config.
     */
    public S3StorageClient buildClient() {
        if (s3mock) {
            return new AdobeMockS3StorageClient(bucketName, endpoint);
        } else {
            return new S3StorageClient(bucketName, region, proxyHost, proxyPort, endpoint);
        }
    }

    protected static String getStringConfig(NamedList<?> config, String property) {
        String envProp = System.getenv().get(toEnvVar(property));
        if (envProp == null) {
            var configProp = config.get(property);
            return configProp == null ? null : configProp.toString();
        } else {
            return envProp;
        }
    }

    protected static int getIntConfig(NamedList<?> config, String property) {
        String envProp = System.getenv().get(toEnvVar(property));
        if (envProp == null) {
            var configProp = config.get(property);
            return configProp == null ? 0 : (int) configProp;
        } else {
            return Integer.parseInt(envProp);
        }
    }

    /**
     * If the property as any other value than 'true' or 'TRUE', this will default to false.
     */
    private static boolean getBooleanConfig(NamedList<?> config, String property) {
        String envProp = System.getenv().get(toEnvVar(property));
        if (envProp == null) {
            var configProp = config.getBooleanArg(property);
            return configProp != null && configProp;
        } else {
            return Boolean.parseBoolean(envProp);
        }
    }

    private static String toEnvVar(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_');
    }
}