/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020 Red Hat, Inc., and individual contributors
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
package io.undertow.server;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * ExchangeInfo implementation.
 *
 * @author Flavia Rainone
 */
public class ExchangeInfoImpl implements ExchangeInfo {

    private static final AtomicLongFieldUpdater<ExchangeInfoImpl> processingTimeUpdater = AtomicLongFieldUpdater.newUpdater(ExchangeInfoImpl.class, "processingTime");

    private final long bytesReceived;
    private volatile long processingTime = -1L;
    private final HttpServerExchange exchange;

    public ExchangeInfoImpl(HttpServerExchange exchange, long bytesReceived) {
        this.exchange = exchange;
        this.bytesReceived = bytesReceived;
    }

    @Override
    public long getBytesReceived() {
        return bytesReceived;
    }

    @Override
    public int getContentLength() {
        return 0;
    }

    @Override
    public String getCurrentQueryString() {
        return exchange.getQueryString();
    }

    @Override
    public String getCurrentUri() {
        return exchange.getRequestURI();
    }

    @Override
    public String getMethod() {
        return exchange.getRequestMethod().toString();
    }

    @Override
    public long getProcessingTime() {
        if (this.processingTime == -1) {
            long startTime = exchange.getRequestStartTime();
            if (startTime < 0)
                return 0;
            return System.nanoTime() - startTime;
        }
        return processingTime;
    }

    public void setProcessingTime(long processingTime) {
        processingTimeUpdater.getAndSet(this, processingTime);
    }

    @Override
    public String getProtocol() {
        return exchange.getProtocol().toString();
    }

    @Override
    public String getHostName() {
        return exchange.getHostName();
    }

    @Override
    public int getHostPort() {
        return exchange.getHostPort();
    }
}
