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

package io.undertow.client.http;

import io.undertow.client.ClientRequest;
import io.undertow.server.TruncatedResponseException;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import org.jboss.logging.Logger;
import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSourceChannel;
import org.xnio.conduits.AbstractStreamSinkConduit;
import org.xnio.conduits.Conduits;
import org.xnio.conduits.StreamSinkConduit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static org.xnio.Bits.allAreClear;
import static org.xnio.Bits.allAreSet;
import static org.xnio.Bits.anyAreSet;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author Emanuel Muckenhuber
 */
final class HttpRequestConduit extends AbstractStreamSinkConduit<StreamSinkConduit> {

    private static final Logger log = Logger.getLogger("io.undertow.client.request");

    private final ByteBufferPool pool;

    private volatile int state = STATE_START;

    private Iterator<HttpString> nameIterator;
    private String string;
    private HttpString headerName;
    private Iterator<String> valueIterator;
    private int charIndex;
    private PooledByteBuffer pooledBuffer;
    private final ClientRequest request;

    private static final int STATE_BODY = 0; // Message body, normal pass-through operation
    private static final int STATE_URL = 1; //Writing the URL
    private static final int STATE_START = 2; // No headers written yet
    private static final int STATE_HDR_NAME = 3; // Header name indexed by charIndex
    private static final int STATE_HDR_D = 4; // Header delimiter ':'
    private static final int STATE_HDR_DS = 5; // Header delimiter ': '
    private static final int STATE_HDR_VAL = 6; // Header value
    private static final int STATE_HDR_EOL_CR = 7; // Header line CR
    private static final int STATE_HDR_EOL_LF = 8; // Header line LF
    private static final int STATE_HDR_FINAL_CR = 9; // Final CR
    private static final int STATE_HDR_FINAL_LF = 10; // Final LF
    private static final int STATE_BUF_FLUSH = 11; // flush the buffer and go to writing body

    private static final int MASK_STATE         = 0x0000000F;
    private static final int FLAG_SHUTDOWN      = 0x00000010;
    private static final int FLAG_WRITING       = 0x00000020;

    private static final AtomicIntegerFieldUpdater<HttpRequestConduit> stateUpdater = AtomicIntegerFieldUpdater.newUpdater(
            HttpRequestConduit.class, "state");


    HttpRequestConduit(final StreamSinkConduit next, final ByteBufferPool pool, final ClientRequest request) {
        super(next);
        this.pool = pool;
        this.request = request;
    }

    /**
     * Handles writing out the header data. It can also take a byte buffer of user
     * data, to enable both user data and headers to be written out in a single operation,
     * which has a noticeable performance impact.
     *
     * It is up to the caller to note the current position of this buffer before and after they
     * call this method, and use this to figure out how many bytes (if any) have been written.
     * @param state
     * @param userData
     * @return
     * @throws java.io.IOException
     */
    private int processWrite(int state, final ByteBuffer userData) throws IOException {
        return doProcessWrite(state, userData);
    }

