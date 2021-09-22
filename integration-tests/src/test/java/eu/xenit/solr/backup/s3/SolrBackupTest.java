package eu.xenit.solr.backup.s3;

import static io.restassured.RestAssured.given;
import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


public class SolrBackupTest {
    static RequestSpecification spec;
    static RequestSpecification specBackup;
    static RequestSpecification specBackupDetails;
    static RequestSpecification specRestore;
    static RequestSpecification specRestoreStatus;

    @BeforeEach
    public void setup() {

        String basePathSolr = "solr/alfresco";
        String basePathSolrBackup = "solr/alfresco/replication";
        String solrHost = System.getProperty("solr.host","localhost");
        int solrPort = 0;
        try {
            solrPort = Integer.parseInt(System.getProperty("solr.tcp.8080","8080"));
        } catch(NumberFormatException e) {
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
                .addParam("command","backup")
                .addParam("repository","s3")
                .addParam("location","s3:///")
                .addParam("numberToKeep","3")
                .build();
        specBackupDetails = new RequestSpecBuilder()
                .setBaseUri(baseURISolr)
                .setPort(solrPort)
                .setBasePath(basePathSolrBackup)
                .addParam("command","details")
                .addParam("wt","json")
                .build();
        specRestore = new RequestSpecBuilder()
                .setBaseUri(baseURISolr)
                .setPort(solrPort)
                .setBasePath(basePathSolrBackup)
                .addParam("command","restore")
                .addParam("repository","s3")
                .addParam("location","s3:///")
                .build();
        specRestoreStatus = new RequestSpecBuilder()
                .setBaseUri(baseURISolr)
                .setPort(solrPort)
                .setBasePath(basePathSolrBackup)
                .addParam("command","restorestatus")
                .addParam("wt","json")
                .build();


        // wait for solr to track
        try {
            sleep(30000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testBackupEndpoint() {
        given()
                .spec(specBackup)
                .when()
                .get()
                .then()
                .statusCode(200);
        System.out.println("Backup triggered, will wait maximum 3 minutes");
        Object backup = null;
        long timeout = 180000;
        long elapsed = 0;
        while(backup == null && elapsed<timeout) {
            backup = given()
                    .spec(specBackupDetails)
                    .when()
                    .get()
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("details.backup");
            System.out.println("completedAt=" + backup);
            try {
                sleep(1000);
                elapsed += 1000;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        assertTrue(elapsed<timeout);
    }

    @Test
    public void testRestoreEndpoint() {
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
        while(!"success".equals(status) && elapsed<timeout) {
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
                e.printStackTrace();
            }
        }
        assertTrue(elapsed<timeout);
    }

}
