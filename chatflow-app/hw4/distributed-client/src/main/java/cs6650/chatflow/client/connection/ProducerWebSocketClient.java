package cs6650.chatflow.client.connection;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * WebSocket client for producer server connections.
 * Used for sending all message types (JOIN, TEXT, LEAVE, ACK) to the producer server.
 */
public class ProducerWebSocketClient extends WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(ProducerWebSocketClient.class);

    private final String roomId;

    public ProducerWebSocketClient(URI serverUri, String roomId) {
        super(serverUri);
        this.roomId = roomId;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        logger.debug("Producer connection opened for room: {}", roomId);
    }

    @Override
    public void onMessage(String message) {
        // Producer may send responses - just log at debug level
        logger.debug("Producer connection received message for room {}: {}", roomId, message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        String initiator = remote ? "server" : "client";
        logger.info("Producer connection closed for room {} (code: {}, reason: {}, initiated by: {})",
                roomId, code, reason, initiator);
    }

    @Override
    public void onError(Exception ex) {
        logger.error("Producer connection error for room {}: {}", roomId, ex.getMessage(), ex);
    }

    @Override
    public void onWebsocketPing(WebSocket conn, Framedata f) {
        super.onWebsocketPing(conn, f);
    }

    public String getRoomId() {
        return roomId;
    }
}
