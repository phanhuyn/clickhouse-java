package com.clickhouse.client.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseCompression;
import com.clickhouse.client.ClickHouseConfig;
import com.clickhouse.client.ClickHouseCredentials;
import com.clickhouse.client.ClickHouseException;
import com.clickhouse.client.ClickHouseFormat;
import com.clickhouse.client.ClickHouseInputStream;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseNodeSelector;
import com.clickhouse.client.ClickHouseOutputStream;
import com.clickhouse.client.ClickHouseParameterizedQuery;
import com.clickhouse.client.ClickHouseProtocol;
import com.clickhouse.client.ClickHouseRecord;
import com.clickhouse.client.ClickHouseRequest;
import com.clickhouse.client.ClickHouseRequestManager;
import com.clickhouse.client.ClickHouseResponse;
import com.clickhouse.client.ClickHouseResponseSummary;
import com.clickhouse.client.ClickHouseUtils;
import com.clickhouse.client.ClickHouseVersion;
import com.clickhouse.client.ClientIntegrationTest;
import com.clickhouse.client.config.ClickHouseClientOption;
import com.clickhouse.client.config.ClickHouseHealthCheckMethod;
import com.clickhouse.client.config.ClickHouseOption;
import com.clickhouse.client.data.ClickHouseExternalTable;
import com.clickhouse.client.data.ClickHouseStringValue;
import com.clickhouse.client.http.config.ClickHouseHttpOption;