    /**
     * Handles writing out the header data. It can also take a byte buffer of user
     * data, to enable both user data and headers to be written out in a single operation,
     * which has a noticeable performance impact.
     *
     * It is up to the caller to note the current position of this buffer before and after they
     * call this method, and use this to figure out how many bytes (if any) have been written.
     * @param state
     * @param userData
     * @return
     * @throws java.io.IOException
     */
    private int doProcessWrite(int state, final ByteBuffer userData) throws IOException {
        if (state == STATE_START) {
            pooledBuffer = pool.allocate();
        }
        ClientRequest request = this.request;
        ByteBuffer buffer = pooledBuffer.getBuffer();
        int length;
        int res;
        // BUFFER IS FLIPPED COMING IN
        if (state != STATE_START && buffer.hasRemaining()) {
            log.trace("Flushing remaining buffer");
            do {
                res = next.write(buffer);
                if (res == 0) {
                    return state;
                }
            } while (buffer.hasRemaining());
        }
        buffer.clear();
        // BUFFER IS NOW EMPTY FOR FILLING
        for (;;) {
            switch (state) {
                case STATE_BODY: {
                    log.tracef("%s.processWrite state is body", this);
                    // shouldn't be possible, but might as well do the right thing anyway
                    return state;
                }
                case STATE_START: {
                    log.tracef("%s.processWrite starting request", this);
                    int len = request.getMethod().length() + request.getPath().length() + request.getProtocol().length() + 4;

                    // test that our buffer has enough space for the initial request line plus one more CR+LF
                    if(len <= buffer.remaining()) {
                        assert buffer.remaining() >= 50;
                        request.getMethod().appendTo(buffer);
                        buffer.put((byte) ' ');
                        string = request.getPath();
                        length = string.length();
                        for (charIndex = 0; charIndex < length; charIndex++) {
                            buffer.put((byte) string.charAt(charIndex));
                        }
                        buffer.put((byte) ' ');
                        request.getProtocol().appendTo(buffer);
                        buffer.put((byte) '\r').put((byte) '\n');
                    } else {
                        StringBuilder sb = new StringBuilder(len);
                        sb.append(request.getMethod().toString());
                        sb.append(" ");
                        sb.append(request.getPath());
                        sb.append(" ");
                        sb.append(request.getProtocol());
                        sb.append("\r\n");
                        string = sb.toString();
                        charIndex = 0;
                        state = STATE_URL;
                        break;
                    }
                    HeaderMap headers = request.getRequestHeaders();
                    nameIterator = headers.getHeaderNames().iterator();
                    if (! nameIterator.hasNext()) {
                        log.tracef("%s.processWrite: no request headers at start request", this);
                        buffer.put((byte) '\r').put((byte) '\n');
                        buffer.flip();
                        while (buffer.hasRemaining()) {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.trace("Continuation");
                                log.tracef("%s.processWrite: continuation at start request", this);
                                return STATE_BUF_FLUSH;
                            }
                        }
                        pooledBuffer.close();
                        pooledBuffer = null;
                        log.tracef("%s.processWrite: reached state body", this);
                        return STATE_BODY;
                    }
                    headerName = nameIterator.next();
                    charIndex = 0;
                    // fall thru
                }
                case STATE_HDR_NAME: {
                    log.tracef("%s.processWrite: processing header '%s'", this, headerName);
                    length = headerName.length();
                    while (charIndex < length) {
                        if (buffer.hasRemaining()) {
                            buffer.put(headerName.byteAt(charIndex++));
                        } else {
                            log.tracef("%s.processWrite: at header name, flushing buffer", this);
                            buffer.flip();
                            do {
                                res = next.write(buffer);
                                if (res == 0) {
                                    log.trace("Continuation");
                                    return STATE_HDR_NAME;
                                }
                            } while (buffer.hasRemaining());
                            buffer.clear();
                        }
                    }
                    // fall thru
                }
                case STATE_HDR_D: {
                    log.tracef("%s.processWrite: writing at hdr d", this);
                    if (! buffer.hasRemaining()) {
                        buffer.flip();
                        do {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.tracef("%s.processWrite: continuation at hdr d", this);
                                return STATE_HDR_D;
                            }
                        } while (buffer.hasRemaining());
                        buffer.clear();
                    }
                    buffer.put((byte) ':');
                    // fall thru
                }
                case STATE_HDR_DS: {
                    log.tracef("%s.processWrite: writing at hdr_ds ", this);
                    if (! buffer.hasRemaining()) {
                        buffer.flip();
                        do {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.trace("Continuation");
                                log.tracef("%s.processWrite: continmuation at hdr ds", this);
                                return STATE_HDR_DS;
                            }
                        } while (buffer.hasRemaining());
                        buffer.clear();
                    }
                    buffer.put((byte) ' ');
                    if(valueIterator == null) {
                        valueIterator = request.getRequestHeaders().get(headerName).iterator();
                    }
                    assert valueIterator.hasNext();
                    string = valueIterator.next();
                    charIndex = 0;
                    // fall thru
                }
                case STATE_HDR_VAL: {
                    log.tracef("Processing header value '%s'", string);
                    log.tracef("%s.processWrite: state is hdr_val", this);
                    length = string.length();
                    while (charIndex < length) {
                        if (buffer.hasRemaining()) {
                            buffer.put((byte) string.charAt(charIndex++));
                        } else {
                            buffer.flip();
                            do {
                                res = next.write(buffer);
                                if (res == 0) {
                                    log.tracef("%s.processWrite: continuation at hdr_val", this);
                                    return STATE_HDR_VAL;
                                }
                            } while (buffer.hasRemaining());
                            buffer.clear();
                        }
                    }
                    charIndex = 0;
                    if (! valueIterator.hasNext()) {
                        log.tracef("%s.processWrite: done writing header value, moving to eol", this);
                        if (! buffer.hasRemaining()) {
                            buffer.flip();
                            do {
                                res = next.write(buffer);
                                if (res == 0) {
                                    log.tracef("%s.processWrite: continuation at hdr_eol_cr", this);
                                    return STATE_HDR_EOL_CR;
                                }
                            } while (buffer.hasRemaining());
                            buffer.clear();
                        }
                        buffer.put((byte) 13); // CR
                        if (! buffer.hasRemaining()) {
                            buffer.flip();
                            do {
                                res = next.write(buffer);
                                if (res == 0) {
                                    log.tracef("%s.processWrite: continuation at hdr_eol_lf", this);
                                    return STATE_HDR_EOL_LF;
                                }
                            } while (buffer.hasRemaining());
                            buffer.clear();
                        }
                        buffer.put((byte) 10); // LF
                        if (nameIterator.hasNext()) {
                            headerName = nameIterator.next();
                            valueIterator = null;
                            state = STATE_HDR_NAME;
                            log.tracef("%s.processWrite: changing state from hdr_val to hdr_name", this);
                            break;
                        } else {
                            log.tracef("%s.processWrite: changing state at hdr_val, we are done writing headers", this);
                            if (! buffer.hasRemaining()) {
                                buffer.flip();
                                do {
                                    res = next.write(buffer);
                                    if (res == 0) {
                                        log.tracef("%s.processWrite: continuation at hdr_final_cr", this);
                                        return STATE_HDR_FINAL_CR;
                                    }
                                } while (buffer.hasRemaining());
                                buffer.clear();
                            }
                            buffer.put((byte) 13); // CR
                            if (! buffer.hasRemaining()) {
                                buffer.flip();
                                do {
                                    res = next.write(buffer);
                                    if (res == 0) {
                                        log.trace("Continuation");
                                        log.tracef("%s.processWrite: continuation at hdr_final_lf", this);
                                        return STATE_HDR_FINAL_LF;
                                    }
                                } while (buffer.hasRemaining());
                                buffer.clear();
                            }
                            buffer.put((byte) 10); // LF
                            this.nameIterator = null;
                            this.valueIterator = null;
                            this.string = null;
                            buffer.flip();
                            //for performance reasons we use a gather write if there is user data
                            if(userData == null) {
                                log.tracef("%s.processWrite: writing buffer after header", this);
                                do {
                                    res = next.write(buffer);
                                    if (res == 0) {
                                        log.trace("Continuation");
                                        log.tracef("%s.processWrite: continuation at buf_flush", this);
                                        return STATE_BUF_FLUSH;
                                    }
                                } while (buffer.hasRemaining());
                            } else {
                                log.tracef("%s.processWrite: at hdr_val, writing userData after header", this);
                                ByteBuffer[] b = {buffer, userData};
                                do {
                                    long r = next.write(b, 0, b.length);
                                    if (r == 0 && buffer.hasRemaining()) {
                                        log.tracef("%s.processWrite: continuation at bluf_flush", this);
                                        return STATE_BUF_FLUSH;
                                    }
                                } while (buffer.hasRemaining());
                            }
                            if (pooledBuffer != null) {
                                pooledBuffer.close();
                                pooledBuffer = null;
                            } else
                                log.tracef("%s.processWrite: WARNING: pooledBuffer was null!", this);
                            log.tracef("%s.processWrite: returning state body", this);
                            return STATE_BODY;
                        }
                        // not reached
                    }
                    // fall thru
                }
                // Clean-up states
                case STATE_HDR_EOL_CR: {
                    log.tracef("%s.processWrite: at hdr_eol_cr", this);
                    if (! buffer.hasRemaining()) {
                        buffer.flip();
                        do {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.tracef("%s.processWrite: continuation at hdr_eol_cr", this);
                                return STATE_HDR_EOL_CR;
                            }
                        } while (buffer.hasRemaining());
                        buffer.clear();
                    }
                    buffer.put((byte) 13); // CR
                }
                case STATE_HDR_EOL_LF: {
                    log.tracef("%s.processWrite: at hdr_eol_lf state", this);
                    if (! buffer.hasRemaining()) {
                        buffer.flip();
                        do {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.tracef("%s.processWrite: continuation at hdr_eol_lf", this);
                                return STATE_HDR_EOL_LF;
                            }
                        } while (buffer.hasRemaining());
                        buffer.clear();
                    }
                    buffer.put((byte) 10); // LF
                    if(valueIterator != null && valueIterator.hasNext()) {
                        log.tracef("%s.processWrite: moving from hdr_eol_lf to hdr_name state because there are more values", this);
                        state = STATE_HDR_NAME;
                        break;
                    } else if (nameIterator.hasNext()) {
                        headerName = nameIterator.next();
                        valueIterator = null;
                        log.tracef("%s.processWrite: moving from hdr_eol_lf to hdr_name state because there are more names", this);
                        state = STATE_HDR_NAME;
                        break;
                    }
                    // fall thru
                }
                case STATE_HDR_FINAL_CR: {
                    log.tracef("%s.processWrite: at hdr_final_cr state", this);
                    if (! buffer.hasRemaining()) {
                        buffer.flip();
                        do {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.tracef("%s.processWrite: continuation at hdr_final_cr", this);
                                return STATE_HDR_FINAL_CR;
                            }
                        } while (buffer.hasRemaining());
                        buffer.clear();
                    }
                    buffer.put((byte) 13); // CR
                    // fall thru
                }
                case STATE_HDR_FINAL_LF: {
                    log.tracef("%s.processWrite: at hdr_final_lf state", this);
                    if (! buffer.hasRemaining()) {
                        buffer.flip();
                        do {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.trace("Continuation");
                                log.tracef("%s.processWrite: continuation at hdr_final_lf", this);
                                return STATE_HDR_FINAL_LF;
                            }
                        } while (buffer.hasRemaining());
                        buffer.clear();
                    }
                    buffer.put((byte) 10); // LF
                    this.nameIterator = null;
                    this.valueIterator = null;
                    this.string = null;
                    buffer.flip();
                    //for performance reasons we use a gather write if there is user data
                    if(userData == null) {
                        log.tracef("%s.processWrite: no user data, just write the buffer", this);
                        do {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.trace("Continuation");
                                log.tracef("%s.processWrite: continuation at buf_flush", this);
                                return STATE_BUF_FLUSH;
                            }
                        } while (buffer.hasRemaining());
                    } else {
                        log.tracef("%s.processWrite: writing userData", this);
                        ByteBuffer[] b = {buffer, userData};
                        do {
                            long r = next.write(b, 0, b.length);
                            if (r == 0 && buffer.hasRemaining()) {
                                log.tracef("%s.processWrite: continuation at buf_flush", this);
                                return STATE_BUF_FLUSH;
                            }
                        } while (buffer.hasRemaining());
                    }
                    // fall thru
                }
                case STATE_BUF_FLUSH: {
                    log.tracef("%s.processWrite: at buf_flush state, returning state_body", this);
                    // buffer was successfully flushed above
                    pooledBuffer.close();
                    pooledBuffer = null;
                    return STATE_BODY;
                }
                case STATE_URL: {
                    log.tracef("%s.processWrite: at url state", this);
                    for(int i = charIndex; i < string.length(); ++i) {
                        if(!buffer.hasRemaining()) {
                            buffer.flip();
                            do {
                                res = next.write(buffer);
                                if (res == 0) {
                                    log.tracef("%s.processWrite: coitnuation at url", this);
                                    this.charIndex = i;
                                    this.state = STATE_URL;
                                    return STATE_URL;
                                }
                            } while (buffer.hasRemaining());
                            buffer.clear();
                        }
                        buffer.put((byte) string.charAt(i));
                    }

                    HeaderMap headers = request.getRequestHeaders();
                    nameIterator = headers.getHeaderNames().iterator();
                    state = STATE_HDR_NAME;
                    log.tracef("%s.processWrite: moving from url to hdr_name state", this);
                    if (! nameIterator.hasNext()) {
                        log.tracef("%s.processWrite: no request headers", this);
                        buffer.put((byte) '\r').put((byte) '\n');
                        buffer.flip();
                        while (buffer.hasRemaining()) {
                            res = next.write(buffer);
                            if (res == 0) {
                                log.trace("Continuation");
                                log.tracef("%s.processWrite: continuation at buf_flush", this);
                                return STATE_BUF_FLUSH;
                            }
                        }
                        pooledBuffer.close();
                        pooledBuffer = null;
                        log.tracef("%s.processWrite: returning state body", this);
                        return STATE_BODY;
                    }
                    headerName = nameIterator.next();
                    charIndex = 0;
                    break;
                }
                default: {
                    throw new IllegalStateException();
                }
            }
        }
    }

    public int write(final ByteBuffer src) throws IOException {
        log.trace("write");
        int oldState;
        do {
            oldState = state;
            if (anyAreSet(oldState, FLAG_WRITING)) {
                return 0;
            }
        } while (!stateUpdater.compareAndSet(this, oldState, oldState | FLAG_WRITING));
        int state = oldState & MASK_STATE;
        int alreadyWritten = 0;
        int originalRemaining = - 1;
        try {
            if (state != 0) {
                originalRemaining = src.remaining();
                state = processWrite(state, src);
                if (state != 0) {
                    return 0;
                }
                alreadyWritten = originalRemaining - src.remaining();
                if (allAreSet(oldState, FLAG_SHUTDOWN)) {
                    next.terminateWrites();
                    throw new ClosedChannelException();
                }
            }
            if(alreadyWritten != originalRemaining) {
                return next.write(src) + alreadyWritten;
            }
            return alreadyWritten;
        } catch (IOException | RuntimeException | Error e) {
            this.state |= FLAG_SHUTDOWN;
            if(pooledBuffer != null) {
                pooledBuffer.close();
                pooledBuffer = null;
            }
            throw e;
        } finally {
            this.state = oldState & ~MASK_STATE | state;
        }
    }

    public long write(final ByteBuffer[] srcs) throws IOException {
        return write(srcs, 0, srcs.length);
    }

    public long write(final ByteBuffer[] srcs, final int offset, final int length) throws IOException {
        log.trace("write");
        if (length == 0) {
            return 0L;
        }
        int oldVal;
        do {
            oldVal = state;
            if (anyAreSet(oldVal, FLAG_WRITING)) {
                return 0;
            }
        } while (!stateUpdater.compareAndSet(this, oldVal, oldVal | FLAG_WRITING));
        int state = oldVal & MASK_STATE;
        try {
            if (state != 0) {
                //todo: use gathering write here
                state = processWrite(state, null);
                if (state != 0) {
                    return 0;
                }
                if (allAreSet(oldVal, FLAG_SHUTDOWN)) {
                    next.terminateWrites();
                    throw new ClosedChannelException();
                }
            }
            return length == 1 ? next.write(srcs[offset]) : next.write(srcs, offset, length);
        } catch (IOException | RuntimeException | Error e) {
            this.state |= FLAG_SHUTDOWN;
            if(pooledBuffer != null) {
                pooledBuffer.close();
                pooledBuffer = null;
            }
            throw e;
        } finally {
            this.state = oldVal & ~MASK_STATE | state;
        }
    }

    @Override
    public int writeFinal(ByteBuffer src) throws IOException {
        return Conduits.writeFinalBasic(this, src);
    }

    @Override
    public long writeFinal(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return Conduits.writeFinalBasic(this, srcs, offset, length);
    }

    public long transferFrom(final FileChannel src, final long position, final long count) throws IOException {
        log.trace("transfer");
        if (count == 0L) {
            return 0L;
        }
        int oldVal;
        do {
            oldVal = state;
            if (anyAreSet(oldVal, FLAG_WRITING)) {
                return 0;
            }
        } while (!stateUpdater.compareAndSet(this, oldVal, oldVal | FLAG_WRITING));
        int state = oldVal & MASK_STATE;
        try {
            if (state != 0) {
                state = processWrite(state, null);
                if (state != 0) {
                    return 0;
                }
                if (allAreSet(oldVal, FLAG_SHUTDOWN)) {
                    next.terminateWrites();
                    throw new ClosedChannelException();
                }
            }
            return next.transferFrom(src, position, count);
        } catch (IOException | RuntimeException | Error e) {
            this.state |= FLAG_SHUTDOWN;
            if(pooledBuffer != null) {
                pooledBuffer.close();
                pooledBuffer = null;
            }
            throw e;
        } finally {
            this.state = oldVal & ~MASK_STATE | state;
        }
    }

    public long transferFrom(final StreamSourceChannel source, final long count, final ByteBuffer throughBuffer) throws IOException {
        log.trace("transfer");
        if (count == 0) {
            throughBuffer.clear().limit(0);
            return 0L;
        }
        int oldVal;
        do {
            oldVal = state;
            if (anyAreSet(oldVal, FLAG_WRITING)) {
                return 0;
            }
        } while (!stateUpdater.compareAndSet(this, oldVal, oldVal | FLAG_WRITING));
        int state = oldVal & MASK_STATE;
        try {
            if (state != 0) {
                state = processWrite(state, null);
                if (state != 0) {
                    return 0;
                }
                if (allAreSet(oldVal, FLAG_SHUTDOWN)) {
                    next.terminateWrites();
                    throw new ClosedChannelException();
                }
            }
            return next.transferFrom(source, count, throughBuffer);
        } catch (IOException | RuntimeException | Error e) {
            this.state |= FLAG_SHUTDOWN;
            if(pooledBuffer != null) {
                pooledBuffer.close();
                pooledBuffer = null;
            }
            throw e;
        } finally {
            this.state = oldVal & ~MASK_STATE | state;
        }
    }

    public boolean flush() throws IOException {

        log.trace("flush");
        int oldVal;
        do {
            oldVal = state;
            if (anyAreSet(oldVal, FLAG_WRITING)) {
                return false;
            }
        } while (!stateUpdater.compareAndSet(this, oldVal, oldVal | FLAG_WRITING));
        int state = oldVal & MASK_STATE;
        try {
            if (state != 0) {
                state = processWrite(state, null);
                if (state != 0) {
                    log.trace("Flush false because headers aren't written yet");
                    return false;
                }
                if (allAreSet(oldVal, FLAG_SHUTDOWN)) {
                    next.terminateWrites();
                    // fall out to the flush
                }
            }
            log.trace("Delegating flush");
            return next.flush();
        } catch (IOException | RuntimeException | Error e) {
            this.state |= FLAG_SHUTDOWN;
            if(pooledBuffer != null) {
                pooledBuffer.close();
                pooledBuffer = null;
            }
            throw e;
        } finally {
            this.state = oldVal & ~MASK_STATE | state;
        }
    }


    public void terminateWrites() throws IOException {
        log.trace("shutdown");
        int oldVal = this.state;
        if (allAreClear(oldVal, MASK_STATE)) {
            next.terminateWrites();
            return;
        }
        this.state = oldVal | FLAG_SHUTDOWN;
    }

    public void truncateWrites() throws IOException {
        log.trace("close");
        int oldVal = this.state;
        if (allAreClear(oldVal, MASK_STATE)) {
            try {
                next.truncateWrites();
            } finally {
                if (pooledBuffer != null) {
                    pooledBuffer.close();
                    pooledBuffer = null;
                }
            }
            return;
        }
        this.state = oldVal & ~MASK_STATE | FLAG_SHUTDOWN;
        throw new TruncatedResponseException();
    }

    public XnioWorker getWorker() {
        return next.getWorker();
    }

    public void freeBuffers() {
        if(pooledBuffer != null) {
            pooledBuffer.close();
            pooledBuffer = null;
            this.state = state & ~MASK_STATE | FLAG_SHUTDOWN;
        }
    }
}
