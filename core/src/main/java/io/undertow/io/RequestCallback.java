package io.undertow.io;

public interface RequestCallback {

    void requestStarted();

    void failedParse();

    void connectionIdle();


}
