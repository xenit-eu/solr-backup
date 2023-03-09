package eu.xenit.solr.backup.s3;

import groovy.util.logging.Slf4j;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class SolrBackupTest {
    private static final Log log = LogFactory.getLog(SolrBackupTest.class);
    static RequestSpecification spec;
    static RequestSpecification specBackup;
    static RequestSpecification specBackupDetails;
    static RequestSpecification specRestore;
    static RequestSpecification specRestoreStatus;

    @BeforeEach
    public void setup() {
        String basePathSolr = "solr/alfresco";
        String basePathSolrBackup = "solr/alfresco/replication";
        String solrHost = System.getProperty("solr.host", "localhost");
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
                .addParam("location", "s3:///")
                .addParam("numberToKeep", "3")
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
                .addParam("location", "s3:///")
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
    void testBackupEndpoint() {
        String status = given()
                .spec(specBackup)
                .when()
                .get()
                .then()
                .statusCode(200)
                .extract()
                .path("status");
        assertEquals("OK", status);
        System.out.println("Backup triggered, will wait maximum 6 minutes");
        Object backup = null;
        long timeout = 500000;
        long elapsed = 0;
        while (backup == null && elapsed < timeout) {
            backup = given()
                    .spec(specBackupDetails)
                    .when()
                    .get()
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("details.backup");
            System.out.println("elapsed =" + elapsed);
            try {
                sleep(1000);
                elapsed += 1000;
            } catch (InterruptedException e) {
                log.error(e);
            }
        }
        assertTrue(elapsed < timeout);
    }

    @Test
    void testRestoreEndpoint() {
        given()
                .spec(specRestore)
                .when()
                .get()
                .then()
                .statusCode(200);
        System.out.println("Restore triggered, will wait maximum 3 minutes");
        String status = "";
        long timeout = 180000;
        long elapsed = 0;
        while (!"success".equals(status) && elapsed < timeout) {
            status = given()
                    .spec(specRestoreStatus)
                    .when()
                    .get()
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("restorestatus.status");
            System.out.println("status=" + status);
            try {
                sleep(1000);
                elapsed += 1000;
            } catch (InterruptedException e) {
                log.error(e);
            }
        }
        assertTrue(elapsed < timeout);
    }

}
