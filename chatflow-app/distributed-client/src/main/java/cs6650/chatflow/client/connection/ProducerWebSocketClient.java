package cs6650.chatflow.client.connection;

import cs6650.chatflow.client.model.ChatMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.function.Consumer;

/**
 * WebSocket client for producer server connections.
 * Used for sending all message types (JOIN, TEXT, LEAVE, ACK) to the producer server.
 *
 * NEW: Receives ACK_CONFIRMATION messages directly from producer for optimized completion tracking.
 */
public class ProducerWebSocketClient extends WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(ProducerWebSocketClient.class);
    private static final Gson gson = new GsonBuilder().create();

    private final String roomId;
    private final Consumer<ChatMessage> ackConfirmationHandler;  // NEW: Handler for ACK confirmations

    /**
     * Creates a WebSocket client for producer server connection.
     *
     * @param serverUri WebSocket server URI
     * @param roomId room identifier for logging and tracking
     * @param ackConfirmationHandler callback for handling ACK_CONFIRMATION messages
     */
    public ProducerWebSocketClient(URI serverUri, String roomId, Consumer<ChatMessage> ackConfirmationHandler) {
        super(serverUri);
        this.roomId = roomId;
        this.ackConfirmationHandler = ackConfirmationHandler;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        logger.debug("Producer connection opened for room: {}", roomId);
    }

    @Override
    public void onMessage(String message) {
        // ========== NEW: Handle ACK_CONFIRMATION messages from producer ==========
        try {
            ChatMessage chatMessage = gson.fromJson(message, ChatMessage.class);

            if ("ACK_CONFIRMATION".equals(chatMessage.getMessageType())) {
                // This is an ACK confirmation from the producer!
                logger.debug("ACK confirmation received for room {}: originalMessageId={}",
                        roomId, chatMessage.getOriginalMessageId());

                if (ackConfirmationHandler != null) {
                    ackConfirmationHandler.accept(chatMessage);
                }
            } else {
                // Unexpected message type on producer connection
                logger.debug("Producer connection received unexpected message type '{}' for room {}",
                        chatMessage.getMessageType(), roomId);
            }

        } catch (Exception e) {
            logger.debug("Producer connection received unparseable message for room {}: {}",
                    roomId, e.getMessage());
        }
        // ========================================================================
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

    /**
     * Get the room ID associated with this connection.
     */
    public String getRoomId() {
        return roomId;
    }
}
