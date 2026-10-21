package eu.xenit.solr.backup.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.RAMDirectory;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class S3BackupRepositoryIncrementalTest {

    private static final String FILE = "_0.cfs";
    private static final URI DEST = URI.create("s3:/backups/snapshot.20240103000000000/");
    private static final String DEST_FILE_PATH = "/backups/snapshot.20240103000000000/" + FILE;
    private static final String BASE_PATH = "/backups/";
    private static final String PREVIOUS_PATH = "/backups/snapshot.20240102000000000/";
    private static final String PREVIOUS_FILE_PATH = PREVIOUS_PATH + FILE;

    @Mock
    S3StorageClient client;

    S3BackupRepository repository;
    Directory directory;
    ByteArrayOutputStream upload;

    @BeforeEach
    void setup() {
        repository = new S3BackupRepository();
        repository.setClient(client);
        directory = new RAMDirectory();
        upload = new ByteArrayOutputStream();
    }

    @Test
    void incrementalIsDisabledByDefault() {
        assertFalse(new S3BackupRepositoryConfig(new NamedList<>()).isIncrementalEnabled());

        NamedList<Object> enabled = new NamedList<>();
        enabled.add(S3BackupRepositoryConfig.S3_INCREMENTAL_ENABLED, true);
        assertTrue(new S3BackupRepositoryConfig(enabled).isIncrementalEnabled());
    }

    @Test
    void disabledUploadsWithoutLookingAtPreviousSnapshots() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);

        assertArrayEquals(file, upload.toByteArray());
        verify(client, never()).listDir(anyString());
        verify(client, never()).listFileSizes(anyString());
        verify(client, never()).readRange(anyString(), anyLong(), anyLong());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void unchangedFileIsCopiedFromLatestPreviousSnapshot() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        repository.setIncrementalEnabled(true);
        // The oldest, a named, the current and a newer snapshot must all be ignored
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {
                "snapshot.20240101000000000", "snapshot.20240102000000000", "snapshot.named",
                "snapshot.20240103000000000", "snapshot.20240104000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, (long) file.length));
        when(client.readRange(PREVIOUS_FILE_PATH, file.length - 16, file.length - 1)).thenReturn(footer(file));

        repository.copyFileFrom(directory, FILE, DEST);

        verify(client).copy(PREVIOUS_FILE_PATH, DEST_FILE_PATH);
        verify(client, never()).pushStream(anyString());
    }

    @Test
    void previousSnapshotIsListedOncePerSnapshot() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        byte[] other = writeLuceneFile("_1.cfs", 200);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, (long) file.length));
        when(client.readRange(PREVIOUS_FILE_PATH, file.length - 16, file.length - 1)).thenReturn(footer(file));
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);
        repository.copyFileFrom(directory, "_1.cfs", DEST); // not in the previous snapshot

        verify(client, times(1)).listDir(BASE_PATH);
        verify(client, times(1)).listFileSizes(PREVIOUS_PATH);
        verify(client).copy(PREVIOUS_FILE_PATH, DEST_FILE_PATH);
        verify(client).pushStream("/backups/snapshot.20240103000000000/_1.cfs");
        assertArrayEquals(other, upload.toByteArray());
    }

    @Test
    void fileMissingFromPreviousSnapshotIsUploaded() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap("_9.cfs", 1000L));
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);

        assertArrayEquals(file, upload.toByteArray());
        verify(client, never()).readRange(anyString(), anyLong(), anyLong());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void fileWithDifferentLengthIsUploaded() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, file.length + 1L));
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);

        assertArrayEquals(file, upload.toByteArray());
        verify(client, never()).readRange(anyString(), anyLong(), anyLong());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void fileWithDifferentChecksumIsUploaded() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        // Same name and length, but different content
        byte[] stored = footer(createLuceneFileBytes(100, (byte) 7));
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, (long) file.length));
        when(client.readRange(PREVIOUS_FILE_PATH, file.length - 16, file.length - 1)).thenReturn(stored);
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);

        assertArrayEquals(file, upload.toByteArray());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void noPreviousSnapshotUploads() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240103000000000", "snapshot.named"});
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);

        assertArrayEquals(file, upload.toByteArray());
        verify(client, never()).listFileSizes(anyString());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void listingFailureUploadsAndIsNotRetriedForEveryFile() throws IOException {
        writeLuceneFile(FILE, 100);
        writeLuceneFile("_1.cfs", 200);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenThrow(new S3Exception("listing failed"));
        when(client.pushStream(anyString())).thenAnswer(invocation -> new ByteArrayOutputStream());

        repository.copyFileFrom(directory, FILE, DEST);
        repository.copyFileFrom(directory, "_1.cfs", DEST);

        verify(client, times(1)).listDir(BASE_PATH);
        verify(client).pushStream(DEST_FILE_PATH);
        verify(client).pushStream("/backups/snapshot.20240103000000000/_1.cfs");
    }

    @Test
    void checksumRetrievalFailureUploads() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, (long) file.length));
        when(client.readRange(PREVIOUS_FILE_PATH, file.length - 16, file.length - 1))
                .thenThrow(new S3Exception("ranged GET not supported"));
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);

        assertArrayEquals(file, upload.toByteArray());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void invalidStoredFooterUploads() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, (long) file.length));
        when(client.readRange(PREVIOUS_FILE_PATH, file.length - 16, file.length - 1)).thenReturn(new byte[16]);
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);

        assertArrayEquals(file, upload.toByteArray());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void copyFailureUploadsToTheSameKey() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, (long) file.length));
        when(client.readRange(PREVIOUS_FILE_PATH, file.length - 16, file.length - 1)).thenReturn(footer(file));
        doThrow(new S3Exception("copy failed")).when(client).copy(PREVIOUS_FILE_PATH, DEST_FILE_PATH);
        expectUpload();

        repository.copyFileFrom(directory, FILE, DEST);

        // The upload (a plain PUT/multipart upload of the whole key) replaces whatever the failed copy left behind
        verify(client).pushStream(DEST_FILE_PATH);
        assertArrayEquals(file, upload.toByteArray());
    }

    @Test
    void unexpectedStorageFailureStopsReuseForTheRestOfTheSnapshot() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        byte[] other = writeLuceneFile("_1.cfs", 200);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Map.of(FILE, (long) file.length, "_1.cfs", (long) other.length));
        when(client.readRange(PREVIOUS_FILE_PATH, file.length - 16, file.length - 1))
                .thenThrow(new S3Exception("access denied"));
        when(client.pushStream(anyString())).thenAnswer(invocation -> new ByteArrayOutputStream());

        repository.copyFileFrom(directory, FILE, DEST);
        repository.copyFileFrom(directory, "_1.cfs", DEST);

        // The second file is uploaded without a new attempt to reuse it
        verify(client, times(1)).readRange(anyString(), anyLong(), anyLong());
        verify(client, never()).copy(anyString(), anyString());
        verify(client).pushStream(DEST_FILE_PATH);
        verify(client).pushStream("/backups/snapshot.20240103000000000/_1.cfs");
    }

    @Test
    void copyFailureStopsReuseForTheRestOfTheSnapshot() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        byte[] other = writeLuceneFile("_1.cfs", 200);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Map.of(FILE, (long) file.length, "_1.cfs", (long) other.length));
        when(client.readRange(PREVIOUS_FILE_PATH, file.length - 16, file.length - 1)).thenReturn(footer(file));
        doThrow(new S3Exception("copy failed")).when(client).copy(PREVIOUS_FILE_PATH, DEST_FILE_PATH);
        when(client.pushStream(anyString())).thenAnswer(invocation -> new ByteArrayOutputStream());

        repository.copyFileFrom(directory, FILE, DEST);
        repository.copyFileFrom(directory, "_1.cfs", DEST);

        verify(client, times(1)).readRange(anyString(), anyLong(), anyLong());
        verify(client, times(1)).copy(anyString(), anyString());
        verify(client).pushStream(DEST_FILE_PATH);
        verify(client).pushStream("/backups/snapshot.20240103000000000/_1.cfs");
    }

    @Test
    void ordinaryMismatchesAndMissingFilesDoNotStopReuse() throws IOException {
        byte[] changed = writeLuceneFile("_0.cfs", 100);
        writeLuceneFile("_1.cfs", 150); // not in the previous snapshot
        byte[] missingLater = writeLuceneFile("_2.cfs", 200); // removed from the previous snapshot after listing
        byte[] unchanged = writeLuceneFile("_3.cfs", 300);
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Map.of(
                "_0.cfs", (long) changed.length, "_2.cfs", (long) missingLater.length, "_3.cfs", (long) unchanged.length));
        // Same name and length, but a different checksum
        when(client.readRange(PREVIOUS_PATH + "_0.cfs", changed.length - 16, changed.length - 1))
                .thenReturn(footer(createLuceneFileBytes(100, (byte) 7)));
        when(client.readRange(PREVIOUS_PATH + "_2.cfs", missingLater.length - 16, missingLater.length - 1))
                .thenThrow(new S3NotFoundException("no such key"));
        when(client.readRange(PREVIOUS_PATH + "_3.cfs", unchanged.length - 16, unchanged.length - 1))
                .thenReturn(footer(unchanged));
        when(client.pushStream(anyString())).thenAnswer(invocation -> new ByteArrayOutputStream());

        for (String name : new String[] {"_0.cfs", "_1.cfs", "_2.cfs", "_3.cfs"}) {
            repository.copyFileFrom(directory, name, DEST);
        }

        verify(client).copy(PREVIOUS_PATH + "_3.cfs", "/backups/snapshot.20240103000000000/_3.cfs");
        verify(client, times(1)).copy(anyString(), anyString());
        verify(client).pushStream("/backups/snapshot.20240103000000000/_0.cfs");
        verify(client).pushStream("/backups/snapshot.20240103000000000/_1.cfs");
        verify(client).pushStream("/backups/snapshot.20240103000000000/_2.cfs");
    }

    @Test
    void fileTooLargeForASingleCopyIsUploaded() throws IOException {
        byte[] file = writeLuceneFile(FILE, 100);
        // Report the local file, and the stored one, as larger than a single copy request supports
        long hugeLength = S3StorageClient.MAX_SINGLE_COPY_SIZE + 1;
        Directory huge = new FilterDirectory(directory) {
            @Override
            public long fileLength(String name) throws IOException {
                return name.equals(FILE) ? hugeLength : super.fileLength(name);
            }
        };
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, hugeLength));
        expectUpload();

        repository.copyFileFrom(huge, FILE, DEST);

        assertArrayEquals(file, upload.toByteArray());
        verify(client, never()).readRange(anyString(), anyLong(), anyLong());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void unreadableLocalFooterIsNotCopiedAndStillReportedByTheUpload() throws IOException {
        // Right length, but not a Lucene file: it has no footer
        try (IndexOutput out = directory.createOutput(FILE, IOContext.DEFAULT)) {
            out.writeBytes(new byte[100], 100);
        }
        repository.setIncrementalEnabled(true);
        when(client.listDir(BASE_PATH)).thenReturn(new String[] {"snapshot.20240102000000000"});
        when(client.listFileSizes(PREVIOUS_PATH)).thenReturn(Collections.singletonMap(FILE, 100L));
        expectUpload();

        assertThrows(CorruptIndexException.class, () -> repository.copyFileFrom(directory, FILE, DEST));

        verify(client, never()).readRange(anyString(), anyLong(), anyLong());
        verify(client, never()).copy(anyString(), anyString());
    }

    @Test
    void parseFooterChecksum() {
        byte[] file = createLuceneFileBytes(10, (byte) 1);
        byte[] footer = footer(file);
        assertEquals(Long.valueOf(checksumOf(file)), S3BackupRepository.parseFooterChecksum(footer));

        assertNull(S3BackupRepository.parseFooterChecksum(null));
        assertNull(S3BackupRepository.parseFooterChecksum(Arrays.copyOf(footer, 15)));
        byte[] badAlgorithm = footer.clone();
        badAlgorithm[7] = 1;
        assertNull(S3BackupRepository.parseFooterChecksum(badAlgorithm));
        byte[] badChecksum = footer.clone();
        badChecksum[8] = 1; // Upper 32 bits of the CRC must be zero
        assertNull(S3BackupRepository.parseFooterChecksum(badChecksum));
        byte[] badMagic = footer.clone();
        badMagic[0] ^= 1; // Flip one bit of the footer magic
        assertNull(S3BackupRepository.parseFooterChecksum(badMagic));
    }

    private void expectUpload() throws IOException {
        when(client.pushStream(anyString())).thenReturn(upload);
    }

    /** Writes a file with a Lucene header and footer to the directory and returns its content. */
    private byte[] writeLuceneFile(String name, int bodySize) throws IOException {
        byte[] content = createLuceneFileBytes(bodySize, (byte) 3);
        try (IndexOutput out = directory.createOutput(name, IOContext.DEFAULT)) {
            out.writeBytes(content, content.length);
        }
        return content;
    }

    private static byte[] createLuceneFileBytes(int bodySize, byte fill) {
        try (Directory scratch = new RAMDirectory()) {
            try (IndexOutput out = scratch.createOutput("scratch", IOContext.DEFAULT)) {
                CodecUtil.writeHeader(out, "test", 1);
                byte[] body = new byte[bodySize];
                Arrays.fill(body, fill);
                out.writeBytes(body, body.length);
                CodecUtil.writeFooter(out);
            }
            try (IndexInput in = scratch.openInput("scratch", IOContext.READONCE)) {
                byte[] content = new byte[(int) in.length()];
                in.readBytes(content, 0, content.length);
                return content;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] footer(byte[] file) {
        return Arrays.copyOfRange(file, file.length - 16, file.length);
    }

    private static long checksumOf(byte[] file) {
        try (Directory scratch = new RAMDirectory()) {
            try (IndexOutput out = scratch.createOutput("f", IOContext.DEFAULT)) {
                out.writeBytes(file, file.length);
            }
            try (IndexInput in = scratch.openInput("f", IOContext.READONCE)) {
                return CodecUtil.retrieveChecksum(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
