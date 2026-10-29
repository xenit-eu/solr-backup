package eu.xenit.solr.backup.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * Tests the operations used to reuse files of a previous snapshot, with a mocked {@link S3Client}.
 */
@ExtendWith(MockitoExtension.class)
class S3StorageClientTest {

    private static final String BUCKET = "test-bucket";

    @Mock
    S3Client s3Client;

    S3StorageClient client;

    @BeforeEach
    void setup() {
        NamedList<Object> config = new NamedList<>();
        config.add(S3BackupRepositoryConfig.S3_BUCKET_NAME, BUCKET);
        client = new S3StorageClient(s3Client, new S3BackupRepositoryConfig(config));
    }

    @Test
    void readRangeRequestsTheRangeOfTheObject() throws Exception {
        byte[] footer = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(new ByteArrayInputStream(footer))));

        byte[] result = client.readRange("/snapshot.1/_0.cfs", 84, 99);

        assertArrayEquals(footer, result);
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(request.capture());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals("snapshot.1/_0.cfs", request.getValue().key());
        assertEquals("bytes=84-99", request.getValue().range());
    }

    @Test
    void readRangeOfMissingObjectIsNotFound() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(serviceException(NoSuchKeyException.builder(), 404, "NoSuchKey"));

        assertThrows(S3NotFoundException.class, () -> client.readRange("snapshot.1/_0.cfs", 0, 15));
    }

    @Test
    void readRangeFailureIsAnS3Exception() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(serviceException(AwsServiceException.builder(), 403, "AccessDenied"));

        S3Exception e = assertThrows(S3Exception.class, () -> client.readRange("snapshot.1/_0.cfs", 0, 15));
        assertFalse(e instanceof S3NotFoundException);
    }

    @Test
    void copyCopiesWithinTheBucket() throws Exception {
        client.copy("/snapshot.1/_0.cfs", "/snapshot.2/_0.cfs");

        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(request.capture());
        assertEquals(BUCKET, request.getValue().sourceBucket());
        assertEquals("snapshot.1/_0.cfs", request.getValue().sourceKey());
        assertEquals(BUCKET, request.getValue().destinationBucket());
        assertEquals("snapshot.2/_0.cfs", request.getValue().destinationKey());
    }

    @Test
    void objectsUpToFiveGigabytesCanBeCopied() {
        long fiveGigabytes = 5L * 1024 * 1024 * 1024;
        assertTrue(S3StorageClient.canCopyObject(0));
        assertTrue(S3StorageClient.canCopyObject(fiveGigabytes));
        assertFalse(S3StorageClient.canCopyObject(fiveGigabytes + 1));
    }

    @Test
    void copyOfMissingObjectIsNotFound() {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow(serviceException(NoSuchKeyException.builder(), 404, "NoSuchKey"));

        assertThrows(S3NotFoundException.class, () -> client.copy("snapshot.1/_0.cfs", "snapshot.2/_0.cfs"));
    }

    @Test
    void copyFailureIsAnS3Exception() {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow(serviceException(AwsServiceException.builder(), 501, "NotImplemented"));

        S3Exception e = assertThrows(S3Exception.class, () -> client.copy("snapshot.1/_0.cfs", "snapshot.2/_0.cfs"));
        assertFalse(e instanceof S3NotFoundException);
    }

    @Test
    void listFileSizesReturnsTheFilesDirectlyUnderThePath() throws Exception {
        ListObjectsV2Response firstPage = ListObjectsV2Response.builder()
                .isTruncated(true)
                .nextContinuationToken("token")
                .contents(
                        S3Object.builder().key("snapshot.1/").size(0L).build(), // directory marker
                        S3Object.builder().key("snapshot.1/_0.cfs").size(100L).build())
                .build();
        ListObjectsV2Response lastPage = ListObjectsV2Response.builder()
                .isTruncated(false)
                .contents(
                        S3Object.builder().key("snapshot.1/_1.cfs").size(200L).build(),
                        S3Object.builder().key("snapshot.1/nested/_2.cfs").size(300L).build(), // not directly under the path
                        S3Object.builder().key("other/_3.cfs").size(400L).build()) // outside of the path
                .build();
        when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> new ListObjectsV2Iterable(s3Client, invocation.getArgument(0)));
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(firstPage, lastPage);

        Map<String, Long> files = client.listFileSizes("/snapshot.1/");

        assertEquals(Map.of("_0.cfs", 100L, "_1.cfs", 200L), files);
        ArgumentCaptor<ListObjectsV2Request> requests = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, org.mockito.Mockito.times(2)).listObjectsV2(requests.capture());
        List<ListObjectsV2Request> sent = requests.getAllValues();
        assertEquals(BUCKET, sent.get(0).bucket());
        assertEquals("snapshot.1/", sent.get(0).prefix());
        assertEquals("/", sent.get(0).delimiter());
        assertEquals("token", sent.get(1).continuationToken());
    }

    private static AwsServiceException serviceException(AwsServiceException.Builder builder, int status, String errorCode) {
        return builder
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .serviceName("S3")
                        .errorCode(errorCode)
                        .errorMessage(errorCode)
                        .build())
                .build();
    }
}
