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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.apache.commons.io.input.ClosedInputStream;
import org.apache.solr.common.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Creates a {@link S3Client} for communicating with AWS S3. Utilizes the default credential
 * provider chain; reference <a
 * href="https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html">AWS SDK
 * docs</a> for details on where this client will fetch credentials from, and the order of
 * precedence.
 */
class S3StorageClient {

    private static final Logger log = LoggerFactory.getLogger(S3StorageClient.class);

    static final String S3_FILE_PATH_DELIMITER = "/";

    // S3 has a hard limit of 1000 keys per batch delete request
    private static final int MAX_KEYS_PER_BATCH_DELETE = 1000;

    // Metadata name used to identify flag directory entries in S3
    private static final String S3_DIR_CONTENT_TYPE = "application/x-directory";

    // Error messages returned by S3 for a key not found.
    private static final Set<String> NOT_FOUND_CODES = Set.of("NoSuchKey", "404 Not Found");

    private final S3Client s3Client;

    /**
     * The S3 bucket where we read/write all data.
     */
    private final String bucketName;

    S3StorageClient(
            String bucketName, String region, String proxyHost, int proxyPort, String endpoint, String accessKey, String secretKey, Boolean pathStyleAccessEnabled, Boolean checksumValidationEnabled) throws URISyntaxException {
        this(createInternalClient(region, proxyHost, proxyPort, endpoint, accessKey, secretKey, pathStyleAccessEnabled, checksumValidationEnabled), bucketName);
    }

