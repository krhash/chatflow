package cs6650.chatflow.server.handler.websocket;

import cs6650.chatflow.server.model.ChatCommand;
import cs6650.chatflow.server.model.ChatEventResponse;
import cs6650.chatflow.server.util.ValidationUtils;
import cs6650.chatflow.server.commons.ChatConstants;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.time.Instant;

/**
 * WebSocket endpoint handling chat commands and sending chat event responses.
 */
@ServerEndpoint(ChatConstants.CHAT_ROOM_PATH)
public class ChatWebSocketEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketEndpoint.class);
    private static final Gson gson = new Gson();

    @OnOpen
    public void onOpen(Session session, @PathParam("roomId") String roomId) {
        logger.info("WebSocket client connected - session: {}, room: {}", session.getId(), roomId);
    }

    @OnMessage
    public void onMessage(Session session, String msgJson, @PathParam("roomId") String roomId) {
        try {
            ChatCommand command = gson.fromJson(msgJson, ChatCommand.class);

            // Store userId in session user properties for logging
            if (command.getUserId() != null) {
                session.getUserProperties().put("userId", command.getUserId());
            }

            logger.debug("Received: {}", msgJson);

            String validationError = ValidationUtils.validate(command);
            if (validationError != null) {
                logger.warn("Validation failed - session: {}, user: {}, error: {}",
                    session.getId(), command.getUserId(), validationError);
                sendTextSafe(session, "{\"error\":\"" + validationError + "\"}");
                return;
            }

            ChatEventResponse response = new ChatEventResponse();
            // Copy fields from command
            response.setMessageId(command.getMessageId());
            response.setUserId(command.getUserId());
            response.setUsername(command.getUsername());
            response.setMessage(command.getMessage());
            response.setTimestamp(command.getTimestamp());
            response.setMessageType(command.getMessageType());

            // Add server-side metadata
            response.setServerTimestamp(Instant.now().toString());
            response.setStatus(ChatConstants.STATUS_OK);

            String responseJson = gson.toJson(response);
            logger.debug("Sent: {}", responseJson);
            sendTextSafe(session, responseJson);

        } catch (JsonSyntaxException e) {
            logger.error("Invalid JSON - session: {}, message: {}, error: {}", session.getId(), msgJson, e.getMessage());
            sendTextSafe(session, "{\"error\":\"" + ChatConstants.ERROR_INVALID_JSON + "\"}");
        } catch (Exception ex) {
            logger.error("Processing error - session: {}, message: {}, error: {}", session.getId(), msgJson, ex.getMessage(), ex);
            sendTextSafe(session, "{\"error\":\"" + ChatConstants.ERROR_INTERNAL_SERVER + "\"}");
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        String userId = (String) session.getUserProperties().get("userId");
        logger.info("WebSocket disconnected - session: {}, user: {}, reason: {}",
            session.getId(), userId != null ? userId : "unknown", reason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable t) {
        String sessionId = (session != null) ? session.getId() : "unknown";
        String userId = (session != null) ? (String) session.getUserProperties().get("userId") : "unknown";
        logger.error("WebSocket error - session: {}, user: {}, error: {}", sessionId, userId, t.getMessage());

        if (session != null) {
            try {
                if (session.isOpen() && !session.getUserProperties().containsKey("closing")) {
                    // Mark closing to avoid recursive close calls
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
                // Client disconnected abruptly; log info and close session cleanly
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
