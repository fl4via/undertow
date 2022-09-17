/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022 Red Hat, Inc., and individual contributors
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

package io.undertow.server.protocol.http2;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.handlers.HttpContinueAcceptingHandler;
import io.undertow.server.session.SessionCookieConfig;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xnio.Options;

import java.io.IOException;
import java.net.URISyntaxException;

/**
 * Test Http2 with expect continue header in the request.
 *
 * @author Flavia Rainone
 */
@RunWith(DefaultServer.class)
//@HttpOneOnly
public class HTTP2WithExpectContinueTestCase {

    static Undertow server;

    @BeforeClass
    public static void setup() throws URISyntaxException {
        final SessionCookieConfig sessionConfig = new SessionCookieConfig();
        int port = DefaultServer.getHostPort("default");
        server = Undertow.builder()
                .addHttpListener(port + 1, DefaultServer.getHostAddress("default"))
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setSocketOption(Options.REUSE_ADDRESSES, true)
                .setHandler(new HttpContinueAcceptingHandler(
                        exchange -> {
                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                            exchange.getResponseSender().send("Hello World");
                        }))
                .build();
        server.start();
    }

    @AfterClass
    public static void stop() {
        server.stop();
        // sleep 1 s to prevent BindException (Address already in use) when running the CI
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignore) {}
    }
    @Test
    public void testHttpContinueAccepted() throws IOException {
        String message = "My HTTP Request!";
        HttpParams httpParams = new BasicHttpParams();
        httpParams.setParameter("http.protocol.wait-for-continue", Integer.MAX_VALUE);

        TestHttpClient client = new TestHttpClient();
        client.setParams(httpParams);
        try {
            HttpPost post = new HttpPost(DefaultServer.getDefaultServerURL() + "/path");
            post.addHeader("Expect", "100-continue");
            post.setEntity(new StringEntity(message));

            HttpResponse result = client.execute(post);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            Assert.assertEquals(message, HttpClientUtils.readResponse(result));
        } finally {
            client.getConnectionManager().shutdown();
        }
    }
}