    @VisibleForTesting
    S3StorageClient(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    private static S3Client createInternalClient(
            String region,
            String proxyHost,
            int proxyPort,
            String endpoint,
            String accessKey,
            String secretKey, Boolean pathStyleAccessEnabled,
            Boolean checksumValidationEnabled) throws URISyntaxException {

        S3ClientBuilder clientBuilder = S3Client.builder();

        S3Configuration configuration = S3Configuration.builder()
                .checksumValidationEnabled(checksumValidationEnabled)
                .build();
        clientBuilder.serviceConfiguration(configuration);

        /*
         * SDK v2 Migration: Proxy settings are now configured on the HTTP client,
         * not on a general client configuration object.
         */
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();
        if (!StringUtils.isEmpty(proxyHost)) {
            ProxyConfiguration.Builder proxyConfigBuilder = ProxyConfiguration.builder()
                    .endpoint(URI.create(proxyHost + ":" + proxyPort));
            httpClientBuilder.proxyConfiguration(proxyConfigBuilder.build());
        }
        clientBuilder.httpClientBuilder(httpClientBuilder);

        /*
         * SDK v2 Migration: ClientOverrideConfiguration is still used for high-level
         * configuration, but protocol and proxy settings have been moved.
         * The protocol is now inferred from the endpoint URI (defaulting to HTTPS).
         */
        clientBuilder.overrideConfiguration(ClientOverrideConfiguration.builder().build());

        if (!(StringUtils.isEmpty(accessKey) || StringUtils.isEmpty(secretKey))) {
            clientBuilder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            log.info("No accessKey or secretKey configured, using default credentials provider chain");
        }

        /*
         * SDK v2 Migration: `setEndpointConfiguration` from v1 is replaced by
         * `endpointOverride`. The region must still be set separately.
         */
        if (!StringUtils.isEmpty(endpoint)) {
            clientBuilder.endpointOverride(new URI(endpoint));
        }
        clientBuilder.region(Region.of(region));

        /*
         * SDK v2 Migration: The method `withPathStyleAccessEnabled(boolean)` from v1 is
         * replaced by `forcePathStyle(boolean)` in v2.
         */
        if (pathStyleAccessEnabled != null) {
            clientBuilder.forcePathStyle(pathStyleAccessEnabled);
        }

        return clientBuilder.build();
    }

    /**
     * Create a directory in S3.
     */
    void createDirectory(String path) throws S3Exception {
        path = sanitizedDirPath(path);

        if (!parentDirectoryExist(path)) {
            createDirectory(getParentDirectory(path));
            // TODO see https://issues.apache.org/jira/browse/SOLR-15359
            //            throw new S3Exception("Parent directory doesn't exist, path=" + path);
        }

        try {
            /*
             * SDK v2 Migration:
             * - Removed the v1-style use of an empty InputStream and an ObjectMetadata object.
             * - Replaced the v1 constructor `new PutObjectRequest(...)` with the v2 builder pattern.
             * In v2, request parameters like bucket and key are set using builder methods.
             */
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();
            s3Client.putObject(putRequest, RequestBody.empty());
        } catch (SdkException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Delete files from S3. Deletion order is not guaranteed.
     *
     * @throws S3NotFoundException if the number of deleted objects does not match {@code entries}
     *                             size
     */
    void delete(Collection<String> paths) throws S3Exception {
        Set<String> entries = new HashSet<>();
        for (String path : paths) {
            entries.add(sanitizedFilePath(path));
        }

        Collection<String> deletedPaths = deleteObjects(entries);

        // If we haven't deleted all requested objects, assume that's because some were missing
        if (entries.size() != deletedPaths.size()) {
            Set<String> notDeletedPaths = new HashSet<>(entries);
            entries.removeAll(deletedPaths);
            throw new S3NotFoundException(notDeletedPaths.toString());
        }
    }

    /**
     * Delete directory, all the files and sub-directories from S3.
     *
     * @param path Path to directory in S3.
     */
    void deleteDirectory(String path) throws S3Exception {
        path = sanitizedDirPath(path);

        Set<String> entries = new HashSet<>();
        if (pathExists(path)) {
            entries.add(path);
        }

        // Get all the files and subdirectories
        entries.addAll(listAll(path));

        deleteObjects(entries);
    }

    /**
     * List all the files and sub-directories directly under given path.
     *
     * @param path Path to directory in S3.
     * @return Files and sub-directories in path.
     */
    String[] listDir(String path) throws S3Exception {
        path = sanitizedDirPath(path);

        final String prefix; // final for use in lambda
        if (!path.equals("/")) prefix = path;
        else {
            prefix = "";
        }
        /*
         * SDK v2 Migration: Switched from the generic `ListObjectsRequest` to the recommended
         * `ListObjectsV2Request` for better performance and features.
         */
        ListObjectsV2Request listRequest =
                ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .prefix(prefix)
                        .delimiter(S3_FILE_PATH_DELIMITER)
                        .build();

        List<String> entries = new ArrayList<>();
        try {
            /*
             * SDK v2 Migration: Replaced the manual `while(true)` loop and the non-existent
             * `listNextBatchOfObjects` method with the SDK v2 paginator.
             * The `listObjectsV2Paginator` automatically handles making subsequent requests
             * to fetch all pages of results, simplifying the code significantly.
             */
            s3Client.listObjectsV2Paginator(listRequest).forEach(page -> {
                // Process the common prefixes (subdirectories).
                List<String> commonPrefixes = page.commonPrefixes().stream()
                        .map(cp -> cp.prefix())
                        .collect(Collectors.toList());

                /*
                 * SDK v2 Migration: The object list in `ListObjectsV2Response` is accessed
                 * via the `contents()` method, not `objectSummaries()`.
                 */
                List<String> files =
                        page.contents().stream()
                                .map(S3Object::key)
                                .collect(Collectors.toList());
                files.addAll(commonPrefixes);
                List<String> processedFiles =
                        files.stream()
                                .filter(s -> s.startsWith(prefix))
                                .map(s -> s.substring(prefix.length()))
                                .filter(s -> !s.isEmpty())
                                .filter(
                                        s -> {
                                            int slashIndex = s.indexOf(S3_FILE_PATH_DELIMITER);
                                            return slashIndex == -1 || slashIndex == s.length() - 1;
                                        })
                                .map(
                                        s -> {
                                            if (s.endsWith(S3_FILE_PATH_DELIMITER)) {
                                                return s.substring(0, s.length() - 1);
                                            }
                                            return s;
                                        })
                                .collect(Collectors.toList());

                entries.addAll(processedFiles);
            });

            return entries.toArray(new String[0]);
        } catch (SdkException ase) {
            throw handleAmazonException(ase);
        }
    }

    HeadObjectResponse getObjectMetadata(String path) throws SdkException {
        /*
         * SDK v2 Migration: Replaced the v1 `getObjectMetadata` method with a `headObject` call.
         * This is the standard v2 way to retrieve object metadata without fetching the object's content.
         */
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(path)
                .build();
        return s3Client.headObject(request);
    }

    /**
     * Check if path exists.
     *
     * @param path to File/Directory in S3.
     * @return true if path exists, otherwise false?
     */
    boolean pathExists(String path) throws S3Exception {
        if (isDirectory(path)) {
            return true;
        }
        path = sanitizedPath(path);

        // for root return true
        if (path.isEmpty() || S3_FILE_PATH_DELIMITER.equals(path)) {
            return true;
        }

        try {
            /*
             * SDK v2 Migration: Replaced the v1 `doesObjectExist` convenience method.
             * The standard v2 pattern is to make a lightweight `headObject` request.
             * If the request succeeds, the object exists. If it throws a `NoSuchKeyException`,
             * the object does not exist.
             */
            getObjectMetadata(path);
            return true;
        } catch (NoSuchKeyException e) {
            // This is the expected exception when an object is not found.
            return false;
        } catch (SdkException ase) {
            // Any other exception indicates a real problem (e.g., permissions).
            throw handleAmazonException(ase);
        }
    }

    /**
     * Check if path is directory.
     *
     * @param path to File/Directory in S3.
     * @return true if path is directory, otherwise false.
     */
    boolean isDirectory(String path) throws S3Exception {
        String dirPath = sanitizedDirPath(path);

        try {
            HeadObjectResponse dirResponse = getObjectMetadata(path);
            // SDK v2 Migration: Get the content type from the response object.
            String contentType = dirResponse.contentType();
            return !StringUtils.isEmpty(contentType) && contentType.equalsIgnoreCase(S3_DIR_CONTENT_TYPE);

        } catch (NoSuchKeyException e) {
            // SDK v2 Migration: Catch the specific `NoSuchKeyException` instead of the broad `AmazonClientException`.
            // This indicates the directory marker object (e.g., "path/") was not found. Now, try the file path as a fallback.
            String filePath = sanitizedFilePath(path);
            try {
                HeadObjectResponse fileResponse = getObjectMetadata(filePath);
                String contentType = fileResponse.contentType();
                return !StringUtils.isEmpty(contentType) && contentType.equalsIgnoreCase(S3_DIR_CONTENT_TYPE);
            } catch (NoSuchKeyException ex) {
                // The key doesn't exist as a directory marker or a file.
                log.info("Could not find key for '{}' in S3, tried both as a folder and as a file.", path);
                return false;
            }
        } catch (SdkException ase) {
            // SDK v2 Migration: Catch the base `SdkException` for all other client or service errors.
            throw handleAmazonException(ase);
        }
    }

    /**
     * Get length of file in bytes.
     *
     * @param path to file in S3.
     * @return length of file.
     */
    long length(String path) throws S3Exception {
        path = sanitizedFilePath(path);
        try {
            HeadObjectResponse objectMetaData = getObjectMetadata(path);
            String contentType = objectMetaData.contentType();

            if (StringUtils.isEmpty(contentType) || !contentType.equalsIgnoreCase(S3_DIR_CONTENT_TYPE)) {
                return objectMetaData.contentLength();
            }
            throw new S3Exception("Path is Directory");
        } catch (SdkException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Open a new {@link InputStream} to file for read. Caller needs to close the stream.
     *
     * @param path to file in S3.
     * @return InputStream for file.
     */
    InputStream pullStream(String path) throws S3Exception {
        path = sanitizedFilePath(path);

        try {
            ResponseInputStream<GetObjectResponse> requestedObject = s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(path)
                    .build());
            // This InputStream instance needs to be closed by the caller
            return requestedObject;
        } catch (SdkException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Open a new {@link OutputStream} to file for write. Caller needs to close the stream.
     *
     * @param path to file in S3.
     * @return OutputStream for file.
     */
    OutputStream pushStream(String path) throws S3Exception {
        path = sanitizedFilePath(path);

        if (!parentDirectoryExist(path)) {
            throw new S3Exception("Parent directory doesn't exist of path: " + path);
        }

        try {
            return new S3OutputStream(s3Client, path, bucketName);
        } catch (SdkException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Override {@link Closeable} since we throw no exception.
     */
    void close() {
        s3Client.close();
    }

    /**
     * Any file path that specifies a non-existent file will not be treated as an error.
     */
    private Collection<String> deleteObjects(Collection<String> paths) throws S3Exception {
        try {
            /*
             * Per the S3 docs:
             * https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/s3/model/DeleteObjectsResult.html
             * An exception is thrown if there's a client error processing the request or in S3 itself.
             * However, there's no guarantee the delete did not happen if an exception is thrown.
             */
            return deleteObjects(paths, MAX_KEYS_PER_BATCH_DELETE);
        } catch (SdkException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Batch deletes from S3.
     *
     * @param entries   collection of S3 keys of the files to be deleted.
     * @param batchSize number of deletes to send to S3 at a time
     */
    @VisibleForTesting
    Collection<String> deleteObjects(Collection<String> entries, int batchSize) throws S3Exception {
        /*
         * SDK v2 Migration: Replaced the v1 `KeyVersion` class with the v2 `ObjectIdentifier`.
         * `ObjectIdentifier` is the standard way to specify a key for batch operations.
         */
        List<ObjectIdentifier> keysToDelete = entries.stream()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .collect(Collectors.toList());

        keysToDelete.sort(Comparator.comparing(ObjectIdentifier::key).reversed());
        List<List<ObjectIdentifier>> partitions = Lists.partition(keysToDelete, batchSize);
        Set<String> deletedPaths = new HashSet<>();

        boolean deleteIndividually = false;
        for (List<ObjectIdentifier> partition : partitions) {
            DeleteObjectsRequest request = createBatchDeleteRequest(partition);

            try {
                DeleteObjectsResponse result = s3Client.deleteObjects(request);

                /*
                 * SDK v2 Migration: The response object's method to get deleted items is `deleted()`,
                 * not `deletedObjects()`. The items in the list are of type `DeletedObject`.
                 */
                result.deleted().stream()
                        .map(DeletedObject::key)
                        .forEach(deletedPaths::add);
            } catch (AwsServiceException e) {
                // This means that the batch-delete is not implemented by this S3 server
                if (e.awsErrorDetails().sdkHttpResponse().statusCode() == 501) {
                    deleteIndividually = true;
                    break;
                } else {
                    throw e;
                }
            }
        }

        if (deleteIndividually) {
            for (ObjectIdentifier k : keysToDelete) {
                try {
                    s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(k.key())
                            .build());
                    deletedPaths.add(k.key());
                } catch (SdkException e) {
                    throw new S3Exception("Could not delete object with key: " + k.key(), e);
                }
            }
        }

        return deletedPaths;
    }

    private DeleteObjectsRequest createBatchDeleteRequest(List<ObjectIdentifier> keysToDelete) {
        /*
         * SDK v2 Migration: The request requires a `Delete` object that wraps the list
         * of `ObjectIdentifier`s, instead of passing the list directly to the request builder.
         */
        Delete deleteAction = Delete.builder()
                .objects(keysToDelete)
                .build();

        return DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete(deleteAction)
                .build();
    }

    private List<String> listAll(String path) throws S3Exception {
        String prefix = sanitizedDirPath(path);

        /*
         * SDK v2 Migration: Switched from the generic `ListObjectsRequest` to the recommended
         * `ListObjectsV2Request`.
         */
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

        List<String> entries = new ArrayList<>();
        try {
                /*
                 * SDK v2 Migration: Replaced the manual `while` loop and the non-existent
                 * `listNextBatchOfObjects` method with the idiomatic v2 paginator.
                 * The `listObjectsV2Paginator` automatically handles the logic of fetching
                 * subsequent pages of results until all objects are listed.
                 */
                s3Client.listObjectsV2Paginator(listRequest).forEach(page -> {
                    /*
                     * SDK v2 Migration: The object list in the response is accessed via
                     * the `contents()` method, which replaces the v1 `objectSummaries()`.
                     */
                    List<String> files = page.contents().stream()
                            .map(S3Object::key)
                            // This application-specific filtering logic is preserved.
                            .filter(s -> s.startsWith(prefix))
                            .collect(Collectors.toList());

                    entries.addAll(files);
                });
            return entries;
        } catch (SdkException ase) {
            throw handleAmazonException(ase);
        }
    }

    private boolean parentDirectoryExist(String path) throws S3Exception {
        // Get the last non-slash character of the string, to find the parent directory
        String parentDirectory = getParentDirectory(path);

        // If we have no specific parent directory, we consider parent is root (and always exists)
        if (parentDirectory.isEmpty() || parentDirectory.equals(S3_FILE_PATH_DELIMITER)) {
            return true;
        }

        return pathExists(parentDirectory);
    }

    private String getParentDirectory(String path) {
        if (!path.contains(S3_FILE_PATH_DELIMITER)) {
            return "";
        }

        // Get the last non-slash character of the string, to find the parent directory
        int fromEnd = path.length() - 1;
        if (path.endsWith(S3_FILE_PATH_DELIMITER)) {
            fromEnd -= 1;
        }
        return fromEnd > 0
                ? path.substring(0, path.lastIndexOf(S3_FILE_PATH_DELIMITER, fromEnd) + 1)
                : S3_FILE_PATH_DELIMITER;
    }

    /**
     * Ensures path adheres to some rules: -Doesn't start with a leading slash
     */
    String sanitizedPath(String path) throws S3Exception {
        // Trim space from start and end
        String sanitizedPath = path.trim();

        // Path should start with file delimiter
        if (sanitizedPath.startsWith(S3_FILE_PATH_DELIMITER)) {
            // throw new S3Exception("Invalid Path. Path needs to start with '/'");
            sanitizedPath = sanitizedPath.substring(1).trim();
        }

        return sanitizedPath;
    }

    /**
     * Ensures file path adheres to some rules: -Overall Path rules from `sanitizedPath` -Throw an
     * error if it ends with a trailing slash
     */
    String sanitizedFilePath(String path) throws S3Exception {
        // Trim space from start and end
        String sanitizedPath = sanitizedPath(path);

        if (sanitizedPath.endsWith(S3_FILE_PATH_DELIMITER)) {
            sanitizedPath = sanitizedPath.substring(0, sanitizedPath.length() - 1).trim();
//      throw new S3Exception("Invalid Path. Path for file can't end with '/'");
        }

        if (sanitizedPath.isEmpty()) {
            throw new S3Exception("Invalid Path. Path cannot be empty");
        }

        return sanitizedPath;
    }

    /**
     * Ensures directory path adheres to some rules: -Overall Path rules from `sanitizedPath` -Add a
     * trailing slash if one does not exist
     */
    String sanitizedDirPath(String path) throws S3Exception {
        // Trim space from start and end
        String sanitizedPath = sanitizedPath(path);

        if (!sanitizedPath.endsWith(S3_FILE_PATH_DELIMITER)) {
            sanitizedPath += S3_FILE_PATH_DELIMITER;
        }

        // Trim file delimiter from end
        // if (sanitizedPath.length() > 1 && sanitizedPath.endsWith(S3_FILE_PATH_DELIMITER)) {
        //    sanitizedPath = sanitizedPath.substring(0, path.length() - 1);
        // }

        return sanitizedPath;
    }

    /**
     * Best effort to handle Amazon exceptions as checked exceptions. Amazon exception are all
     * subclasses of {@link RuntimeException} so some may still be uncaught and propagated.
     */
    static S3Exception handleAmazonException(SdkException ace) {

        // Check if the exception is a service-side error from AWS.
        if (ace instanceof AwsServiceException) {
            AwsServiceException ase = (AwsServiceException) ace;

            /*
             * SDK v2 Migration:
             * - Replaced `ase.awsErrorDetails().sdkHttpResponse().statusCode()` with the simpler `ase.statusCode()`.
             * - The `getErrorType()` method from v1 (which returned an enum like Client/Service) does not exist in v2.
             * The fact that we are in this block means it's a service-side error, so we can hardcode "Service"
             * to maintain the log's structure.
             */
            String errMessage =
                    String.format(
                            Locale.ROOT,
                            "An AmazonServiceException was thrown! [serviceName=%s] "
                                    + "[awsRequestId=%s] [httpStatus=%s] [s3ErrorCode=%s] [s3ErrorType=%s] [message=%s]",
                            ase.awsErrorDetails().serviceName(),
                            ase.requestId(),
                            ase.statusCode(), // Simplified accessor for status code.
                            ase.awsErrorDetails().errorCode(),
                            "Service", // Replaced getErrorType()
                            ase.awsErrorDetails().errorMessage());

            log.error(errMessage);

            if (ase.statusCode() == 404 && NOT_FOUND_CODES.contains(ase.awsErrorDetails().errorCode())) {
                return new S3NotFoundException(errMessage, ase);
            } else {
                return new S3Exception(errMessage, ase);
            }
        }

        // Handles client-side exceptions (e.g., network issues) or other SDK errors.
        return new S3Exception(ace);
    }
}
