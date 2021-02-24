/*
 * Copyright <2021> Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package software.amazon.documentdb.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.documentdb.jdbc.common.test.DocumentDbFlapDoodleTest;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Tests for the DocumentDbDriver
 */
public class DocumentDbDriverTest extends DocumentDbFlapDoodleTest {

    private static final String DATABASE_NAME = "database";
    private static final String COLLECTION_NAME = "testDocumentDbDriverTest";

    /**
     * Initializes the test class.
     * @throws SQLException if a driver manager error occurs.
     */
    @BeforeAll
    public static void initialize() throws SQLException, IOException {
        startMongoDbInstance(true);

        // Add 2 valid users to the local MongoDB instance.
        createUser(DATABASE_NAME, "user", "password");
        createUser(DATABASE_NAME, "user name", "pass word");

        prepareSimpleConsistentData(DATABASE_NAME, COLLECTION_NAME,
                10, "user", "password");
    }

    /**
     * Cleans-up any start up tasks.
     */
    @AfterAll
    public static void cleanup() {
        stopMongoDbInstance();
    }

    /**
     * Test for valid supported connection strings.
     */
    @Test
    public void testValidConnectionString() throws SQLException, IOException {
        final int timeout = 15;

        //TODO : Fix the commented out tests.
        final String[] tests = new String[] {
                "jdbc:documentdb://user:password@localhost:" + getMongoPort() + "/database?tls=false",
                "jdbc:documentdb://user:password@localhost:" + getMongoPort() + "/database?tls=false",
                "jdbc:documentdb://user:password@127.0.0.1:" + getMongoPort() + "/database?tls=false",
                "jdbc:documentdb://user%20name:pass%20word@localhost:" + getMongoPort() + "/database?tls=false",
                //"jdbc:documentdb://user%20name:pass%20word@localhost:1/database?ssl=true",
                //"jdbc:documentdb://user%20name:pass%20word@localhost:1/database?tls=true",
                //"jdbc:documentdb://user%20name:pass%20word@localhost:1/database?replicaSet=rs0",
        };

        for (String test : tests) {
            Assertions.assertNotNull(DriverManager.getDriver(test));
            final Connection connection = DriverManager.getConnection(test);
            Assertions.assertNotNull(connection);
            Assertions.assertTrue(connection.isValid(timeout));
        }
    }

    /**
     * Test invalid connection strings.
     */
    @Test
    public void testInvalidConnectionString() {
        final Map<String, String> tests = new HashMap<String, String>() {{
            put("jdbx:documentdb://localhost/database", "No suitable driver");
            put("documentdb://localhost/database", "No suitable driver");
            put("jdbc:documentdbx://localhost/database", "No suitable driver");
            put("jdbc:mongodb://localhost/database", "No suitable driver");
        }};

        for (Entry<String, String> test : tests.entrySet()) {
            Assertions.assertEquals(test.getValue(),
                    Assertions.assertThrows(SQLException.class, () -> DriverManager.getDriver(test.getKey()))
                            .getMessage());
            Assertions.assertEquals(String.format("No suitable driver found for %s", test.getKey()),
                    Assertions.assertThrows(SQLException.class, () -> DriverManager.getConnection(test.getKey()))
                            .getMessage());
        }
    }

    /**
     * Test null connection strings.
     */
    @Test
    public void testNullConnectionString() {
        Assertions.assertEquals("The url cannot be null",
                Assertions.assertThrows(SQLException.class, () -> DriverManager.getConnection(null))
                        .getMessage());
    }

    /**
     * Test empty user/password/host/database on connection strings provided by properties.
     */
    @Test
    public void testEmptyRequiredPropertiesOnConnectionString() throws SQLException {
        Properties properties = new Properties();
        properties.put(DocumentDbConnectionProperty.PASSWORD.getName(), "password");
        Connection connection = DriverManager.getConnection(
                String.format("jdbc:documentdb://user@localhost:%s/database?tls=false", getMongoPort()),
                properties);
        Assertions.assertNotNull(connection);

        properties = new Properties();
        properties.put(DocumentDbConnectionProperty.USER.getName(), "user");
        properties.put(DocumentDbConnectionProperty.PASSWORD.getName(), "password");
        connection = DriverManager.getConnection(
                String.format("jdbc:documentdb://localhost:%s/database?tls=false", getMongoPort()),
                properties);
        Assertions.assertNotNull(connection);

        properties = new Properties();
        properties.put(DocumentDbConnectionProperty.DATABASE.getName(), "database");
        DriverManager.getConnection(
                String.format("jdbc:documentdb://user:password@localhost:%s/?tls=false", getMongoPort()),
                properties);
        Assertions.assertNotNull(connection);

        properties = new Properties();
        properties.put(DocumentDbConnectionProperty.USER.getName(), "user");
        properties.put(DocumentDbConnectionProperty.PASSWORD.getName(), "password");
        properties.put(
                DocumentDbConnectionProperty.HOSTNAME.getName(),
                String.format("localhost:%s", getMongoPort()));
        DriverManager.getConnection(
                "jdbc:documentdb:///database?tls=false",
                properties);
        Assertions.assertNotNull(connection);

        properties = new Properties();
        properties.put(DocumentDbConnectionProperty.USER.getName(), "user");
        properties.put(DocumentDbConnectionProperty.PASSWORD.getName(), "password");
        properties.put(
                DocumentDbConnectionProperty.HOSTNAME.getName(),
                String.format("localhost:%s", getMongoPort()));
        properties.put(DocumentDbConnectionProperty.DATABASE.getName(), "database");
        DriverManager.getConnection(
                "jdbc:documentdb:///?tls=false",
                properties);
        Assertions.assertNotNull(connection);
    }

