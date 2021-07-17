package io.undertow.server;

import io.undertow.server.protocol.http.ContentOverrunTestCase;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({ StopTestCase.class, ContentOverrunTestCase.class, ReadTimeoutTestCase.class })
public class TestSuite {}
