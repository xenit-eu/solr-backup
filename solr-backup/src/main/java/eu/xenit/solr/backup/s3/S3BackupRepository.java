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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.StringUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.DirectoryFactory;
import org.apache.solr.core.backup.repository.BackupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A concrete implementation of {@link BackupRepository} interface supporting backup/restore of Solr
 * indexes to S3.
 */
public class S3BackupRepository implements BackupRepository {

  private static final Logger log = LoggerFactory.getLogger(S3BackupRepository.class);

  private static final int CHUNK_SIZE = 16 * 1024 * 1024; // 16 MBs
  static final String S3_SCHEME = "s3";

  private NamedList<?> config;

  public void setClient(S3StorageClient client) {
    this.client = client;
  }

  private S3StorageClient client;

  @Override
  public void init(NamedList args) {
    this.config = args;
    S3BackupRepositoryConfig backupConfig = new S3BackupRepositoryConfig(this.config);

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
    public URI createURI(String location) {
        if (StringUtils.isEmpty(location)) {
            throw new IllegalArgumentException("cannot create URI with an empty location");
        }

        try {
          URI result = getUri(location);
          createBackUpDirectory(result);
            return result;
        } catch (URISyntaxException ex) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex);
        }
    }

  private URI getUri(String location) throws URISyntaxException {
    StringBuilder locationResult = new StringBuilder();
    if (!location.startsWith("/") && !location.startsWith(S3_SCHEME + ":")) {
      locationResult.append("/");
    }
    locationResult.append(location);
    if (!location.endsWith("/")) {
      locationResult.append("/");
    }
    if (location.startsWith(S3_SCHEME + ":")) {
      return new URI(locationResult.toString());
    }
    return new URI(S3_SCHEME, null, locationResult.toString(), null);
  }

  private void createBackUpDirectory(URI result) {
    try {
      if(!exists(result)){
        createDirectory(result);
      }
    } catch (IOException e) {
      log.error(e.getMessage(),e);
    }
  }

  public URI createDirectoryURI(String location) {
    if (StringUtils.isEmpty(location)) {
      throw new IllegalArgumentException("cannot create URI with an empty location");
    }

    if (!location.endsWith("/")) {
      location += "/";
    }

    return createURI(location);
  }

  @Override
  public URI resolve(URI baseUri, String... pathComponents) {
    if (!S3_SCHEME.equalsIgnoreCase(baseUri.getScheme())) {
      throw new IllegalArgumentException("URI must begin with 's3:' scheme");
    }

    // If paths contains unnecessary '/' separators, they'll be removed by URI.normalize()
    String path = baseUri + "/" + String.join("/", pathComponents);
    return URI.create(path).normalize();
  }

  public URI resolveDirectory(URI baseUri, String... pathComponents) {
    if (pathComponents.length > 0) {
      pathComponents[pathComponents.length - 1] = pathComponents[pathComponents.length - 1] + "/";
    } else {
      if (!baseUri.getPath().endsWith("/")) {
        baseUri = URI.create(baseUri + "/");
      }
    }
    return resolve(baseUri, pathComponents);
  }

  @Override
  public void createDirectory(URI path) throws IOException {
    Objects.requireNonNull(path, "cannot create directory to a null URI");

    String s3Path = getS3Path(path);

    if (log.isDebugEnabled()) {
      log.debug("Create directory '{}'", s3Path);
    }

    client.createDirectory(s3Path);
  }

  @Override
  public void deleteDirectory(URI path) throws IOException {
    Objects.requireNonNull(path, "cannot delete directory with a null URI");

    String s3Path = getS3Path(path);

    if (log.isDebugEnabled()) {
      log.debug("Delete directory '{}'", s3Path);
    }

    client.deleteDirectory(s3Path);
  }

  public void delete(URI path, Collection<String> files, boolean ignoreNoSuchFileException)
      throws IOException {
    Objects.requireNonNull(path, "cannot delete files without a valid URI path");
    Objects.requireNonNull(files, "collection of files to delete cannot be null");

    if (log.isDebugEnabled()) {
      log.debug("Delete files {} from {}", files, getS3Path(path));
    }

    Set<String> filesToDelete =
        files.stream()
            .map(file -> resolve(path, file))
            .map(S3BackupRepository::getS3Path)
            .collect(Collectors.toSet());

    try {
      client.delete(filesToDelete);
    } catch (S3NotFoundException e) {
      if (!ignoreNoSuchFileException) {
        throw e;
      }
    }
  }

  @Override
  public boolean exists(URI path) throws IOException {
    Objects.requireNonNull(path, "cannot test for existence of a null URI path");

    String s3Path = getS3Path(path);

    if (log.isDebugEnabled()) {
      log.debug("Path exists '{}'", s3Path);
    }

    return client.pathExists(s3Path);
  }

  @Override
  public IndexInput openInput(URI path, String fileName, IOContext ctx) throws IOException {
    Objects.requireNonNull(path, "cannot open a input stream without a valid URI path");
    if (StringUtils.isEmpty(fileName)) {
      throw new IllegalArgumentException("need a valid file name to read from S3");
    }

    URI filePath = resolve(path, fileName);
    String s3Path = getS3Path(filePath);

    if (log.isDebugEnabled()) {
      log.debug("Read from S3 '{}'", s3Path);
    }

    return new S3IndexInput(client.pullStream(s3Path), s3Path, client.length(s3Path));
  }

  @Override
  public OutputStream createOutput(URI path) throws IOException {
    Objects.requireNonNull(path, "cannot write to S3 without a valid URI path");

    String s3Path = getS3Path(path);

    if (log.isDebugEnabled()) {
      log.debug("Write to S3 '{}'", s3Path);
    }

    return client.pushStream(s3Path);
  }

  /**
   * This method returns all the entries (files and directories) in the specified directory.
   *
   * @param path The directory path
   * @return an array of strings, one for each entry in the directory
   */
  @Override
  public String[] listAll(URI path) throws IOException {
    String s3Path = getS3Path(path);

    if (log.isDebugEnabled()) {
      log.debug("listAll for '{}'", s3Path);
    }

    return client.listDir(s3Path);
  }

  @Override
  public PathType getPathType(URI path) throws IOException {
    String s3Path = getS3Path(path);

    if (log.isDebugEnabled()) {
      log.debug("getPathType for '{}'", s3Path);
    }

    return client.isDirectory(s3Path) ? PathType.DIRECTORY : PathType.FILE;
  }

  @Override
    public void copyFileFrom(Directory sourceDir, String fileName, URI dest) throws IOException {
        copyIndexFileFrom(sourceDir,fileName,dest,fileName);
  }

  /**
   * Copy an index file from specified <code>sourceDir</code> to the destination repository (i.e.
   * backup).
   *
   * @param sourceDir The source directory hosting the file to be copied.
   * @param sourceFileName The name of the file to be copied.
   * @param destFileName The name of the file to  copy to.
   * @param dest The destination backup location.
   * @throws IOException in case of errors
   * @throws CorruptIndexException in case checksum of the file does not match with precomputed
   *     checksum stored at the end of the file
   */
  public void copyIndexFileFrom(
      Directory sourceDir, String sourceFileName, URI dest, String destFileName)
      throws IOException {
    if (StringUtils.isEmpty(sourceFileName)) {
      throw new IllegalArgumentException("must have a valid source file name to copy");
    }
    if (StringUtils.isEmpty(destFileName)) {
      throw new IllegalArgumentException("must have a valid destination file name to copy");
    }

    URI filePath = resolve(dest, destFileName);
    String s3Path = getS3Path(filePath);
    Instant start = Instant.now();
    if (log.isDebugEnabled()) {
      log.debug("Upload started to S3 '{}'", s3Path);
    }

    try (ChecksumIndexInput indexInput =
        sourceDir.openChecksumInput(sourceFileName, DirectoryFactory.IOCONTEXT_NO_CACHE)) {
      if (indexInput.length() <= CodecUtil.footerLength()) {
        throw new CorruptIndexException("file is too small:" + indexInput.length(), indexInput);
      }

      client.createDirectory(getS3Path(dest));
      try (OutputStream outputStream = client.pushStream(s3Path)) {

        byte[] buffer = new byte[CHUNK_SIZE];
        int bufferLen;
        long remaining = indexInput.length() - CodecUtil.footerLength();

        while (remaining > 0) {
          bufferLen = remaining >= CHUNK_SIZE ? CHUNK_SIZE : (int) remaining;

          indexInput.readBytes(buffer, 0, bufferLen);
          outputStream.write(buffer, 0, bufferLen);
          remaining -= bufferLen;
        }
        final long checksum = CodecUtil.checkFooter(indexInput);
        writeFooter(checksum, outputStream);
      }
    }

    long timeElapsed = Duration.between(start, Instant.now()).toMillis();
    if (log.isInfoEnabled()) {
      log.info("Upload to S3: '{}' finished in {}ms", s3Path, timeElapsed);
    }
  }

  @Override
    public void copyFileTo(URI sourceRepo, String fileName, Directory dest) throws IOException {
        copyIndexFileTo(sourceRepo,fileName,dest,fileName);
    }

  /**
   * Copy an index file from specified <code>sourceRepo</code> to the destination directory (i.e.
   * restore).
   *
   * @param sourceDir The source URI hosting the file to be copied.
   * @param dest The destination where the file should be copied.
   * @param sourceFileName The name of the file to be copied
   * @param destFileName The name of the file to  copy to.
   * @throws IOException in case of errors.
   */
  public void copyIndexFileTo(
      URI sourceDir, String sourceFileName, Directory dest, String destFileName)
      throws IOException {
    if (StringUtils.isEmpty(sourceFileName)) {
      throw new IllegalArgumentException("must have a valid source file name to copy");
    }
    if (StringUtils.isEmpty(destFileName)) {
      throw new IllegalArgumentException("must have a valid destination file name to copy");
    }

    URI filePath = resolve(sourceDir, sourceFileName);
    String s3Path = getS3Path(filePath);
    Instant start = Instant.now();
    if (log.isDebugEnabled()) {
      log.debug("Download started from S3 '{}'", s3Path);
    }

    try (InputStream inputStream = client.pullStream(s3Path);
        IndexOutput indexOutput = dest.createOutput(destFileName, DirectoryFactory.IOCONTEXT_NO_CACHE)) {
      byte[] buffer = new byte[CHUNK_SIZE];
      int len;
      while ((len = inputStream.read(buffer)) != -1) {
        indexOutput.writeBytes(buffer, 0, len);
      }
    }

    long timeElapsed = Duration.between(start, Instant.now()).toMillis();

    if (log.isInfoEnabled()) {
      log.info("Download from S3 '{}' finished in {}ms", s3Path, timeElapsed);
    }
  }

  @Override
  public void close() {
    client.close();
  }

  /** Return the path to use in S3. */
  private static String getS3Path(URI uri) {
    // Depending on the scheme, the first element may be the host. Following ones are the path
    String host = uri.getHost();
    return host == null ? uri.getPath() : host + uri.getPath();
  }

  private void writeFooter(long checksum, OutputStream outputStream) throws IOException {
    IndexOutput out =
        new IndexOutput("", "") {
          @Override
          public void writeByte(byte b) throws IOException {
            outputStream.write(b);
          }

          @Override
          public void writeBytes(byte[] b, int offset, int length) throws IOException {
            outputStream.write(b, offset, length);
          }

          @Override
          public void close() {}

          @Override
          public long getFilePointer() {
            return 0;
          }

          @Override
          public long getChecksum() {
            return checksum;
          }
        };
    CodecUtil.writeFooter(out);
  }
}