    /**
     * Test invalid connection strings that fail semantics check
     * @throws SQLException thrown when a driver or connection error is encountered.
     */
    @Test
    public void testInvalidMongoDbConnectionString() throws SQLException {
        final Map<String, String> tests = new HashMap<String, String>() {{
            put("jdbc:documentdb://localhost:1/database", "User and password are required to connect. Syntax: 'jdbc:documentdb://[<user>[:<password>]@]<hostname>/<database>[?options...]'");
            put("jdbc:documentdb://username:password@localhost:1:2/database", "Valid hostname is required to connect. Syntax: 'jdbc:documentdb://[<user>[:<password>]@]<hostname>/<database>[?options...]'");
            put("jdbc:documentdb://username:password@localhost:1/", "Database is required to connect. Syntax: 'jdbc:documentdb://[<user>[:<password>]@]<hostname>/<database>[?options...]'");
            put("jdbc:documentdb://username@localhost:1/database", "User and password are required to connect. Syntax: 'jdbc:documentdb://[<user>[:<password>]@]<hostname>/<database>[?options...]'");
        }};

        for (Entry<String, String> test : tests.entrySet()) {
            Assertions.assertNotNull(DriverManager.getDriver(test.getKey()));
            Assertions.assertEquals(test.getValue(),
                    Assertions.assertThrows(SQLException.class, () -> DriverManager.getConnection(test.getKey()))
                            .getMessage());
        }
    }

    /**
     * Tests the properties builder function
     */
    @Test
    public void testSetPropertiesFromConnectionString() throws SQLException {
        final Properties info = new Properties();
        info.clear();
        String connectionString = "mongodb://username:password@localhost/database";
        DocumentDbConnectionProperties properties = DocumentDbDriver
                .getPropertiesFromConnectionString(info, connectionString);
        Assertions.assertEquals(4, properties.size());
        Assertions.assertEquals("localhost", properties.getProperty("host"));
        Assertions.assertEquals(DATABASE_NAME, properties.getProperty("database"));
        Assertions.assertEquals("username", properties.getProperty("user"));
        Assertions.assertEquals("password", properties.getProperty("password"));

        // Connection string does not override existing properties.
        connectionString = "mongodb://username:password@127.0.0.1/newdatabase";
        properties = DocumentDbDriver
                .getPropertiesFromConnectionString(info, connectionString);
        Assertions.assertEquals(4, properties.size());
        Assertions.assertEquals("127.0.0.1", properties.getProperty("host"));
        Assertions.assertEquals("newdatabase", properties.getProperty("database"));
        Assertions.assertEquals("username", properties.getProperty("user"));
        Assertions.assertEquals("password", properties.getProperty("password"));

        // Get user (unencoded) name and password.
        info.clear();
        connectionString = "mongodb://user%20name:pass%20word@127.0.0.1/newdatabase";
        properties = DocumentDbDriver
                .getPropertiesFromConnectionString(info, connectionString);
        Assertions.assertEquals(4, properties.size());
        Assertions.assertEquals("127.0.0.1", properties.getProperty("host"));
        Assertions.assertEquals("newdatabase", properties.getProperty("database"));
        Assertions.assertEquals("user name", properties.getProperty("user"));
        Assertions.assertEquals("pass word", properties.getProperty("password"));

        // Check that all properties can be added.
        info.clear();
        connectionString = "mongodb://user%20name:pass%20word@127.0.0.1/newdatabase" +
                "?" + DocumentDbConnectionProperty.READ_PREFERENCE.getName() + "=" + "secondaryPreferred" +
                "&" + DocumentDbConnectionProperty.APPLICATION_NAME.getName() + "=" + "application" +
                "&" + DocumentDbConnectionProperty.REPLICA_SET.getName() + "=" + "rs0" +
                "&" + DocumentDbConnectionProperty.TLS_ENABLED.getName() + "=" + "true" +
                "&" + DocumentDbConnectionProperty.TLS_ALLOW_INVALID_HOSTNAMES.getName() + "=" + "true" +
                "&" + DocumentDbConnectionProperty.LOGIN_TIMEOUT_SEC.getName() + "=" + "4" +
                "&" + DocumentDbConnectionProperty.RETRY_READS_ENABLED.getName() + "=" + "true" +
                "&" + DocumentDbConnectionProperty.METADATA_SCAN_METHOD.getName() + "=" + "natural" +
                "&" + DocumentDbConnectionProperty.METADATA_SCAN_LIMIT.getName() + "=" + "1";
        properties = DocumentDbDriver
                .getPropertiesFromConnectionString(info, connectionString);
        Assertions.assertEquals(DocumentDbConnectionProperty.values().length, properties.size());

        // Check that unsupported properties are ignored.
        connectionString = "mongodb://user%20name:pass%20word@127.0.0.1/newdatabase" +
                "?" + "maxStalenessSeconds" + "=" + "value";
        properties = DocumentDbDriver
                .getPropertiesFromConnectionString(info, connectionString);
        Assertions.assertEquals(4, properties.size());
        Assertions.assertNull(properties.getProperty("maxStalenessSeconds"));
    }
}
