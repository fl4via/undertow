/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
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

package io.undertow.attribute;

import java.util.Iterator;
import java.util.Map;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;

/**
 * A response cookie
 */
public class ResponseCookieAttribute implements ExchangeAttribute {

    private static final String TOKEN_PREFIX = "%{resp-cookie,";

    private final String cookieName;

    public ResponseCookieAttribute(final String cookieName) {
        this.cookieName = cookieName;
    }

    @Override
    public String readAttribute(final HttpServerExchange exchange) {
        Map<String, Cookie> cookiesByPath = exchange.getResponseCookies().get(cookieName);
        if (cookiesByPath == null || cookiesByPath.size() == 0) {
            return null;
        }
        if (cookiesByPath.size() == 1) {
            return cookiesByPath.values().iterator().next().getValue();
        } else {
            final Iterator<Cookie> iterator = cookiesByPath.values().iterator();
            StringBuilder builder = new StringBuilder();
            builder.append(iterator.next());
            while (iterator.hasNext()) {
                final Cookie cookie = iterator.next();
                builder.append(";");
                builder.append(cookie.getValue());
            }
            return builder.toString();
        }
    }

    @Override
    public void writeAttribute(final HttpServerExchange exchange, final String newValue) throws ReadOnlyAttributeException {
        exchange.setResponseCookie(new CookieImpl(cookieName, newValue));
    }

    public static final class Builder implements ExchangeAttributeBuilder {

        @Override
        public String name() {
            return "Response cookie";
        }

        @Override
        public ExchangeAttribute build(final String token) {
            if (token.startsWith(TOKEN_PREFIX) && token.endsWith("}")) {
                final String cookieName = token.substring(TOKEN_PREFIX.length(), token.length() - 1);
                return new ResponseCookieAttribute(cookieName);
            }
            return null;
        }

        @Override
        public int priority() {
            return 0;
        }
    }
}
