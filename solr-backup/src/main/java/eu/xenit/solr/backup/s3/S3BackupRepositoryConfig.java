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

/**
 * Class representing the {@code backup} S3 config bundle specified in solr.xml. All user-provided
 * config can be overridden via environment variables (use uppercase, with '_' instead of '.'), see
 * {@link S3BackupRepositoryConfig#toEnvVar}.
 */
public class S3BackupRepositoryConfig {
    public static final String S3_BUCKET_NAME = "s3.bucket.name";
    public static final String S3_REGION = "s3.region";
    public static final String S3_ACCESS_KEY = "s3.access.key";
    public static final String S3_SECRET_KEY = "s3.secret.key";
    public static final String S3_ENDPOINT = "s3.endpoint";
    public static final String S3_PROXY_HOST = "s3.proxy.host";
    public static final String S3_PROXY_PORT = "s3.proxy.port";

    private final String bucketName;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String proxyHost;
    private final int proxyPort;
    private final String endpoint;

    public S3BackupRepositoryConfig(NamedList<?> config) {
        region = getStringConfig(config, S3_REGION);
        bucketName = getStringConfig(config, S3_BUCKET_NAME);
        proxyHost = getStringConfig(config, S3_PROXY_HOST);
        proxyPort = getIntConfig(config, S3_PROXY_PORT);
        endpoint = getStringConfig(config, S3_ENDPOINT);
        accessKey = getStringConfig(config, S3_ACCESS_KEY);
        secretKey = getStringConfig(config, S3_SECRET_KEY);
    }

    /**
     * @return a {@link S3StorageClient} from the provided config.
     */
    public S3StorageClient buildClient() {
        return new S3StorageClient(bucketName, region, proxyHost, proxyPort, endpoint, accessKey, secretKey);
    }

    private static String getStringConfig(NamedList<?> config, String property) {
        String envProp = System.getenv().get(toEnvVar(property));
        if (envProp == null) {
            Object configProp = config.get(property);
            return configProp == null ? null : configProp.toString();
        } else {
            return envProp;
        }
    }

    private static int getIntConfig(NamedList<?> config, String property) {
        String envProp = System.getenv().get(toEnvVar(property));
        if (envProp == null) {
            Object configProp = config.get(property);
            return configProp instanceof Integer ? (int) configProp : 0;
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
            Boolean configProp = config.getBooleanArg(property);
            return configProp != null && configProp;
        } else {
            return Boolean.parseBoolean(envProp);
        }
    }

    private static String toEnvVar(String property) {
        return property.toUpperCase().replace('.', '_');
    }
}
