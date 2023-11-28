package eu.xenit.solr.backup.s3;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class S3BackupRepositoryTest {
    S3BackupRepository s3BackupRepository;
    @Mock
    S3StorageClient client;

    @BeforeEach
    public void setup() {
        s3BackupRepository = new S3BackupRepository();
        s3BackupRepository.setClient(client);
    }

    @ParameterizedTest
    @CsvSource({
            "example/path, /example/path/",
            "/another/path/, /another/path/",
            "s3://bucket/object-key, /object-key/"
    })
    void testCreateURIWithDifferentPaths(String location, String expectedPath) throws S3Exception {
        Mockito.when(client.pathExists(Mockito.any(String.class))).thenReturn(true);

        URI result = s3BackupRepository.createURI(location);

        assertNotNull(result);
        assertEquals("s3", result.getScheme());
        assertEquals(expectedPath, result.getPath());
    }


    @Test
    void testCreateURIWithEmptyLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> s3BackupRepository.createURI(""));

    }
}