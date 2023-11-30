package eu.xenit.solr.backup.s3;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectListing;
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
    static AmazonS3 s3Client;
    static final String BUCKET = "bucket";

    @BeforeEach
    public void setup() {
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
                .until(() -> s3Client.listObjects(BUCKET)
                        .getObjectSummaries()
                        .stream()
                        .filter(s3ObjectSummary -> s3ObjectSummary.getSize() == 0
                                && s3ObjectSummary.getKey().contains("snapshot"))
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

    private AmazonS3 createInternalClient(
            String region, String endpoint, String accessKey, String secretKey) {
        ClientConfiguration clientConfig = new ClientConfiguration().withProtocol(Protocol.HTTPS);
        AmazonS3ClientBuilder clientBuilder = AmazonS3ClientBuilder.standard().withClientConfiguration(clientConfig);
        clientBuilder.withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)));
        clientBuilder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, region));
        clientBuilder.withPathStyleAccessEnabled(true);
        return clientBuilder.build();
    }


}
