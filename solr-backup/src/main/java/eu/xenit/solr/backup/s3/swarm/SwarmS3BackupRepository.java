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

import static eu.xenit.solr.backup.s3.S3StorageClient.BLOB_FILE_PATH_DELIMITER;

import eu.xenit.solr.backup.s3.S3BackupRepository;
import eu.xenit.solr.backup.s3.S3IndexInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.solr.common.StringUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.backup.repository.BackupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A concrete implementation of {@link BackupRepository} interface supporting backup/restore of Solr indexes to Caringo Swarm S3.
 */
public class SwarmS3BackupRepository extends S3BackupRepository {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static final String SWARM_SCHEME = "swarm";

    private NamedList<?> config;
    private SwarmS3StorageClient client;

    @Override
    public void init(NamedList args) {
        this.config = args;
        SwarmS3BackupRepositoryConfig backupConfig = new SwarmS3BackupRepositoryConfig(this.config);

        // If a client was already created, close it to avoid any resource leak
        if (client != null) {
            client.close();
        }

        this.client = backupConfig.buildClient();

    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfigProperty(String name) {
        return (T) this.config.get(name);
    }

    @Override
    public URI resolve(URI baseUri, String... pathComponents) {
        if (!SWARM_SCHEME.equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("URI must being with 'swarm:' scheme");
        }

        String path = baseUri + "/" + String.join("/", pathComponents);
        if (pathComponents.length == 1 && pathComponents[0].contains("/"))
            path = SWARM_SCHEME + ":///" + pathComponents[0];
        return URI.create(path).normalize();
    }

    @Override
    public void createDirectory(URI path) throws IOException {
    }

    @Override
    public void deleteDirectory(URI path) throws IOException {
        Objects.requireNonNull(path, "cannot delete directory with a null URI");

        String blobPath = getS3Path(path);

        if (log.isDebugEnabled()) {
            log.debug("Delete directory '{}'", blobPath);
        }

        client.deleteDirectory(blobPath);
    }

    @Override
    public boolean exists(URI path) throws IOException {
        Objects.requireNonNull(path, "cannot test for existence of a null URI path");

        String blobPath = getS3Path(path);

        if (log.isDebugEnabled()) {
            log.debug("Path exists '{}'", blobPath);
        }

        return client.pathExists(blobPath);
    }

    @Override
    public IndexInput openInput(URI path, String fileName, IOContext ctx) throws IOException {
        Objects.requireNonNull(path, "cannot open a input stream without a valid URI path");
        if (StringUtils.isEmpty(fileName)) {
            throw new IllegalArgumentException("need a valid file name to read from S3");
        }

        URI filePath = resolve(path, fileName);
        String blobPath = getS3Path(filePath);

        if (log.isDebugEnabled()) {
            log.debug("Read from S3 '{}'", blobPath);
        }

        return new S3IndexInput(client.pullStream(blobPath), blobPath, client.length(blobPath));
    }

    @Override
    public OutputStream createOutput(URI path) throws IOException {
        Objects.requireNonNull(path, "cannot write to S3 without a valid URI path");

        String blobPath = getS3Path(path);

        if (log.isDebugEnabled()) {
            log.debug("Write to S3 '{}'", blobPath);
        }

        return client.pushStream(blobPath);
    }

    /**
     * This method returns files starting with specified prefix(path)
     * If the path is empty or the root folder, it returns virtual (sub)folders detected as the first part of the path of files starting with that prefix.
     *
     * @param path The directory path
     * @return an array of strings, one for each entry in the directory
     */
    @Override
    public String[] listAll(URI path) throws IOException {
        String blobPath = getS3Path(path);

        if (log.isDebugEnabled()) {
            log.debug("listAll for '{}'", blobPath);
        }

        return client.listDir(blobPath);
    }

    @Override
    public PathType getPathType(URI path) throws IOException {
        String blobPath = getS3Path(path);

        if (log.isDebugEnabled()) {
            log.debug("getPathType for '{}'", blobPath);
        }

        if (!blobPath.contains(BLOB_FILE_PATH_DELIMITER))
            return PathType.DIRECTORY;

        return PathType.FILE;
    }

    /**
     * Copy an index file from specified <code>sourceDir</code> to the destination repository (i.e. backup).
     *
     * @param sourceDir The source directory hosting the file to be copied.
     * @param sourceFileName The name of the file to be copied
     * @param dest The destination backup location.
     * @throws IOException in case of errors
     * @throws CorruptIndexException in case checksum of the file does not match with precomputed checksum stored at the
     * end of the file
     */
    public void copyIndexFileFrom(Directory sourceDir, String sourceFileName, URI dest, String destFileName)
            throws IOException {
        if (StringUtils.isEmpty(sourceFileName)) {
            throw new IllegalArgumentException("must have a valid source file name to copy");
        }
        if (StringUtils.isEmpty(destFileName)) {
            throw new IllegalArgumentException("must have a valid destination file name to copy");
        }

        URI filePath = resolve(dest, destFileName);
        String blobPath = getS3Path(filePath);
        Instant start = Instant.now();
        if (log.isDebugEnabled()) {
            log.debug("Upload started to S3 '{}'", blobPath);
        }

        try (IndexInput indexInput = sourceDir.openInput(sourceFileName, IOContext.DEFAULT)) {
            try (OutputStream outputStream = client.pushStream(blobPath)) {

                byte[] buffer = new byte[CHUNK_SIZE];
                int bufferLen;
                long remaining = indexInput.length();

                while (remaining > 0) {
                    bufferLen = remaining >= CHUNK_SIZE ? CHUNK_SIZE : (int) remaining;

                    indexInput.readBytes(buffer, 0, bufferLen);
                    outputStream.write(buffer, 0, bufferLen);
                    remaining -= bufferLen;
                }
                outputStream.flush();
            }
        }

        long timeElapsed = Duration.between(start, Instant.now()).toMillis();
        if (log.isInfoEnabled()) {
            log.info("Upload to S3: '{}' finished in {}ms", blobPath, timeElapsed);
        }
    }

    /**
     * Copy an index file from specified <code>sourceRepo</code> to the destination directory (i.e. restore).
     *
     * @param sourceDir The source URI hosting the file to be copied.
     * @param dest The destination where the file should be copied.
     * @throws IOException in case of errors.
     */
    public void copyIndexFileTo(URI sourceDir, String sourceFileName, Directory dest, String destFileName)
            throws IOException {
        if (StringUtils.isEmpty(sourceFileName)) {
            throw new IllegalArgumentException("must have a valid source file name to copy");
        }
        if (StringUtils.isEmpty(destFileName)) {
            throw new IllegalArgumentException("must have a valid destination file name to copy");
        }

        URI filePath = resolve(sourceDir, sourceFileName);
        String blobPath = getS3Path(filePath);
        Instant start = Instant.now();
        if (log.isDebugEnabled()) {
            log.debug("Download started from S3 '{}'", blobPath);
        }

        try (InputStream inputStream = client.pullStream(blobPath);
                IndexOutput indexOutput = dest.createOutput(destFileName, IOContext.DEFAULT)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                indexOutput.writeBytes(buffer, 0, len);
            }
        }

        long timeElapsed = Duration.between(start, Instant.now()).toMillis();

        if (log.isInfoEnabled()) {
            log.info("Download from S3 '{}' finished in {}ms", blobPath, timeElapsed);
        }
    }

    @Override
    public void copyFileTo(URI sourceRepo, String fileName, Directory dest) throws IOException {
        try {
            copyIndexFileTo(new URI("swarm:///"), fileName, dest, fileName.substring(fileName.indexOf("/") + 1));
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void close() {
        client.close();
    }

    /**
     * Return the path to use in underlying blob store.
     */
    private static String getS3Path(URI uri) {
        // Depending on the scheme, the first element may be the host. Following ones are the path
        String host = uri.getHost();
        return host == null ? uri.getPath().substring(1) : host + uri.getPath().substring(1);
    }
}
