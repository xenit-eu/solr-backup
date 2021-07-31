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

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import eu.xenit.solr.backup.s3.S3Exception;
import eu.xenit.solr.backup.s3.S3OutputStream;
import eu.xenit.solr.backup.s3.S3StorageClient;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.solr.common.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Creates a {@link AmazonS3} for communicating with Caringo Swarm S3. Utilizes the default credential provider chain;
 * reference <a href="https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html">AWS SDK docs</a> for
 * details on where this client will fetch credentials from, and the order of precedence.
 */
class SwarmS3StorageClient extends S3StorageClient{

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());


    public SwarmS3StorageClient(String bucketName, String endpoint) {
        super(createInternalClient("eu-west-1", "",0, endpoint), bucketName);
    }


    /**
     * Get length of file in bytes.
     *
     * @param path to file in S3.
     * @return length of file.
     */
    public long length(String path) throws S3Exception {
        path = sanitizedPath(path, true);
        try {
            ObjectMetadata objectMetadata = s3Client.getObjectMetadata(bucketName, path);
            String blobDirHeaderVal = objectMetadata.getUserMetaDataOf(BLOB_DIR_HEADER);

            if (StringUtils.isEmpty(blobDirHeaderVal) || !blobDirHeaderVal.equalsIgnoreCase("true")) {
                return objectMetadata.getContentLength();
            }
            throw new S3Exception("Path is Directory");
        } catch (AmazonClientException ase) {
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
        path = sanitizedPath(path, true);

        try {
            S3Object requestedObject = s3Client.getObject(bucketName, path);
            // This InputStream instance needs to be closed by the caller
            return requestedObject.getObjectContent();
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * List all the files and sub-directories directly under given path.
     *
     * @param path Path to directory in S3.
     * @return Files and sub-directories in path.
     */

    /**
     * Open a new {@link OutputStream} to file for write. Caller needs to close the stream.
     *
     * @param path to file in S3.
     * @return OutputStream for file.
     */
    OutputStream pushStream(String path) throws S3Exception {
        path = sanitizedPath(path, true);

        try {
            return new S3OutputStream(s3Client, path, bucketName);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }



    /**
     * List all the files and sub-directories directly under given path.
     *
     * @param path Path to directory in S3.
     * @return Files and sub-directories in path.
     */
    public String[] listDir(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        String prefix = path.equals("/") ? path : path + BLOB_FILE_PATH_DELIMITER;
        ListObjectsRequest listRequest = new ListObjectsRequest()
                .withBucketName(bucketName)
                .withPrefix(prefix)
                .withDelimiter(BLOB_FILE_PATH_DELIMITER);

        List<String> entries = new ArrayList<>();
        try {
            ObjectListing objectListing = s3Client.listObjects(listRequest);

            while (true) {
                List<String> files = objectListing.getObjectSummaries().stream()
                        .map(S3ObjectSummary::getKey)
                        // This filtering is needed only for S3mock. Real S3 does not ignore the trailing '/' in the prefix.
                        .filter(s -> s.startsWith(prefix))
                        .map(s -> s.substring(prefix.length()))
                        .collect(Collectors.toList());

                entries.addAll(files);

                if (objectListing.isTruncated()) {
                    objectListing = s3Client.listNextBatchOfObjects(objectListing);
                } else {
                    break;
                }
            }
            return entries.toArray(new String[0]);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Check if path exists.
     *
     * @param path to File/Directory in S3.
     * @return true if path exists, otherwise false?
     */
    boolean pathExists(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        // for root return true
        if (path.isEmpty() || "/".equals(path)) {
            return true;
        }

        try {
            return s3Client.doesObjectExist(bucketName, path);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Ensures path adheres to some rules:
     * -If it's a file, throw an error if it ends with a trailing slash
     * -Else, silently trim the trailing slash
     */
    String sanitizedPath(String path, boolean isFile) throws S3Exception {
        // Trim space from start and end
        String sanitizedPath = path.trim();


        if (isFile && sanitizedPath.endsWith(BLOB_FILE_PATH_DELIMITER)) {
            throw new S3Exception("Invalid Path. Path for file can't end with '/'");
        }

        // Trim file delimiter from end
        if (sanitizedPath.length() > 1 && sanitizedPath.endsWith(BLOB_FILE_PATH_DELIMITER)) {
            sanitizedPath = sanitizedPath.substring(0, path.length() - 1);
        }

        return sanitizedPath;
    }

    /**
     * Override {@link Closeable} since we throw no exception.
     */
    public void close() {
        s3Client.shutdown();
    }
}
