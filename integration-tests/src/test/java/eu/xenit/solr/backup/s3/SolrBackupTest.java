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
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static java.lang.Thread.sleep;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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


    /**
     * Requires {@code S3_INCREMENTAL_ENABLED=true} for the Solr container (see
     * {@code integration-tests/src/test/resources/compose/docker-compose.yml}).
     *
     * <p>Creates two backups of an index with overlapping files and verifies that the second
     * snapshot can be restored. Exercises the incremental backup workflow through Solr.
     */
    @Test
    @Order(3)
    void testIncrementalBackupCreatesRestorableSnapshot() {
        backupAndWaitForNewSnapshot();
        long totalDocs = numberOfDocuments();
        List<String> ids = documentIds(2);
        assertTrue(totalDocs > 2, "Expected the index to contain documents, but found " + totalDocs);

        deleteDocumentAndCommit(ids.get(0));
        assertEquals(totalDocs - 1, numberOfDocuments());
        String secondSnapshot = backupAndWaitForNewSnapshot();

        Map<String, Map<String, S3Object>> snapshots = snapshotFiles();
        String[] names = snapshots.keySet().toArray(new String[0]);
        assertEquals(secondSnapshot, names[names.length - 1]);
        Map<String, S3Object> first = snapshots.get(names[names.length - 2]);
        Map<String, S3Object> second = snapshots.get(secondSnapshot);

        // Files are only unchanged if their content is the same too, which is only fetched for files of equal size
        List<String> unchanged = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, S3Object> file : second.entrySet()) {
            S3Object previous = first.get(file.getKey());
            boolean same = previous != null
                    && previous.size().equals(file.getValue().size())
                    && Arrays.equals(content(previous), content(file.getValue()));
            (same ? unchanged : changed).add(file.getKey());
        }
        System.out.println("Second snapshot " + secondSnapshot + ": " + unchanged.size() + " files unchanged, "
                + changed.size() + " new or changed");

        // Change the index again, so that restoring the second snapshot visibly goes back to its state
        deleteDocumentAndCommit(ids.get(1));
        assertEquals(totalDocs - 2, numberOfDocuments());

        given().spec(specRestore).when().get().then().statusCode(200);
        await().atMost(180, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    Map<String, Object> restore = given().spec(specRestoreStatus).when().get().then()
                            .statusCode(200).extract().path("restorestatus");
                    return secondSnapshot.equals(restore.get("snapshotName")) && "success".equals(restore.get("status"));
                });
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> numberOfDocuments() == totalDocs - 1);

        // Checked last, so that the restore was verified even if the files of the snapshots are not as expected
        assertAll(
                () -> assertFalse(unchanged.isEmpty(), "The snapshots " + names[names.length - 2] + " and "
                        + secondSnapshot + " have no unchanged files, so the second one could not reuse any. "
                        + "Files of the second snapshot: " + second.keySet()),
                () -> assertFalse(changed.isEmpty(), "All files of " + secondSnapshot + " are unchanged, so the "
                        + "deletion did not change the index files"));
    }

    /**
     * Triggers a backup and waits until it completed, returning the name of the snapshot it created.
     */
    private String backupAndWaitForNewSnapshot() {
        String latestBefore = latestSnapshot();
        String completedBefore = backupCompletedAt();
        awaitNextSecondAfter(completedBefore);
        assertEquals("OK", given().spec(specBackup).when().get().then().statusCode(200).extract().path("status"));
        await().atMost(180, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    String completed = backupCompletedAt();
                    return completed != null && !completed.equals(completedBefore)
                            && !String.valueOf(latestSnapshot()).equals(String.valueOf(latestBefore));
                });
        return latestSnapshot();
    }

    /**
     * The completion time of a backup has a resolution of a second: wait until the clock is past the previous one,
     * so that the next backup can't get the same completion time and be mistaken for the previous backup.
     */
    private void awaitNextSecondAfter(String completedAt) {
        if (completedAt == null) {
            return;
        }
        // Solr formats the time with Date#toString, and runs in a container with the clock of this machine
        Instant nextSecond = ZonedDateTime
                .parse(completedAt, DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH))
                .toInstant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> !Instant.now().isBefore(nextSecond));
    }

    private String backupCompletedAt() {
        // The backup details are a plain NamedList, which Solr renders as a flat array unless json.nl=map is given
        Map<String, Object> backup = given().spec(specBackupDetails).param("json.nl", "map").when().get().then()
                .statusCode(200).extract().path("details.backup");
        if (backup == null) {
            return null;
        }
        assertFalse(backup.containsKey("snapShootException"), "Backup failed: " + backup.get("snapShootException"));
        return (String) backup.get("snapshotCompletedAt");
    }

    private String latestSnapshot() {
        TreeMap<String, Map<String, S3Object>> snapshots = snapshotFiles();
        return snapshots.isEmpty() ? null : snapshots.lastKey();
    }

    /**
     * The files in each snapshot directory of the bucket, by snapshot name and file name, oldest snapshot first.
     */
    private TreeMap<String, Map<String, S3Object>> snapshotFiles() {
        Pattern snapshotFile = Pattern.compile("(snapshot\\.\\d{17})/([^/]+)$");
        TreeMap<String, Map<String, S3Object>> snapshots = new TreeMap<>();
        s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .forEach(object -> {
                    Matcher matcher = snapshotFile.matcher(object.key());
                    if (matcher.find()) {
                        snapshots.computeIfAbsent(matcher.group(1), name -> new TreeMap<>())
                                .put(matcher.group(2), object);
                    }
                });
        return snapshots;
    }

    private byte[] content(S3Object object) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(object.key()).build())
                .asByteArray();
    }

    private long numberOfDocuments() {
        Number numFound = given().spec(spec).params(nodeDocumentsQuery()).param("rows", "0").param("wt", "json")
                .when().get("select").then().statusCode(200).extract().path("response.numFound");
        return numFound.longValue();
    }

    private List<String> documentIds(int count) {
        return given().spec(spec).params(nodeDocumentsQuery()).param("rows", count).param("fl", "id").param("wt", "json")
                .when().get("select").then().statusCode(200).extract().path("response.docs.id");
    }

    /**
     * Matches the documents of repository nodes. The default query parser of the alfresco core does not support
     * "*:*", and the tracker state documents must not be deleted.
     */
    private static Map<String, String> nodeDocumentsQuery() {
        return Map.of("q", "*:*", "defType", "lucene", "fq", "DOC_TYPE:Node");
    }

    private void deleteDocumentAndCommit(String id) {
        given().spec(spec).queryParam("commit", "true").contentType("text/xml")
                .body("<delete><id>" + id.replace("&", "&amp;").replace("<", "&lt;") + "</id></delete>")
                .when().post("update").then().statusCode(200);
    }

    void validateSnapshotCount(long count) {
        await().atMost(180, TimeUnit.SECONDS)
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
        ClientOverrideConfiguration clientConfig = ClientOverrideConfiguration.builder().build();

        S3Configuration configuration = S3Configuration.builder()
                .build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .httpClientBuilder(ApacheHttpClient.builder()).overrideConfiguration(clientConfig)
                .serviceConfiguration(configuration);
        clientBuilder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));

        clientBuilder.endpointOverride(new URI(endpoint));
        clientBuilder.region(Region.of(region));

        clientBuilder.forcePathStyle(true);
        return clientBuilder.build();
    }


}
