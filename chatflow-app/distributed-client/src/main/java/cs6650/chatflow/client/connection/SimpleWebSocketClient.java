package cs6650.chatflow.client.connection;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic WebSocket client for producer server connections.
 * Used for sending all message types (JOIN, TEXT, LEAVE, ACK) to the producer server.
 *
 * This is a lightweight wrapper around the Java-WebSocket library client
 * with proper logging and minimal overhead.
 */
public class SimpleWebSocketClient extends org.java_websocket.client.WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(SimpleWebSocketClient.class);

    private final String roomId;

    /**
     * Creates WebSocket client for a specific room.
     * @param serverUri WebSocket server URI (e.g., ws://localhost:8080/chatflow-server/chat/room1)
     * @param roomId room identifier for logging and tracking purposes
     */
    public SimpleWebSocketClient(java.net.URI serverUri, String roomId) {
        super(serverUri);
        this.roomId = roomId;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        logger.debug("WebSocket connection opened for room: {}", roomId);
    }

    @Override
    public void onMessage(String message) {
        // Producer connections are primarily for sending, but may receive server responses
        // Log at debug level since this is uncommon in normal operation
        logger.debug("WebSocket received message for room {}: {}", roomId, message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        // Connection closures are important to track
        String initiator = remote ? "server" : "client";
        logger.info("WebSocket connection closed for room {} (code: {}, reason: {}, initiated by: {})",
                roomId, code, reason, initiator);
    }

    @Override
    public void onError(Exception ex) {
        // Errors are always important
        logger.error("WebSocket error for room {}: {}", roomId, ex.getMessage(), ex);
    }

    @Override
    public void onWebsocketPing(WebSocket conn, Framedata f) {
        // Respond to pings automatically (default behavior)
        super.onWebsocketPing(conn, f);
    }

    /**
     * Get the room ID associated with this connection.
     * @return room identifier (e.g., "room1", "room2", etc.)
     */
    public String getRoomId() {
        return roomId;
    }
}
