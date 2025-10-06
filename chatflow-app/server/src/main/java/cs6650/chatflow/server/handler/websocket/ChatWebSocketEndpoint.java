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
        logger.info("Session {} opened for room {}", session.getId(), roomId);
    }

    @OnMessage
    public void onMessage(Session session, String msgJson, @PathParam("roomId") String roomId) throws IOException {
        try {
            ChatCommand command = gson.fromJson(msgJson, ChatCommand.class);
            String validationError = ValidationUtils.validate(command);
            if (validationError != null) {
                logger.warn("Validation failed for session {}: {}", session.getId(), validationError);
                session.getBasicRemote().sendText("{\"error\":\"" + validationError + "\"}");
                return;
            }

            ChatEventResponse response = new ChatEventResponse();
            // Copy fields from command
            response.setUserId(command.getUserId());
            response.setUsername(command.getUsername());
            response.setMessage(command.getMessage());
            response.setTimestamp(command.getTimestamp());
            response.setMessageType(command.getMessageType());

            // Add server-side metadata
            response.setServerTimestamp(Instant.now().toString());
            response.setStatus(ChatConstants.STATUS_OK);

            session.getBasicRemote().sendText(gson.toJson(response));
            logger.info("Sent response to session {}: {}", session.getId(), response.getMessage());
        } catch (JsonSyntaxException e) {
            logger.error("Invalid JSON from session {}: {}", session.getId(), e.getMessage());
            session.getBasicRemote().sendText("{\"error\":\"" + ChatConstants.ERROR_INVALID_JSON + "\"}");
        } catch (Exception ex) {
            logger.error("Unexpected error on session {}: {}", session.getId(), ex.getMessage(), ex);
            session.getBasicRemote().sendText("{\"error\":\"" + ChatConstants.ERROR_INTERNAL_SERVER + "\"}");
        }
    }

    @OnClose
    public void onClose(Session session) {
        logger.info("Session {} closed", session.getId());
    }

    @OnError
    public void onError(Session session, Throwable t) {
        String sessionId = (session != null) ? session.getId() : "unknown";
        logger.error("Error in session {}: {}", sessionId, t.getMessage(), t);
    }
}
