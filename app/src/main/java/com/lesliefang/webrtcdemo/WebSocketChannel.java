package com.lesliefang.webrtcdemo;

import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Created by fanglin on 2020/10/18.
 */
public class WebSocketChannel {
    private static final String TAG = WebSocketChannel.class.getSimpleName();
    private WebSocket webSocket;
    private boolean connected;
    private WebSocketCallback webSocketCallback;

    public void connect(String url) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .build();
        webSocket = client.newWebSocket(request, new WebSocketHandler());
    }

    public boolean isConnected() {
        return connected;
    }

    public void sendMessage(String message) {
        if (webSocket != null && connected) {
            Log.d(TAG, "send==>>" + message);
            webSocket.send(message);
        } else {
            Log.e(TAG, "send failed socket not connected");
        }
    }

    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "manual close");
            webSocket = null;
        }
    }

    private class WebSocketHandler extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            connected = true;
            Log.d(TAG, "onOpen");
            if (webSocketCallback != null) {
                webSocketCallback.onOpen();
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.d(TAG, "onMessage <<==" + text);
            if (webSocketCallback != null) {
                webSocketCallback.onMessage(text);
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "onClosed " + reason);
            connected = false;
            if (webSocketCallback != null) {
                webSocketCallback.onClosed();
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.d(TAG, "onFailure " + t.getMessage());
            connected = false;
            if (webSocketCallback != null) {
                webSocketCallback.onClosed();
            }
        }
    }

    public void setWebSocketCallback(WebSocketCallback webSocketCallback) {
        this.webSocketCallback = webSocketCallback;
    }

    public interface WebSocketCallback {
        void onOpen();

        void onMessage(String text);

        void onClosed();
    }
}

