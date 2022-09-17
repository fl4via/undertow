/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.undertow.client.http;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.client.UndertowClient;
import io.undertow.connector.ByteBufferPool;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.handlers.HttpContinueAcceptingHandler;
import io.undertow.server.handlers.form.FormEncodedDataDefinition;
import io.undertow.testutils.DebuggingSlicePool;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpOneOnly;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import io.undertow.util.StringReadChannelListener;
import io.undertow.util.StringWriteChannelListener;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSinkChannel;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 *
 *
 * @author Flavia Rainone
 */
@RunWith(DefaultServer.class)
@HttpOneOnly
// Mimic curl --http2 -X PUT  localhost:8080/ -d foo -X PUT -H 'Expect: 100-continue' --http2-prior-knowledge -v
public class H2CUpgradeExpectContinueTestCase {

    private static final String ECHO_PATH = "/echo";
    private static final String HELLO_WORLD = "Hello World";
    private static final AttachmentKey<String> RESPONSE_BODY = AttachmentKey.create(String.class);

    private static ByteBufferPool smallPool;
    private static XnioWorker worker;
    private static Undertow server;

    /**
     * Initializes the server with the H2C handler and adds the echo handler to
     * manage the requests.
     * @throws IOException Some error
     */
    @BeforeClass
    public static void beforeClass() throws IOException {
        // server and client pool is using 1024 for the buffer size
        smallPool = new DebuggingSlicePool(new DefaultByteBufferPool(true, 1024, 1000, 10, 100));

        server = Undertow.builder()
                .setByteBufferPool(smallPool)
                .addHttpListener(DefaultServer.getHostPort("default") + 1, DefaultServer.getHostAddress("default"))//, new Http2UpgradeHandler(path))
                .setSocketOption(Options.REUSE_ADDRESSES, true)
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setHandler(new HttpContinueAcceptingHandler(
                        exchange -> {
                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                            exchange.getResponseSender().send(HELLO_WORLD);
                        }))
                .build();
        server.start();

        // Create xnio worker
        final Xnio xnio = Xnio.getInstance();
        worker = xnio.createWorker(null, OptionMap.builder()
                .set(Options.WORKER_IO_THREADS, 8)
                .set(Options.TCP_NODELAY, true)
                .set(Options.KEEP_ALIVE, true)
                .getMap());
    }

    /**
     * Stops server and worker.
     */
    @AfterClass
    public static void afterClass() {
        if (server != null) {
            server.stop();
        }
        if (worker != null) {
            worker.shutdown();
        }
        if (smallPool != null) {
            smallPool.close();
            smallPool = null;
        }
    }

    /**
     * Method that sends a POST request with Expected:100-continue header.
     * @param connection The connection to use
     * @throws Exception Some error
     */
    private ClientResponse sendRequestPart1(ClientConnection connection) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<ClientResponse> responses = new CopyOnWriteArrayList<>();
        StringBuilder sb = new StringBuilder();
        final String content = sb.length() > 0? sb.toString() : null;
        connection.getIoThread().execute(() -> {
                final ClientRequest request = new ClientRequest()
                        .setMethod(Methods.POST)
                        .setPath(ECHO_PATH);
                request.getRequestHeaders().put(Headers.HOST, DefaultServer.getHostAddress());
                request.getRequestHeaders().put(Headers.EXPECT, "100-continue");
                connection.sendRequest(request, createClientCallback(responses, latch, content));
            }
        );
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (responses.isEmpty())
            return null;
        assertEquals(1, responses.size());
        return responses.get(0);
    }

    private ClientResponse sendRequestPart2(ClientConnection connection) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<ClientResponse> responses = new CopyOnWriteArrayList<>();
        StringBuilder sb = new StringBuilder();
        final String content = sb.length() > 0? sb.toString() : null;
        connection.getIoThread().execute(() -> {
                    final ClientRequest request = new ClientRequest()
                            .setMethod(Methods.POST)
                            .setPath(ECHO_PATH);
                    request.getRequestHeaders().put(Headers.HOST, DefaultServer.getHostAddress());
                    request.getRequestHeaders().put(Headers.CONTENT_TYPE, FormEncodedDataDefinition.APPLICATION_X_WWW_FORM_URLENCODED);
                    connection.sendRequest(request, createClientCallback(responses, latch, content));
                }
        );
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (responses.isEmpty())
            return null;
        assertEquals(1, responses.size());
        return responses.get(0);
    }

    @Test
    public void test() throws Exception {
        final UndertowClient client = UndertowClient.getInstance();

        // the client connection uses the small byte-buffer of 1024 to force the continuation frames
        final ClientConnection connection = client.connect(
                new URI("http://" + DefaultServer.getHostAddress() + ":" + (DefaultServer.getHostPort("default") + 1)),
                worker, new UndertowXnioSsl(worker.getXnio(), OptionMap.EMPTY, DefaultServer.getClientSSLContext()),
                smallPool, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true)).get();
        try {
            // the first request triggers the upgrade to H2C
            assertResponse(sendRequestPart1(connection));
            assertResponse(sendRequestPart2(connection));
            assertNull(sendRequestPart1(connection));
            assertNull(sendRequestPart2(connection));
        } finally {
            IoUtils.safeClose(connection);
        }
    }

    private void assertResponse(ClientResponse response) {
        assertNotNull(response);
        assertEquals("Response " + 0 + " code was not OK", StatusCodes.OK, response.getResponseCode());
        assertEquals("Incorrect data received for response " + 0, HELLO_WORLD, response.getAttachment(RESPONSE_BODY));

    }

    /**
     * Create the callback to receive the response and assign it to the list.
     * @param responses The list where the response will be added
     * @param latch The latch to count down when the response is received
     * @param message The message to send if it's a POST message (if null nothing is send)
     * @return The created callback
     */
    private static ClientCallback<ClientExchange> createClientCallback(final List<ClientResponse> responses, final CountDownLatch latch, String message) {
        return new ClientCallback<>() {
            @Override
            public void completed(ClientExchange result) {
                if (message != null) {
                    new StringWriteChannelListener(message).setup(result.getRequestChannel());
                }
                result.setResponseListener(new ClientCallback<>() {
                    @Override
                    public void completed(final ClientExchange result) {
                        responses.add(result.getResponse());
                        new StringReadChannelListener(result.getConnection().getBufferPool()) {

                            @Override
                            protected void stringDone(String string) {
                                result.getResponse().putAttachment(RESPONSE_BODY, string);
                                latch.countDown();
                            }

                            @Override
                            protected void error(IOException e) {
                                e.printStackTrace();
                                latch.countDown();
                            }
                        }.setup(result.getResponseChannel());
                    }

                    @Override
                    public void failed(IOException e) {
                        e.printStackTrace();
                        latch.countDown();
                    }
                });
                try {
                    result.getRequestChannel().shutdownWrites();
                    if (!result.getRequestChannel().flush()) {
                        result.getRequestChannel().getWriteSetter().set(ChannelListeners.<StreamSinkChannel>flushingChannelListener(null, null));
                        result.getRequestChannel().resumeWrites();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    latch.countDown();
                }
            }

            @Override
            public void failed(IOException e) {
                e.printStackTrace();
                latch.countDown();
            }
        };
    }
}
