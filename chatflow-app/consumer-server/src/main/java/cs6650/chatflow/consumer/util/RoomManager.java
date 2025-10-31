package cs6650.chatflow.consumer.util;

import cs6650.chatflow.consumer.commons.Constants;
import cs6650.chatflow.consumer.model.ChatEvent;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.Session;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Manages WebSocket sessions for each room.
 * Handles adding/removing sessions and broadcasting messages to all sessions in a room.
 */
public class RoomManager {
    private static final Logger logger = LoggerFactory.getLogger(RoomManager.class);
    private static final Gson gson = new Gson();

    // Room ID -> Set of active WebSocket sessions
    private final ConcurrentHashMap<String, Set<Session>> roomSessions = new ConcurrentHashMap<>();

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
     * Adds a WebSocket session to the specified room.
     */
    public void addSession(Session session, String roomId) {
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        logger.info("Added session {} to room {}", session.getId(), roomId);
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
                logger.info("Removed empty room {}", roomId);
            }
            logger.info("Removed session {} from room {}", session.getId(), roomId);
        }
    }

    /**
     * Broadcasts a ChatEvent to all sessions in the specified room.
     * Returns true if message was successfully sent to at least one client.
     */
    public boolean broadcastToRoom(ChatEvent event, String roomId) {
        Set<Session> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            logger.debug("No active sessions in room {}", roomId);
            return false; // No clients to receive the message
        }

        String message = gson.toJson(event);
        int broadcastCount = 0;

        for (Session session : sessions) {
            try {
                session.getBasicRemote().sendText(message);
                broadcastCount++;
            } catch (IOException e) {
                logger.warn("Failed to send message to session {} in room {}: {}", session.getId(), roomId, e.getMessage());
                // Remove closed session
                sessions.remove(session);
            }
        }

        if (broadcastCount > 0) {
            logger.debug("Broadcasted message {} to {} sessions in room {}", event.getMessageId(), broadcastCount, roomId);
        }

        // Clean up empty rooms
        if (sessions.isEmpty()) {
            roomSessions.remove(roomId);
            logger.info("Cleaned up empty room {}", roomId);
        }

        // Return true only if we successfully sent to at least one client
        return broadcastCount > 0;
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
}
