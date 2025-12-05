package cs6650.chatflow.server.handler.websocket;

import cs6650.chatflow.server.model.ChatCommand;
import cs6650.chatflow.server.model.AckConfirmation;
import cs6650.chatflow.server.util.ValidationUtils;
import cs6650.chatflow.server.commons.Constants;
import cs6650.chatflow.server.messaging.MessagePublisherManager;
import cs6650.chatflow.server.messaging.MessagePublisher;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.*;

import static cs6650.chatflow.server.commons.Constants.HEARTBEAT_INTERVAL_SECONDS;

/**
 * WebSocket endpoint handling chat commands and sending chat event responses.
 * Implements heartbeat mechanism to keep connections alive with periodic ping frames.
 *
 * Optimized ACK handling: ACK messages receive immediate confirmation back to the client,
 * avoiding unnecessary round-trip through the consumer server.
 */
@ServerEndpoint(Constants.CHAT_ROOM_PATH)
public class ChatWebSocketEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketEndpoint.class);
    private static final Gson gson = new Gson();

    @OnOpen
    public void onOpen(Session session, @PathParam("roomId") String roomId) {
        // Start heartbeat scheduler for this session
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "Heartbeat-" + session.getId()));

        // Store scheduler in session properties for cleanup
        session.getUserProperties().put("heartbeatScheduler", heartbeatScheduler);

        // Schedule periodic ping sending
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                if (session.isOpen()) {
                    session.getBasicRemote().sendPing(ByteBuffer.wrap("ping".getBytes()));
                    logger.debug("Ping sent to session {}", session.getId());
                }
            } catch (Exception e) {
                logger.debug("Failed to send ping to session {}: {}", session.getId(), e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        logger.info("WebSocket connected - session: {}, room: {}",
                session.getId(), roomId);
    }

    @OnMessage
    public void onMessage(Session session, String msgJson, @PathParam("roomId") String roomId) {
        try {
            ChatCommand command = gson.fromJson(msgJson, ChatCommand.class);

            String validationError = ValidationUtils.validate(command);
            if (validationError != null) {
                logger.warn("Validation failed - session: {}, room: {}, error: {}",
                        session.getId(), roomId, validationError);
                sendTextSafe(session, "{\"error\":\"" + validationError + "\"}");
                return;
            }

            // ========== NEW: Handle ACK messages directly ==========
            if (Constants.MESSAGE_TYPE_ACK.equals(command.getMessageType())) {
                handleAckMessage(session, command, roomId);
                return;
            }
            // =======================================================

            // Regular messages (JOIN/TEXT/LEAVE) - publish to RabbitMQ
            MessagePublisher publisher = MessagePublisherManager.getInstance();
            publisher.publishMessage(command, roomId, session);

        } catch (JsonSyntaxException e) {
            logger.error("Invalid JSON - session: {}, message: {}, error: {}", session.getId(), msgJson, e.getMessage());
            sendTextSafe(session, "{\"error\":\"" + Constants.ERROR_INVALID_JSON + "\"}");
        } catch (Exception ex) {
            logger.error("Processing error - session: {}, message: {}, error: {}", session.getId(), msgJson, ex.getMessage(), ex);
            sendTextSafe(session, "{\"error\":\"" + Constants.ERROR_INTERNAL_SERVER + "\"}");
        }
    }

    /**
     * Handles ACK messages by sending immediate confirmation back to client.
     * Optionally publishes ACK to queue for logging/persistence.
     */
    private void handleAckMessage(Session session, ChatCommand ackCommand, String roomId) {
        try {
            // Extract original message ID from ACK message
            // ACK message format: "DELIVERY_ACK:original-message-id"
            String originalMessageId = extractOriginalMessageId(ackCommand);

            // Create ACK confirmation to send back to client
            AckConfirmation confirmation = new AckConfirmation();
            confirmation.setMessageId(java.util.UUID.randomUUID().toString()); // New ID for confirmation
            confirmation.setOriginalMessageId(originalMessageId);
            confirmation.setAckMessageId(ackCommand.getMessageId());
            confirmation.setUserId(ackCommand.getUserId());
            confirmation.setUsername(ackCommand.getUsername());
            confirmation.setMessage("ACK_CONFIRMED:" + originalMessageId);
            confirmation.setServerTimestamp(Instant.now().toString());
            confirmation.setTimestamp(Instant.now().toString());

            // Send confirmation DIRECTLY back to client (no queue round-trip!)
            String confirmationJson = gson.toJson(confirmation);
            sendTextSafe(session, confirmationJson);

            logger.debug("ACK confirmation sent for message {} to session {}", originalMessageId, session.getId());

            // ========== OPTIONAL: Still publish ACK to queue for logging/metrics ==========
            // Uncomment if you want to keep ACKs in the message queue for persistence
            /*
            MessagePublisher publisher = MessagePublisherManager.getInstance();
            publisher.publishMessage(ackCommand, roomId, session);
            */
            // ==============================================================================

        } catch (Exception e) {
            logger.error("Error handling ACK message: {}", e.getMessage(), e);
            sendTextSafe(session, "{\"error\":\"Failed to process ACK\"}");
        }
    }

    /**
     * Extracts the original message ID from an ACK message.
     * ACK message format: messageId ends with "-DELIVERY_ACK"
     * Message text: "DELIVERY_ACK:original-message-id"
     */
    private String extractOriginalMessageId(ChatCommand ackCommand) {
        // Method 1: Extract from message text (preferred)
        String message = ackCommand.getMessage();
        if (message != null && message.startsWith("DELIVERY_ACK:")) {
            return message.substring("DELIVERY_ACK:".length());
        }

        // Method 2: Extract from messageId by removing suffix
        String messageId = ackCommand.getMessageId();
        if (messageId != null && messageId.endsWith("-DELIVERY_ACK")) {
            return messageId.substring(0, messageId.length() - "-DELIVERY_ACK".length());
        }

        // Fallback: return the message ID itself
        logger.warn("Could not extract original message ID from ACK: {}", ackCommand.getMessageId());
        return messageId;
    }

    @OnClose
    public void onClose(Session session, CloseReason reason, @PathParam("roomId") String roomId) {
        // Cleanup heartbeat scheduler
        ScheduledExecutorService scheduler = (ScheduledExecutorService) session.getUserProperties().get("heartbeatScheduler");
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        logger.info("WebSocket disconnected - session: {}, room: {}, reason: {}",
                session.getId(), roomId, reason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable t, @PathParam("roomId") String roomId) {
        String sessionId = (session != null) ? session.getId() : "unknown";
        String room = (roomId != null) ? roomId : "unknown";
        logger.error("WebSocket error - session: {}, room: {}, error: {}", sessionId, room, t.getMessage());

        if (session != null) {
            // Cleanup heartbeat scheduler on error
            ScheduledExecutorService scheduler = (ScheduledExecutorService) session.getUserProperties().get("heartbeatScheduler");
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            try {
                if (session.isOpen() && !session.getUserProperties().containsKey("closing")) {
                    session.getUserProperties().put("closing", true);
                    session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Error occurred"));
                }
            } catch (IOException e) {
                logger.debug("Exception closing session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Helper method to send text message to client safely.
     * Checks if the session is open and handles IOExceptions gracefully.
     */
    private void sendTextSafe(Session session, String message) {
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException e) {
                logger.info("Client closed connection abruptly for session {}: {}", session.getId(), e.getMessage());
                try {
                    if (session.isOpen()) {
                        session.close(new CloseReason(CloseReason.CloseCodes.PROTOCOL_ERROR, "IOException on send"));
                    }
                } catch (IOException closeEx) {
                    logger.info("Exception while closing session {}: {}", session.getId(), closeEx.getMessage());
                }
            }
        } else {
            logger.warn("Session {} is closed or null, skipping send", (session != null ? session.getId() : "null"));
        }
    }
}
