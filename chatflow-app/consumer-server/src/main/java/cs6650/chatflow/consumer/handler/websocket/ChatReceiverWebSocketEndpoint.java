package cs6650.chatflow.consumer.handler.websocket;

import cs6650.chatflow.consumer.commons.Constants;
import cs6650.chatflow.consumer.util.RoomManager;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/**
 * WebSocket endpoint for anonymous listeners to receive chat messages from specific rooms.
 *
 * Use Case: Test clients, monitoring tools, or analytics listeners
 * - Clients connect with /chatflow-receiver/{roomId}
 * - No userId required (anonymous listening)
 * - Automatically receive all messages broadcast to the room
 * - Heartbeat mechanism keeps connections alive
 */
@ServerEndpoint(Constants.CHAT_RECEIVER_PATH)
public class ChatReceiverWebSocketEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(ChatReceiverWebSocketEndpoint.class);
    private final RoomManager roomManager = RoomManager.getInstance();
    private static final Gson gson = new Gson();

    @OnOpen
    public void onOpen(Session session, @PathParam("roomId") String roomId) {
        try {
            // Validate room ID
            if (!roomManager.isValidRoomId(roomId)) {
                logger.warn("Invalid room ID: {} for session {}", roomId, session.getId());
                try {
                    session.close(new CloseReason(
                            CloseReason.CloseCodes.CANNOT_ACCEPT,
                            Constants.ERROR_INVALID_ROOM_ID
                    ));
                } catch (IOException e) {
                    logger.error("Failed to close session for invalid room ID", e);
                }
                return;
            }

            // Normalize room ID to numeric format
            String normalizedRoomId = roomManager.normalizeRoomId(roomId);

            logger.info("🔌 Anonymous listener connected - session: {}, room: {} (normalized: {})",
                    session.getId(), roomId, normalizedRoomId);

            // Store room ID in session properties for later use
            session.getUserProperties().put("roomId", normalizedRoomId);

            // Add session to room manager (no userId needed for anonymous listeners)
            roomManager.addSession(session, normalizedRoomId);

            // Start heartbeat scheduler for this session
            ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "Heartbeat-" + session.getId())
            );

            // Store scheduler in session properties for cleanup
            session.getUserProperties().put("heartbeatScheduler", heartbeatScheduler);

            // Schedule periodic ping sending (keep-alive)
            heartbeatScheduler.scheduleAtFixedRate(() -> {
                        try {
                            if (session.isOpen()) {
                                session.getBasicRemote().sendPing(ByteBuffer.wrap("ping".getBytes()));
                                logger.trace("📡 Ping sent to listener session {}", session.getId());
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to send ping to listener session {}: {}",
                                    session.getId(), e.getMessage());
                        }
                    }, Constants.HEARTBEAT_INTERVAL_SECONDS,
                    Constants.HEARTBEAT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);

            logger.info("✅ Listener session {} now receiving messages from room {}",
                    session.getId(), normalizedRoomId);

        } catch (Exception e) {
            logger.error("Error in onOpen for listener session {}: {}",
                    session.getId(), e.getMessage(), e);
            try {
                if (session.isOpen()) {
                    session.close(new CloseReason(
                            CloseReason.CloseCodes.UNEXPECTED_CONDITION,
                            Constants.ERROR_INTERNAL_SERVER
                    ));
                }
            } catch (IOException closeEx) {
                logger.error("Failed to close session on error", closeEx);
            }
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason, @PathParam("roomId") String roomId) {
        // Get room ID from session properties
        String actualRoomId = (String) session.getUserProperties().get("roomId");
        if (actualRoomId == null) {
            actualRoomId = roomId;
        }

        logger.info("🔌 Listener disconnected - session: {}, room: {}, reason: {}",
                session.getId(), actualRoomId, reason.getReasonPhrase());

        // Remove session from room manager
        if (actualRoomId != null) {
            roomManager.removeSession(session, actualRoomId);
        }

        // Cleanup heartbeat scheduler
        ScheduledExecutorService scheduler = (ScheduledExecutorService)
                session.getUserProperties().get("heartbeatScheduler");
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
        String actualRoomId = (session != null)
                ? (String) session.getUserProperties().get("roomId")
                : roomId;
        if (actualRoomId == null) actualRoomId = "unknown";

        logger.error("⚠️ Listener error - session: {}, room: {}, error: {}",
                sessionId, actualRoomId, t.getMessage());

        if (session != null) {
            // Remove session from room manager
            roomManager.removeSession(session, actualRoomId);

            // Cleanup heartbeat scheduler on error
            ScheduledExecutorService scheduler = (ScheduledExecutorService)
                    session.getUserProperties().get("heartbeatScheduler");
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
                    session.close(new CloseReason(
                            CloseReason.CloseCodes.UNEXPECTED_CONDITION,
                            "Error occurred"
                    ));
                }
            } catch (IOException e) {
                logger.debug("Exception closing listener session {}: {}",
                        sessionId, e.getMessage());
            }
        }
    }

    /**
     * Handle messages from clients (mostly for logging/debugging).
     * Anonymous listeners typically don't send messages, just receive.
     */
    @OnMessage
    public void onMessage(Session session, String message, @PathParam("roomId") String roomId) {
        try {
            String normalizedRoomId = roomManager.normalizeRoomId(roomId);

            logger.debug("📨 Message received from listener session {} in room {}: {}",
                    session.getId(), normalizedRoomId, message);

            // Anonymous listeners don't typically send messages
            // If they do, just log it (could be used for monitoring/debugging)

        } catch (Exception e) {
            logger.error("Error processing message from listener session {} in room {}: {}",
                    session.getId(), roomId, e.getMessage());
        }
    }
}
