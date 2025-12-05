package cs6650.chatflow.client.connection;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.function.BiConsumer;

/**
 * WebSocket client for consumer server connections.
 * Used exclusively for receiving messages broadcast by the consumer server.
 *
 * This client is read-only - it never sends messages back to the consumer server.
 * All received messages are delegated to a message handler callback for processing.
 */
public class ConsumerWebSocketClient extends WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerWebSocketClient.class);

    private final String roomId;
    private final BiConsumer<String, String> messageHandler;

    /**
     * Creates a WebSocket client for consumer server connection.
     *
     * @param serverUri WebSocket server URI (e.g., ws://localhost:8081/consumer-server/chatflow-receiver/room1)
     * @param roomId room identifier for logging and tracking purposes
     * @param messageHandler callback function to handle received messages (messageJson, roomId)
     */
    public ConsumerWebSocketClient(URI serverUri, String roomId, BiConsumer<String, String> messageHandler) {
        super(serverUri);
        this.roomId = roomId;
        this.messageHandler = messageHandler;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        logger.debug("Consumer connection opened for room: {}", roomId);
    }

    @Override
    public void onMessage(String message) {
        // This is the primary function - receive and delegate to handler
        logger.debug("Consumer connection received message for room: {}", roomId);

        try {
            // Delegate to message handler (typically ReceiverConnectionPool.handleMessage)
            if (messageHandler != null) {
                messageHandler.accept(message, roomId);
            }
        } catch (Exception e) {
            logger.error("Error in message handler for room {}: {}", roomId, e.getMessage(), e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        String initiator = remote ? "server" : "client";
        logger.info("Consumer connection closed for room {} (code: {}, reason: {}, initiated by: {})",
                roomId, code, reason, initiator);
    }

    @Override
    public void onError(Exception ex) {
        logger.error("Consumer connection error for room {}: {}", roomId, ex.getMessage(), ex);
    }

    @Override
    public void onWebsocketPing(WebSocket conn, Framedata f) {
        // Respond to pings automatically (default behavior)
        super.onWebsocketPing(conn, f);
    }

    /**
     * Get the room ID associated with this connection.
     *
     * @return room identifier (e.g., "room1", "room2", etc.)
     */
    public String getRoomId() {
        return roomId;
    }
}
