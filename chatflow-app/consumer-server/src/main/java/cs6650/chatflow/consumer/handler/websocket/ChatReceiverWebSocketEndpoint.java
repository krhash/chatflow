package cs6650.chatflow.consumer.handler.websocket;

import cs6650.chatflow.consumer.commons.Constants;
import cs6650.chatflow.consumer.util.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/**
 * WebSocket endpoint for clients to receive chat messages from specific rooms.
 * Clients connect with /chatflow-receiver/{roomId} and automatically start receiving messages.
 * Implements heartbeat mechanism to keep connections alive.
 */
@ServerEndpoint(Constants.CHAT_RECEIVER_PATH)
public class ChatReceiverWebSocketEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(ChatReceiverWebSocketEndpoint.class);

    private final RoomManager roomManager = RoomManager.getInstance();

    @OnOpen
    public void onOpen(Session session, @PathParam("roomId") String roomId) {
        try {
            // Validate room ID
            if (!roomManager.isValidRoomId(roomId)) {
                logger.warn("Invalid room ID: {} for session {}", roomId, session.getId());
                try {
                    session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, Constants.ERROR_INVALID_ROOM_ID));
                } catch (IOException e) {
                    logger.error("Failed to close session for invalid room ID", e);
                }
                return;
            }

            // Normalize room ID to numeric format
            String normalizedRoomId = roomManager.normalizeRoomId(roomId);

            logger.info("WebSocket receiver connected - session: {}, room: {} (normalized: {})",
                session.getId(), roomId, normalizedRoomId);

            // Add session to room manager (using normalized ID)
            roomManager.addSession(session, normalizedRoomId);

            // Start heartbeat scheduler for this session
            ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "Heartbeat-" + session.getId()));

            // Store scheduler in session properties for cleanup
            session.getUserProperties().put("heartbeatScheduler", heartbeatScheduler);
            session.getUserProperties().put("roomId", roomId);

            // Schedule periodic ping sending
            heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (session.isOpen()) {
                        session.getBasicRemote().sendPing(ByteBuffer.wrap("ping".getBytes()));
                        logger.debug("Ping sent to receiver session {}", session.getId());
                    }
                } catch (Exception e) {
                    logger.debug("Failed to send ping to receiver session {}: {}", session.getId(), e.getMessage());
                }
            }, Constants.HEARTBEAT_INTERVAL_SECONDS, Constants.HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

            logger.info("Receiver session {} started listening to room {}", session.getId(), roomId);

        } catch (Exception e) {
            logger.error("Error in onOpen for session {}: {}", session.getId(), e.getMessage(), e);
            try {
                if (session.isOpen()) {
                    session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, Constants.ERROR_INTERNAL_SERVER));
                }
            } catch (IOException closeEx) {
                logger.error("Failed to close session on error", closeEx);
            }
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason, @PathParam("roomId") String roomId) {
        // Get room ID from session properties if not provided
        String actualRoomId = (String) session.getUserProperties().get("roomId");
        if (actualRoomId == null) {
            actualRoomId = roomId;
        }

        logger.info("WebSocket receiver disconnected - session: {}, room: {}, reason: {}",
            session.getId(), actualRoomId, reason.getReasonPhrase());

        // Remove session from room manager
        if (actualRoomId != null) {
            roomManager.removeSession(session, actualRoomId);
        }

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
    }

    @OnError
    public void onError(Session session, Throwable t, @PathParam("roomId") String roomId) {
        String sessionId = (session != null) ? session.getId() : "unknown";
        String actualRoomId = (session != null) ? (String) session.getUserProperties().get("roomId") : roomId;
        if (actualRoomId == null) actualRoomId = "unknown";

        logger.error("WebSocket receiver error - session: {}, room: {}, error: {}", sessionId, actualRoomId, t.getMessage());

        if (session != null) {
            // Remove session from room manager
            roomManager.removeSession(session, actualRoomId);

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
                    // Mark closing to avoid recursive close calls
                    session.getUserProperties().put("closing", true);
                    session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Error occurred"));
                }
            } catch (IOException e) {
                logger.debug("Exception closing receiver session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * This endpoint only receives messages from the server, so no @OnMessage handler for client messages.
     * Messages are sent automatically when they arrive from RabbitMQ via the RoomManager.broadcastToRoom() method.
     */
}
