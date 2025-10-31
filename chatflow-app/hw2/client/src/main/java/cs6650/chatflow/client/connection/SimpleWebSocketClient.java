package cs6650.chatflow.client.connection;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;

/**
 * Simple WebSocket client for sending DELIVERY_ACK messages.
 * Minimal implementation focused on sending messages without complex response handling.
 */
public class SimpleWebSocketClient extends org.java_websocket.client.WebSocketClient {

    private final String roomId;

    /**
     * Creates WebSocket client for a specific room.
     * @param serverUri WebSocket server URI
     * @param roomId room identifier for logging purposes
     */
    public SimpleWebSocketClient(java.net.URI serverUri, String roomId) {
        super(serverUri);
        this.roomId = roomId;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("ACK WebSocket opened for " + roomId);
    }

    @Override
    public void onMessage(String message) {
        // ACK connections typically don't receive messages, but log if they do
        System.out.println("ACK WebSocket received message for " + roomId + ": " + message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("ACK WebSocket closed for " + roomId + ": " + reason);
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("ACK WebSocket error for " + roomId + ": " + ex.getMessage());
    }

    @Override
    public void onWebsocketPing(WebSocket conn, Framedata f) {
        super.onWebsocketPing(conn, f);
    }
}
