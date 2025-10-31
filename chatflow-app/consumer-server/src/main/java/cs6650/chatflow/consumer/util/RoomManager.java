package cs6650.chatflow.consumer.util;

import cs6650.chatflow.consumer.commons.Constants;
import cs6650.chatflow.consumer.model.ChatEvent;
import cs6650.chatflow.consumer.model.MessageAck;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.Session;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced room manager with user-based state tracking and guaranteed message delivery.
 * Each room tracks users, their sessions, and message delivery states.
 */
public class RoomManager {
    private static final Logger logger = LoggerFactory.getLogger(RoomManager.class);
    private static final Gson gson = new Gson();

    // Legacy: Room ID -> Set of active WebSocket sessions (for backward compatibility)
    private final Map<String, Set<Session>> roomSessions = new ConcurrentHashMap<>();

    // Enhanced: Room ID -> RoomState (user tracking + message delivery)
    private final Map<String, RoomState> roomStates = new ConcurrentHashMap<>();

    // Circuit breakers: Room ID -> CircuitBreaker (per-room failure protection)
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    private static class SingletonHolder {
        private static final RoomManager INSTANCE = new RoomManager();
    }

    public static RoomManager getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private RoomManager() {
        // Private constructor for singleton
    }

    /**
     * Adds a WebSocket session to the specified room with user tracking.
     * Expects userId in session properties.
     */
    public void addSession(Session session, String roomId) {
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);

        // Enhanced user tracking
        RoomState roomState = roomStates.computeIfAbsent(roomId, RoomState::new);
        roomState.addSession(session);

