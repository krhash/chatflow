package cs6650.chatflow.consumer.util;

import cs6650.chatflow.consumer.model.ChatEvent;

import javax.websocket.Session;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced state management for a room with user tracking and message delivery guarantees.
 */
public class RoomState {
    private final String roomId;

    // Legacy: WebSocket sessions (still needed for session management)
    private final Set<Session> activeSessions = ConcurrentHashMap.newKeySet();

    // New: User-based state management
    private final Map<String, User> activeUsers = new ConcurrentHashMap<>();

    // Message delivery tracking: messageId -> MessageDeliveryState
    private final Map<String, MessageDeliveryState> pendingDeliveries = new ConcurrentHashMap<>();

    public RoomState(String roomId) {
        this.roomId = roomId;
    }

    public String getRoomId() {
        return roomId;
    }

    /**
     * Legacy session management (backward compatibility)
     */
    public Set<Session> getActiveSessions() {
        return activeSessions;
    }

    public void addSession(Session session) {
        activeSessions.add(session);
    }

    public void removeSession(Session session) {
        activeSessions.remove(session);
    }

    public int getSessionCount() {
        return activeSessions.size();
    }

    /**
     * User-based management
     */
    public Map<String, User> getActiveUsers() {
        return activeUsers;
    }

    public void addUser(String userId, Session session) {
        activeUsers.put(userId, new User(userId, session));
    }

    public void removeUser(String userId) {
        activeUsers.remove(userId);
    }

    public User getUser(String userId) {
        return activeUsers.get(userId);
    }

    public boolean hasUser(String userId) {
        return activeUsers.containsKey(userId);
    }

    public Set<String> getActiveUserIds() {
        return activeUsers.keySet();
    }

    public int getUserCount() {
        return activeUsers.size();
    }

    /**
     * Message delivery tracking
     */
    public Map<String, MessageDeliveryState> getPendingDeliveries() {
        return pendingDeliveries;
    }

    public void trackMessage(ChatEvent event, Set<String> activeUserIds) {
        MessageDeliveryState deliveryState = new MessageDeliveryState(event, activeUserIds, roomId);
        pendingDeliveries.put(event.getMessageId(), deliveryState);
    }

    public MessageDeliveryState getDeliveryState(String messageId) {
        return pendingDeliveries.get(messageId);
    }

    public void markMessageDelivered(String messageId, String userId) {
        MessageDeliveryState state = pendingDeliveries.get(messageId);
        if (state != null) {
            state.markDelivered(userId);

            // Clean up if fully delivered (optimization)
            if (state.isDeliveredToAll()) {
                // Note: We don't remove here as we need to track acknowledgments
                // Removal happens when fully acknowledged
            }
        }
    }

    public void markMessageAcknowledged(String messageId, String userId) {
        MessageDeliveryState state = pendingDeliveries.get(messageId);
        if (state != null) {
            state.markAcknowledged(userId);

            // Clean up if fully acknowledged
            if (state.isFullyAcknowledged()) {
                pendingDeliveries.remove(messageId);
            }
        }
    }

    public boolean canAcknowledgeMessage(String messageId, String userId) {
        MessageDeliveryState state = pendingDeliveries.get(messageId);
        if (state == null) {
            return false; // Message not being tracked
        }
        return state.isIntendedForUser(userId); // User was active when message was sent
    }

    public boolean canMessageBeAcknowledged(String messageId) {
        MessageDeliveryState state = pendingDeliveries.get(messageId);
        return state != null && state.isFullyAcknowledged();
    }

    public int getPendingDeliveryCount() {
        return pendingDeliveries.size();
    }

    /**
     * Cleanup inactive users (sessions that are closed)
     */
    public void cleanupInactiveUsers() {
        activeUsers.entrySet().removeIf(entry -> !entry.getValue().isConnected());
    }

    public boolean isEmpty() {
        return activeSessions.isEmpty() && activeUsers.isEmpty() && pendingDeliveries.isEmpty();
    }

    @Override
    public String toString() {
        return "RoomState{" +
                "roomId='" + roomId + '\'' +
                ", sessions=" + getSessionCount() +
                ", users=" + getUserCount() +
                ", pendingDeliveries=" + getPendingDeliveryCount() +
                '}';
    }
}
