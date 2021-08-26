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
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import eu.xenit.solr.backup.s3.S3Exception;
import eu.xenit.solr.backup.s3.S3OutputStream;
import eu.xenit.solr.backup.s3.S3StorageClient;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Creates a client for communicating with Caringo Swarm S3.
 * Caringo Swarm does not have a hierarchical structure, therefore all methods involving parent-child relationships have been overriden
 */
class SwarmS3StorageClient extends S3StorageClient{

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public SwarmS3StorageClient(String bucketName, String endpoint) {
        super(createInternalClient("eu-west-1", "",0, endpoint), bucketName);
    }

    /**
     * Open a new {@link OutputStream} to file for write. Caller needs to close the stream.
     *
     * @param path to file in Swarm.
     * @return OutputStream for file.
     */
    @Override
    protected OutputStream pushStream(String path) throws S3Exception {
        path = sanitizedPath(path, true);

        try {
            return new S3OutputStream(s3Client, path, bucketName);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    public void deleteDirectory(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        List<String> entries = new ArrayList<>();

        // Get all the files
        entries.addAll(listAll(path));

        super.deleteObjects(entries);
    }

    private List<String> listAll(String path) throws S3Exception {
        String prefix = path + BLOB_FILE_PATH_DELIMITER;
        ListObjectsRequest listRequest = new ListObjectsRequest()
                .withBucketName(bucketName)
                .withPrefix(prefix);

        List<String> entries = new ArrayList<>();
        try {
            ObjectListing objectListing = s3Client.listObjects(listRequest);

            while (true) {
                List<String> files = objectListing.getObjectSummaries().stream()
                        .map(S3ObjectSummary::getKey)
                        .filter(s -> s.startsWith(prefix))
                        .collect(Collectors.toList());

                entries.addAll(files);

                if (objectListing.isTruncated()) {
                    objectListing = s3Client.listNextBatchOfObjects(objectListing);
                } else {
                    break;
                }
            }
            return entries;
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    @Override
    protected String[] listDir(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        ListObjectsRequest listRequest = new ListObjectsRequest()
                .withBucketName(bucketName);

        List<String> entries = new ArrayList<>();
        try {
            ObjectListing objectListing = s3Client.listObjects(listRequest);

            while (true) {
                List<String> files = objectListing.getObjectSummaries().stream()
                        .map(S3ObjectSummary::getKey)
                        .collect(Collectors.toList());

                for(String file : files) {
                    // ignore file with successful snapshots
                    if(!file.contains("/"))
                        continue;
                    String dir = file.substring(0,file.indexOf("/"));
                    if(!dir.isEmpty() && !entries.contains(dir)) {
                        entries.add(dir);
                    }
                }

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


    protected String[] listFiles(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        String prefix = path.equals("/") ? path : path + BLOB_FILE_PATH_DELIMITER;
        ListObjectsRequest listRequest = new ListObjectsRequest()
                .withBucketName(bucketName)
                .withPrefix(prefix);

        List<String> entries = new ArrayList<>();
        try {
            ObjectListing objectListing = s3Client.listObjects(listRequest);

            while (true) {
                List<String> files = objectListing.getObjectSummaries().stream()
                        .map(S3ObjectSummary::getKey)
                        .filter(s -> s.startsWith(prefix))
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
     * Ensures path adheres to some rules:
     * -If it's a file, throw an error if it ends with a trailing slash
     * -Else, silently trim the trailing slash
     */
    @Override
    protected String sanitizedPath(String path, boolean isFile) throws S3Exception {
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
}
