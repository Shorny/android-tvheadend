/*
 * Copyright (c) 2016 Kiall Mac Innes <kiall@macinnes.ie>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package ie.macinnes.htsp;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class Connection implements Runnable {
    private static final String TAG = Connection.class.getName();

    public final static int STATE_CONNECTING = 0;
    public final static int STATE_CONNECTED = 1;
    public final static int STATE_CLOSING = 2;
    public final static int STATE_CLOSED = 3;
    public final static int STATE_FAILED = 3;

    protected SocketChannel mSocketChannel;
    protected Selector mSelector;

    protected ByteBuffer mReadBuffer;

    protected Queue<HtspMessage> mMessageQueue;

    protected boolean mRunning = false;
    protected int mState = STATE_CLOSED;

    protected List<IConnectionListener> mHTSPConnectionListeners = new ArrayList<>();
    protected List<IMessageListener> mMessageListeners = new ArrayList<>();

    public Connection() {
        // TODO: What size buffers?
        mReadBuffer = ByteBuffer.allocate(10485760); // 10 MB

        mMessageQueue = new LinkedList<HtspMessage>();
    }

    public void addConnectionListener(IConnectionListener listener) {
        if (mHTSPConnectionListeners.contains(listener)) {
            Log.w(TAG, "Attempted to add duplicate connection listener");
            return;
        }
        mHTSPConnectionListeners.add(listener);
    }

    public void addMessageListener(IMessageListener listener) {
        if (mMessageListeners.contains(listener)) {
            Log.w(TAG, "Attempted to add duplicate message listener");
            return;
        }
        mMessageListeners.add(listener);
    }

    @Override
    public void run() {
        try {
            open();
        } catch (IOException e) {
            Log.e(TAG, "Failed to open HTSP connection", e);
            return;
        }

        while (mRunning) {
            try {
                mSelector.select();
            } catch (IOException e) {
                Log.e(TAG, "Failed to select from socket channel", e);
                mRunning = false;
                setState(STATE_FAILED);
                break;
            }

            if (!mSelector.isOpen()) {
                break;
            }

            Set<SelectionKey> keys = mSelector.selectedKeys();
            Iterator<SelectionKey> i = keys.iterator();

            try {
                while (i.hasNext()) {
                    SelectionKey selectionKey = i.next();
                    i.remove();

                    if (!selectionKey.isValid()) {
                        continue;
                    }

                    if (selectionKey.isConnectable()) {
                        processConnectableSelectionKey();
                    }

                    if (selectionKey.isReadable()) {
                        processReadableSelectionKey();
                    }

                    if (selectionKey.isWritable()) {
                        processWritableSelectionKey();
                    }
                }

                if (mMessageQueue.isEmpty()) {
                    mSocketChannel.register(mSelector, SelectionKey.OP_READ);
                } else {
                    mSocketChannel.register(mSelector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Something failed - shutting down", e);
                mRunning = false;
            }
        }

        close();
    }

    public void open() throws IOException {
        Log.i(TAG, "Opening HTSP Connection");
        setState(STATE_CONNECTING);

        final Object openLock = new Object();

        try {
            mSocketChannel = SocketChannel.open();
            mSocketChannel.connect(new InetSocketAddress("10.5.1.22", 9982));
            mSelector = Selector.open();
            mSocketChannel.configureBlocking(false);
            mSocketChannel.register(mSelector, SelectionKey.OP_CONNECT, openLock);

            mRunning = true;
        } catch (Exception e) {
            Log.e(TAG, "DERPD A! ", e);
            e.printStackTrace();
        }

        if (!mRunning) {
            setState(STATE_FAILED);
            return;
        }

        synchronized (openLock) {
            try {
                openLock.wait(2000);
                if (mSocketChannel.isConnectionPending()) {
                    Log.w(TAG, "Timeout while connecting, derp");
                    close();
                    return;
                }
            } catch (InterruptedException ex) {
                Log.w(TAG, "Derp? " + ex.getLocalizedMessage());
                close();
                return;
            }
        }

        Log.i(TAG, "HTSP Connected");
        setState(STATE_CONNECTED);
    }

    public void close() {
        Log.i(TAG, "Closing HTSP Connection");

        mRunning = false;
        setState(STATE_CLOSING);

        if (mSocketChannel != null) {
            try {
                Log.w(TAG, "Calling SocketChannel close");
                mSocketChannel.socket().close();
                mSocketChannel.close();
                mSelector.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close socket channel: " + e.getLocalizedMessage());
            }
        }

        setState(STATE_CLOSED);
    }

    public void sendMessage(HtspMessage htspMessage) {
        Log.i(TAG, "Sending HtspMessage: " + htspMessage.toString());

        mMessageQueue.add(htspMessage);

        try {
            mSocketChannel.register(mSelector, SelectionKey.OP_WRITE | SelectionKey.OP_READ | SelectionKey.OP_CONNECT);
            mSelector.wakeup();
        } catch (ClosedChannelException e) {
            Log.w(TAG, "Failed to send message: " + e.getLocalizedMessage());
            e.printStackTrace();
        }
    }

    public void sendMessage(BaseMessage message) {
        sendMessage(message.toHtspMessage());
    }

    public int getState() {
        return mState;
    }

    protected void setState(int state) {
        int previousState = mState;
        mState = state;

        if (mHTSPConnectionListeners != null) {
            for (IConnectionListener listener : mHTSPConnectionListeners) {
                listener.onStateChange(state, previousState);
            }
        }
    }

    private void processConnectableSelectionKey() throws IOException {
        Log.v(TAG, "processConnectableSelectionKey()");

        if (mSocketChannel.isConnectionPending()) {
            mSocketChannel.finishConnect();
        }

        mSocketChannel.register(mSelector, SelectionKey.OP_READ);
    }

    private void processReadableSelectionKey() throws IOException {
        Log.v(TAG, "processReadableSelectionKey()");

        int bufferStartPosition = mReadBuffer.position();
        int bytesRead = this.mSocketChannel.read(mReadBuffer);

        Log.v(TAG, "Read " + bytesRead + " bytes.");

        int bytesToBeConsumed = bufferStartPosition + bytesRead;

        if (bytesRead == -1) {
            close();
        } else if (bytesRead > 0) {
            int bytesConsumed = -1;

            while (bytesConsumed != 0 && bytesToBeConsumed > 0) {
                bytesConsumed = processMessage(bytesToBeConsumed);
                bytesToBeConsumed = bytesToBeConsumed - bytesConsumed;
            }
        }
    }

    private int processMessage(int bytesToBeConsumed) throws IOException {
        Log.v(TAG, "Processing a HTSP Message");

        ResponseMessage message = HtspMessage.fromWire(mReadBuffer);
        int bytesConsumed = mReadBuffer.position();

        if (message == null) {
            return 0;
        }

        // Reset the buffers limit to the full amount of data read
        mReadBuffer.limit(bytesToBeConsumed);

        // Compact the buffer, discarding all previously read data, and ensuring we don't
        // loose any bytes already read for the next message.
        mReadBuffer.compact();

        if (mMessageListeners != null) {
            for (IMessageListener listener : mMessageListeners) {
                listener.onMessage((ResponseMessage) message);
            }
        } else {
            Log.w(TAG, "Message received, but no listeners.. Discarding.");
        }

        return bytesConsumed;
    }

    private void processWritableSelectionKey() throws IOException {
        Log.v(TAG, "processWritableSelectionKey()");
        HtspMessage htspMessage = mMessageQueue.poll();

        if (htspMessage != null) {
            mSocketChannel.write(htspMessage.toWire());
        }
    }
}