import com.clickhouse.client.http.config.HttpConnectionProvider;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ClickHouseHttpClientTest extends ClientIntegrationTest {
    @DataProvider(name = "requestCompressionMatrix")
    protected Object[][] getRequestCompressionMatrix() {
        return new Object[][] {
                { ClickHouseCompression.NONE, -2, 2, 1 },
                { ClickHouseCompression.LZ4, -2, 19, 1 }, // [0, 18]
                { ClickHouseCompression.SNAPPY, -2, 33, 1024 }, // [1 * 1024, 32 * 1024]
                { ClickHouseCompression.ZSTD, -2, 23, 1 }, // [0, 22]
        };
    }

    @DataProvider(name = "mixedCompressionMatrix")
    protected Object[][] getMixedCompressionMatrix() {
        // ClickHouse Code: 638. DB::Exception: hadoop snappy decode
        // error:INVALID_INPUT. (SNAPPY_UNCOMPRESS_FAILED)
        ClickHouseCompression[] supportedRequestCompression = { ClickHouseCompression.NONE, ClickHouseCompression.LZ4,
                ClickHouseCompression.ZSTD };
        ClickHouseCompression[] supportedResponseCompression = { ClickHouseCompression.NONE,
                ClickHouseCompression.BROTLI, ClickHouseCompression.BZ2, ClickHouseCompression.DEFLATE,
                ClickHouseCompression.GZIP, ClickHouseCompression.LZ4, ClickHouseCompression.XZ,
                ClickHouseCompression.ZSTD };
        Object[][] matrix = new Object[supportedRequestCompression.length * supportedResponseCompression.length][];
        int i = 0;
        for (ClickHouseCompression reqComp : supportedRequestCompression) {
            for (ClickHouseCompression respComp : supportedResponseCompression) {
                matrix[i++] = new Object[] { reqComp, respComp };
            }
        }
        return matrix;
    }

    @Override
    protected ClickHouseProtocol getProtocol() {
        return ClickHouseProtocol.HTTP;
    }

    @Override
    protected Class<? extends ClickHouseClient> getClientClass() {
        return ClickHouseHttpClient.class;
    }

    @Override
    protected Map<ClickHouseOption, Serializable> getClientOptions() {
        return Collections.singletonMap(ClickHouseHttpOption.CONNECTION_PROVIDER,
                HttpConnectionProvider.HTTP_URL_CONNECTION);
    }

    @Test(dataProvider = "requestCompressionMatrix", groups = "integration")
    public void testCompressedRequest(ClickHouseCompression compression, int startLevel, int endLevel, int step)
            throws ClickHouseException {
        final ClickHouseNode server = getServer();
        final int readBufferSize = ClickHouseUtils.getBufferSize(
                (int) ClickHouseClientOption.READ_BUFFER_SIZE.getDefaultValue(),
                (int) ClickHouseClientOption.BUFFER_SIZE.getDefaultValue(),
                (int) ClickHouseClientOption.MAX_BUFFER_SIZE.getDefaultValue());

        for (int i = startLevel; i <= endLevel; i += step) {
            final ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (ClickHouseOutputStream out = ClickHouseOutputStream.of(o, readBufferSize, compression, i, null)) {
                out.write("1,23\n4,56".getBytes());
                out.flush();
            } catch (IOException e) {
                throw ClickHouseException.of(e, server);
            }
            try (ClickHouseClient client = getClient();
                    ClickHouseResponse response = newRequest(client, server)
                            .format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                            .compressServerResponse(false)
                            .decompressClientRequest(true, compression, i)
                            .external(
                                    // external table with compressed data
                                    ClickHouseExternalTable.builder().name("x").columns("i Int32, s String")
                                            .compression(compression)
                                            .format(ClickHouseFormat.CSV)
                                            .content(new ByteArrayInputStream(o.toByteArray())).build(),
                                    // external table without compression
                                    ClickHouseExternalTable.builder().name("y").columns("s String, i Int32")
                                            .format(ClickHouseFormat.TSV)
                                            .content(new ByteArrayInputStream("32\t1\n43\t2\n54\t3\n65\t4".getBytes()))
                                            .build())
                            .query("select x.* from x inner join y on x.i = y.i").executeAndWait()) {
                int j = 0;
                for (ClickHouseRecord r : response.records()) {
                    Assert.assertEquals(r.getValue(0).asInteger(), j == 0 ? 1 : 4);
                    Assert.assertEquals(r.getValue(1).asInteger(), j == 0 ? 23 : 56);
                    j++;
                }
                Assert.assertEquals(j, 2);
            }
        }
    }

    @Test(dataProvider = "mixedCompressionMatrix", groups = "integration")
    public void testDecompressResponse(ClickHouseCompression reqComp, ClickHouseCompression respComp)
            throws ClickHouseException {
        try (ClickHouseClient client = getClient();
                ClickHouseResponse response = newRequest(client, getServer())
                        .format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                        .decompressClientRequest(true, reqComp)
                        .compressServerResponse(true, respComp)
                        .query("select number n, toString(number+1) s from numbers(10)").executeAndWait()) {
            int i = 0;
            for (ClickHouseRecord r : response.records()) {
                Assert.assertEquals(r.getValue(0).asInteger(), i++);
                Assert.assertEquals(r.getValue(1).asInteger(), i);
            }
            Assert.assertEquals(i, 10);
        }
    }

    @Test(groups = "integration")
    public void testAuthentication() throws ClickHouseException {
        String sql = "select currentUser()";
        try (ClickHouseClient client = getClient(
                new ClickHouseConfig(null, ClickHouseCredentials.fromUserAndPassword("dba", "dba"), null, null));
                ClickHouseResponse response = newRequest(client, getServer())
                        // .option(ClickHouseHttpOption.CUSTOM_PARAMS, "user=dba,password=incorrect")
                        .query(sql).executeAndWait()) {
            Assert.assertEquals(response.firstRecord().getValue(0).asString(), "dba");
        }

        try (ClickHouseClient client = getClient();
                ClickHouseResponse response = newRequest(client, getServer())
                        .option(ClickHouseHttpOption.CUSTOM_HEADERS, "Authorization=Basic ZGJhOmRiYQ==")
                        // .option(ClickHouseHttpOption.CUSTOM_PARAMS, "user=dba,password=incorrect")
                        .query(sql).executeAndWait()) {
            Assert.assertEquals(response.firstRecord().getValue(0).asString(), "dba");
        }

        try (ClickHouseClient client = getClient();
                ClickHouseResponse response = newRequest(client, getServer(ClickHouseNode
                        .of("http://localhost?custom_http_headers=aUthorization%3DBasic%20ZGJhOmRiYQ%3D%3D")))
                        .query(sql).executeAndWait()) {
            Assert.assertEquals(response.firstRecord().getValue(0).asString(), "dba");
        }
    }

    @Override
    @Test(groups = "integration")
    public void testSession() throws ClickHouseException {
        super.testSession();

        ClickHouseNode server = getServer();
        String sessionId = ClickHouseRequestManager.getInstance().createSessionId();
        try (ClickHouseClient client = getClient()) {
            ClickHouseRequest<?> req = newRequest(client, server).session(sessionId, true)
                    .option(ClickHouseHttpOption.CUSTOM_PARAMS, "session_check=0,max_query_size=1000")
                    .option(ClickHouseHttpOption.CONNECTION_PROVIDER, HttpConnectionProvider.APACHE_HTTP_CLIENT)
                    .transaction(null)
                    .format(ClickHouseFormat.RowBinaryWithNamesAndTypes);
            try (ClickHouseResponse resp = req.copy()
                    .query("create temporary table test_session(a String)engine=Memory as select '1'")
                    .executeAndWait()) {
                // ignore
            }
            try (ClickHouseResponse resp = req.copy().query("select * from test_session").executeAndWait()) {
                Assert.assertEquals(resp.firstRecord().getValue(0).asInteger(), 1);
            }
        }
    }

    @Test(groups = "integration")
    public void testPing() {
        try (ClickHouseClient client = ClickHouseClient.builder().options(getClientOptions())
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP)).build()) {
            Assert.assertTrue(client.ping(getServer(), 3000));
        }

        try (ClickHouseClient client = ClickHouseClient.builder().options(getClientOptions())
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                .option(ClickHouseHttpOption.WEB_CONTEXT, "a/b").build()) {
            Assert.assertTrue(client.ping(getServer(), 3000));
        }

        try (ClickHouseClient client = ClickHouseClient.builder().options(getClientOptions())
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                .option(ClickHouseHttpOption.WEB_CONTEXT, "a/b")
                .option(ClickHouseClientOption.HEALTH_CHECK_METHOD, ClickHouseHealthCheckMethod.PING).build()) {
            Assert.assertFalse(client.ping(getServer(), 3000));
        }

        try (ClickHouseClient client = ClickHouseClient.builder().options(getClientOptions())
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                .option(ClickHouseHttpOption.WEB_CONTEXT, "/")
                .option(ClickHouseClientOption.HEALTH_CHECK_METHOD, ClickHouseHealthCheckMethod.PING).build()) {
            Assert.assertTrue(client.ping(getServer(), 3000));
        }

        try (ClickHouseClient client = ClickHouseClient.builder().options(getClientOptions())
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                .option(ClickHouseClientOption.HEALTH_CHECK_METHOD, ClickHouseHealthCheckMethod.PING)
                .removeOption(ClickHouseHttpOption.WEB_CONTEXT).build()) {
            Assert.assertTrue(client.ping(getServer(), 3000));
        }
    }

    @Test // (groups = "integration")
    public void testTransaction() throws ClickHouseException {
        testAbortTransaction();
        testNewTransaction();
        testJoinTransaction();
        testCommitTransaction();
        testRollbackTransaction();
        testTransactionSnapshot();
        testTransactionTimeout();
        testImplicitTransaction();
    }

    @Test // (groups = "integration")
    public void testSslClientAuth() throws ClickHouseException {
        // NPE on JDK 8:
        // java.lang.NullPointerException
        // at sun.security.provider.JavaKeyStore.convertToBytes(JavaKeyStore.java:822)
        // at
        // sun.security.provider.JavaKeyStore.engineSetKeyEntry(JavaKeyStore.java:271)
        // at
        // sun.security.provider.JavaKeyStore$JKS.engineSetKeyEntry(JavaKeyStore.java:57)
        // at
        // sun.security.provider.KeyStoreDelegator.engineSetKeyEntry(KeyStoreDelegator.java:117)
        // at
        // sun.security.provider.JavaKeyStore$DualFormatJKS.engineSetKeyEntry(JavaKeyStore.java:71)
        // at java.security.KeyStore.setKeyEntry(KeyStore.java:1140)
        // at
        // com.clickhouse.client.config.ClickHouseDefaultSslContextProvider.getKeyStore(ClickHouseDefaultSslContextProvider.java:105)
        ClickHouseNode server = getSecureServer(ClickHouseProtocol.HTTP);
        try (ClickHouseClient client = getSecureClient();
                ClickHouseResponse response = newRequest(client, server)
                        .query("select 123").executeAndWait()) {
            Assert.assertEquals(response.firstRecord().getValue(0).asInteger(), 123);
        }
    }

    @Test(groups = { "integration" })
    public void testCreateTableAsSelect() throws ClickHouseException {
        ClickHouseNode server = getServer();
        sendAndWait(server, "drop table if exists test_create_table_as_select");
        try (ClickHouseClient client = getClient()) {
            ClickHouseRequest<?> request = newRequest(client, server);
            try (ClickHouseResponse resp = request.write()
                    .external(ClickHouseExternalTable.builder().name("myExtTable").addColumn("s", "String")
                            .addColumn("i", "Int32").content(ClickHouseInputStream.of("one,1\ntwo,2"))
                            .format(ClickHouseFormat.CSV).build())
                    .query("create table test_create_table_as_select engine=Memory as select * from myExtTable")
                    .executeAndWait()) {
                // ignore
            }

            try (ClickHouseResponse resp = request.format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                    .query("select * from test_create_table_as_select order by i").executeAndWait()) {
                String[] array = new String[] { "one", "two" };
                int count = 0;
                for (ClickHouseRecord r : resp.records()) {
                    Assert.assertEquals(r.getValue("i").asInteger(), count + 1);
                    Assert.assertEquals(r.getValue("s").asString(), array[count]);
                    count++;
                }
                Assert.assertEquals(count, array.length);
            }
        }
    }

    @Override
    @Test(groups = { "integration" })
    public void testMutation() throws ClickHouseException {
        super.testMutation();

        ClickHouseNode server = getServer();
        sendAndWait(server, "drop table if exists test_http_mutation",
                "create table test_http_mutation(a String, b Nullable(Int64))engine=Memory");
        try (ClickHouseClient client = getClient();
                ClickHouseResponse response = newRequest(client, server)
                        .set("send_progress_in_http_headers", 1)
                        .query("insert into test_http_mutation select toString(number), number from numbers(1)")
                        .executeAndWait()) {
            ClickHouseResponseSummary summary = response.getSummary();
            Assert.assertEquals(summary.getWrittenRows(), 1L);
        }
    }

    @Test(groups = { "integration" })
    public void testLogComment() throws ClickHouseException, IOException {
        ClickHouseNode server = getServer(ClickHouseProtocol.HTTP);
        String uuid = UUID.randomUUID().toString();
        try (ClickHouseClient client = ClickHouseClient.newInstance()) {
            ClickHouseRequest<?> request = newRequest(client, server)
                    .format(ClickHouseFormat.RowBinaryWithNamesAndTypes);
            try (ClickHouseResponse resp = request
                    .query("select version()").executeAndWait()) {
                if (!ClickHouseVersion.of(resp.firstRecord().getValue(0).asString()).check("[21.2,)")) {
                    return;
                }
            }
            try (ClickHouseResponse resp = request
                    .option(ClickHouseClientOption.LOG_LEADING_COMMENT, true)
                    .query("-- select something\r\nselect 1", uuid).executeAndWait()) {
            }

            try (ClickHouseResponse resp = request
                    .option(ClickHouseClientOption.LOG_LEADING_COMMENT, true)
                    .query("SYSTEM FLUSH LOGS", uuid).executeAndWait()) {
            }

            try (ClickHouseResponse resp = request
                    .option(ClickHouseClientOption.LOG_LEADING_COMMENT, true)
                    .query(ClickHouseParameterizedQuery
                            .of(request.getConfig(), "select log_comment from system.query_log where query_id = :qid"))
                    .params(ClickHouseStringValue.of(uuid)).executeAndWait()) {
                int counter = 0;
                for (ClickHouseRecord r : resp.records()) {
                    Assert.assertEquals(r.getValue(0).asString(), "select something");
                    counter++;
                }
                Assert.assertEquals(counter, 2);
            }
        }
    }

    @Test(groups = { "integration" })
    public void testPost() throws ClickHouseException {
        ClickHouseNode server = getServer(ClickHouseProtocol.HTTP);

        try (ClickHouseClient client = ClickHouseClient.builder().options(getClientOptions())
                .defaultCredentials(ClickHouseCredentials.fromUserAndPassword("foo", "bar")).build()) {
            // why no detailed error message for this: "select 1，2"
            try (ClickHouseResponse resp = newRequest(client, server).compressServerResponse(false)
                    .format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                    .query("select 1,2").executeAndWait()) {
                int count = 0;
                for (ClickHouseRecord r : resp.records()) {
                    Assert.assertEquals(r.getValue(0).asInteger(), 1);
                    Assert.assertEquals(r.getValue(1).asInteger(), 2);
                    count++;
                }

                Assert.assertEquals(count, 1);
            }

            // reuse connection
            try (ClickHouseResponse resp = newRequest(client, server)
                    .format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                    .query("select 3,4").executeAndWait()) {
                int count = 0;
                for (ClickHouseRecord r : resp.records()) {
                    Assert.assertEquals(r.getValue(0).asInteger(), 3);
                    Assert.assertEquals(r.getValue(1).asInteger(), 4);
                    count++;
                }

                Assert.assertEquals(count, 1);
            }
        }
    }
}
