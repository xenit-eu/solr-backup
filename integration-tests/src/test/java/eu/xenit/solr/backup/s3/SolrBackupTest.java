package eu.xenit.solr.backup.s3;

import groovy.util.logging.Slf4j;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static java.lang.Thread.sleep;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SolrBackupTest {
    private static final Log log = LogFactory.getLog(SolrBackupTest.class);
    static RequestSpecification spec;
    static RequestSpecification specBackup;
    static RequestSpecification specBackupDetails;
    static RequestSpecification specRestore;
    static RequestSpecification specRestoreStatus;
    static S3Client s3Client;
    static final String BUCKET = "bucket";

    @BeforeEach
    public void setup() throws URISyntaxException {
        String basePathSolr = "solr/alfresco";
        String basePathSolrBackup = "solr/alfresco/replication";
        String solrHost = System.getProperty("solr.host", "localhost");
        s3Client = createInternalClient("us-east-1",
                "http://localhost:4566",
                "access_key",
                "secret_key");
        int solrPort = 0;
        try {
            solrPort = Integer.parseInt(System.getProperty("solr.tcp.8080", "8080"));
        } catch (NumberFormatException e) {
            System.out.println("Solr port 8080 is not exposed, probably ssl is enabled");
        }

        System.out.println("basePathSolr=" + basePathSolr + " and basePathSolrBackup=" + basePathSolrBackup + " and solrHost=" + solrHost + " and solrPort=" + solrPort);
        String baseURISolr = "http://" + solrHost;

        RestAssured.defaultParser = Parser.JSON;

        spec = new RequestSpecBuilder()
                .setBaseUri(baseURISolr)
                .setPort(solrPort)
                .setBasePath(basePathSolr)
                .build();
        specBackup = new RequestSpecBuilder()
                .setBaseUri(baseURISolr)
                .setPort(solrPort)
                .setBasePath(basePathSolrBackup)
                .addParam("command", "backup")
                .addParam("repository", "s3")
                .addParam("numberToKeep", "2")
                .addParam("wt", "json")
                .build();
        specBackupDetails = new RequestSpecBuilder()
                .setBaseUri(baseURISolr)
                .setPort(solrPort)
                .setBasePath(basePathSolrBackup)
                .addParam("command", "details")
                .addParam("wt", "json")
                .build();
        specRestore = new RequestSpecBuilder()
                .setBaseUri(baseURISolr)
                .setPort(solrPort)
                .setBasePath(basePathSolrBackup)
                .addParam("command", "restore")
                .addParam("repository", "s3")
                .build();
        specRestoreStatus = new RequestSpecBuilder()
                .setBaseUri(baseURISolr)
                .setPort(solrPort)
                .setBasePath(basePathSolrBackup)
                .addParam("command", "restorestatus")
                .addParam("wt", "json")
                .build();


        // wait for solr to track
        try {
            sleep(30000);
        } catch (InterruptedException e) {
            log.error(e);
        }
    }


    @Test
    @Order(2)
    void testRestoreEndpoint() {
        given()
                .spec(specRestore)
                .when()
                .get()
                .then()
                .statusCode(200);
        System.out.println("Restore triggered, will wait maximum 3 minutes");
        long startTime = System.currentTimeMillis();
        await().atMost(180, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS).until(() -> {
                    String status = given()
                            .spec(specRestoreStatus)
                            .when()
                            .get()
                            .then()
                            .statusCode(200)
                            .extract()
                            .path("restorestatus.status");
                    System.out.println("elapsed = " + (System.currentTimeMillis() - startTime) + "with status= " + status);
                    return "success".equals(status);
                });
    }

    @Test
    @Order(1)
    void testBackupWithNumberToLiveEndpoint() {
        validateSnapshotCount(0);
        callBackupEndpoint(1);
        validateSnapshotCount(1);
        callBackupEndpoint(2);
        validateSnapshotCount(2);
        callBackupEndpoint(3);
        validateSnapshotCount(2);
    }


    void validateSnapshotCount(long count) {
        await().atMost(180, TimeUnit.SECONDS)
                /*
                 * SDK v2 Migration:
                 * - Switched to `ListObjectsV2Request` for the S3 call.
                 * - The response object's method to get the list of objects is now `contents()`, not `objectSummaries()`.
                 * - The object class is `S3Object`, which has the same `size()` and `key()` methods.
                 */
                .until(() -> s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET)
                                .build())
                        .contents()
                        .stream()
                        .filter(s3Object -> s3Object.size() == 0
                                && s3Object.key().contains("snapshot"))
                        .count() == count);

    }

    private void callBackupEndpoint() {
        callBackupEndpoint(0);
    }
    private void callBackupEndpoint(int count) {
        String status = given()
                .spec(specBackup)
                .when()
                .get()
                .then()
                .statusCode(200)
                .extract()
                .path("status");
        assertEquals("OK", status);
        System.out.println("Backup triggered" + (count == 0 ? "" : count + " time ") + ", will wait maximum 9 minutes");
        long startTime = System.currentTimeMillis();
        await().atMost(540, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    Object backup = given()
                            .spec(specBackupDetails)
                            .when()
                            .get()
                            .then()
                            .statusCode(200)
                            .extract()
                            .path("details.backup");
                    System.out.println("elapsed = " + (System.currentTimeMillis() - startTime));
                    return backup != null;
                });
    }

    private S3Client createInternalClient(
            String region, String endpoint, String accessKey, String secretKey) throws URISyntaxException {
        // SDK v2 Migration: Removed explicit protocol setting, as it's inferred from the endpoint URI.
        ClientOverrideConfiguration clientConfig = ClientOverrideConfiguration.builder().build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .httpClientBuilder(ApacheHttpClient.builder()).overrideConfiguration(clientConfig);
        clientBuilder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));

        /*
         * SDK v2 Migration:
         * - Replaced the v1 `setEndpointConfiguration` with `endpointOverride` and `region`.
         * - `endpointOverride` takes a URI object.
         * - `region` must be set separately.
         */
        clientBuilder.endpointOverride(new URI(endpoint));
        clientBuilder.region(Region.of(region));

        // SDK v2 Migration: Replaced `pathStyleAccessEnabled(true)` with `forcePathStyle(true)`.
        clientBuilder.forcePathStyle(true);
        return clientBuilder.build();
    }


}