        String userId = (String) session.getUserProperties().get("userId");
        if (userId != null) {
            roomState.addUser(userId, session);
            logger.info("Added user {} (session {}) to room {}", userId, session.getId(), roomId);
        } else {
            logger.warn("Session {} connected without userId to room {}", session.getId(), roomId);
        }
    }

    /**
     * Removes a WebSocket session from the specified room.
     */
    public void removeSession(Session session, String roomId) {
        Set<Session> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }

        // Enhanced cleanup
        RoomState roomState = roomStates.get(roomId);
        if (roomState != null) {
            roomState.removeSession(session);

            String userId = (String) session.getUserProperties().get("userId");
            if (userId != null) {
                roomState.removeUser(userId);
                logger.debug("Removed user {} from room {}", userId, roomId);
            }

            // Clean up empty rooms
            if (roomState.isEmpty()) {
                roomStates.remove(roomId);
                // Remove circuit breaker for empty room to free memory
                circuitBreakers.remove(roomId);
                logger.info("Cleaned up empty room {} and circuit breaker", roomId);
            }
        }

        logger.info("Removed session {} from room {}", session.getId(), roomId);
    }

    /**
     * Adds a user to a room's active user list when JOIN message is received.
     * This is called when processing JOIN messages from the queue.
     */
    public void addUserToRoom(String userId, String roomId) {
        if (userId == null) {
            logger.warn("Cannot add null userId to room {}", roomId);
            return;
        }

        RoomState roomState = roomStates.computeIfAbsent(roomId, RoomState::new);
        if (!roomState.hasUser(userId)) {
            // Add user without session initially (user identified via ACK messages)
            User user = new User(userId, null); // No WebSocket session yet
            roomState.addUser(userId, null);
            logger.info("Added user {} to active users in room {}", userId, roomId);
        }
    }

    /**
     * Removes a user from a room's active user list when LEAVE message is received.
     */
    public void removeUserFromRoom(String userId, String roomId) {
        if (userId == null) {
            logger.warn("Cannot remove null userId from room {}", roomId);
            return;
        }

        RoomState roomState = roomStates.get(roomId);
        if (roomState != null) {
            roomState.removeUser(userId);
            logger.info("Removed user {} from active users in room {}", userId, roomId);

            // Clean up empty rooms
            if (roomState.isEmpty()) {
                roomStates.remove(roomId);
                // Remove circuit breaker for empty room to free memory
                circuitBreakers.remove(roomId);
                logger.info("Cleaned up empty room {} and circuit breaker", roomId);
            }
        }
    }

    /**
     * Broadcasts a message to all active WebSocket sessions in the room.
     * Protected by circuit breaker to prevent cascading failures.
     * Returns true if at least one session received the message.
     * For Option A: Immediate ACK - client acknowledgments become optional QoS.
     */
    public boolean broadcastToRoom(ChatEvent event, String roomId) {
        // Get or create circuit breaker for this room
        CircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(roomId,
            k -> new CircuitBreaker("Room-" + roomId));

        try {
            final boolean[] result = new boolean[1];
            boolean executed = circuitBreaker.execute(() -> {
                result[0] = performBroadcast(event, roomId);
            });

            if (!executed) {
                // Circuit breaker is open, broadcast prevented
                logger.warn("Broadcast prevented for room {} due to circuit breaker protection", roomId);
                return false;
            }

            return result[0];
        } catch (Exception e) {
            // This shouldn't happen as performBroadcast doesn't throw exceptions
            logger.error("Unexpected error during broadcast to room {}: {}", roomId, e.getMessage());
            return false;
        }
    }

    /**
     * Performs the actual broadcasting logic without circuit breaker protection.
     */
    private boolean performBroadcast(ChatEvent event, String roomId) {
        RoomState roomState = roomStates.get(roomId);
        if (roomState == null) {
            logger.debug("No room state for room {}", roomId);
            return false;
        }

        Set<Session> sessions = roomState.getActiveSessions();
        if (sessions.isEmpty()) {
            logger.debug("No active sessions in room {}", roomId);
            return false; // No connected clients
        }

        String messageJson = gson.toJson(event);
        int deliveredToSessions = 0;

        // Broadcast to ALL connected WebSocket sessions in this room
        for (Session session : sessions) {
            try {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(messageJson);
                    deliveredToSessions++;
                } else {
                    logger.debug("Skipping closed session {} in room {}", session.getId(), roomId);
                }
            } catch (IOException e) {
                logger.warn("Failed to send message {} to session in room {}: {}",
                    event.getMessageId(), roomId, e.getMessage());
            } catch (IllegalStateException e) {
                if (e.getMessage().contains("closed")) {
                    logger.debug("Session {} already closed in room {}, skipping", session.getId(), roomId);
                } else {
                    logger.warn("Illegal state when sending message {} to session {} in room {}: {}",
                        event.getMessageId(), session.getId(), roomId, e.getMessage());
                }
            }
        }

        logger.debug("Broadcasted message {} to {} sessions in room {}", event.getMessageId(), deliveredToSessions, roomId);

        boolean success = deliveredToSessions > 0;
        if (!success) {
            // Circuit breaker will record this as a failure
            logger.warn("Broadcast failed: no sessions received message {} in room {}", event.getMessageId(), roomId);
        }

        // For TEXT messages, client acknowledgments are now optional QoS
        // RabbitMQ ACK happens immediately in the consumer after broadcast attempt
        return success;
    }

    /**
     * Processes a client acknowledgment.
     */
    public boolean processAcknowledgment(MessageAck ack) {
        RoomState roomState = roomStates.get(ack.getRoomId());
        if (roomState == null) {
            logger.warn("Received ack for unknown room {}", ack.getRoomId());
            return false;
        }

        // Verify this user can acknowledge this message
        if (!roomState.canAcknowledgeMessage(ack.getMessageId(), ack.getUserId())) {
            logger.warn("User {} cannot acknowledge message {} in room {} (not in active user list)",
                ack.getUserId(), ack.getMessageId(), ack.getRoomId());
            return false;
        }

        // Mark as acknowledged
        roomState.markMessageAcknowledged(ack.getMessageId(), ack.getUserId());

        MessageDeliveryState deliveryState = roomState.getDeliveryState(ack.getMessageId());
        if (deliveryState != null) {
            logger.debug("User {} acknowledged message {} in room {}, pending acks: {}",
                ack.getUserId(), ack.getMessageId(), ack.getRoomId(), deliveryState.getPendingAcknowledgements());

            if (deliveryState.isFullyAcknowledged()) {
                logger.info("Message {} fully acknowledged in room {} and can be removed from queue", ack.getMessageId(), ack.getRoomId());
                return true; // Signal that message can be acknowledged to RabbitMQ
            }
        }

        return false; // Message not yet fully acknowledged
    }

    /**
     * Checks if a message can be acknowledged (fully delivered to all active users).
     */
    public boolean canAcknowledgeMessage(String messageId, String roomId) {
        RoomState roomState = roomStates.get(roomId);
        return roomState != null && roomState.canMessageBeAcknowledged(messageId);
    }


    /**
     * Gets the number of sessions in a room.
     */
    public int getRoomSessionCount(String roomId) {
        Set<Session> sessions = roomSessions.get(roomId);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * Gets the total number of active rooms.
     */
    public int getTotalRooms() {
        return roomSessions.size();
    }

    /**
     * Gets the total number of active sessions across all rooms.
     */
    public int getTotalSessions() {
        return roomSessions.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Validates if the room ID is valid (between 1 and 20).
     * Accepts both formats: "1" or "room1"
     */
    public boolean isValidRoomId(String roomId) {
        if (roomId == null) {
            return false;
        }

        String numericPart = roomId;
        if (roomId.startsWith("room")) {
            numericPart = roomId.substring(4); // Remove "room" prefix
        }

        try {
            int id = Integer.parseInt(numericPart);
            return id >= 1 && id <= 20;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Normalizes room ID to numeric format (e.g., "room1" -> "1")
     */
    public String normalizeRoomId(String roomId) {
        if (roomId == null) {
            return null;
        }

        if (roomId.startsWith("room")) {
            return roomId.substring(4); // Remove "room" prefix
        }

        return roomId; // Already in numeric format
    }

    /**
     * Gets the current state of the circuit breaker for a room.
     * Returns null if no circuit breaker exists for the room.
     */
    public CircuitBreaker.State getCircuitBreakerState(String roomId) {
        CircuitBreaker circuitBreaker = circuitBreakers.get(roomId);
        return circuitBreaker != null ? circuitBreaker.getState() : null;
    }

    /**
     * Gets the number of consecutive failures for a room's circuit breaker.
     * Returns -1 if no circuit breaker exists for the room.
     */
    public int getCircuitBreakerFailures(String roomId) {
        CircuitBreaker circuitBreaker = circuitBreakers.get(roomId);
        return circuitBreaker != null ? circuitBreaker.getConsecutiveFailures() : -1;
    }
}
