package cs6650.chatflow.consumer.util;

import cs6650.chatflow.consumer.model.ChatEvent;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the delivery state of a message to multiple users in a room.
 */
public class MessageDeliveryState {
    private final ChatEvent event;
    private final Set<String> activeUserIds; // Users connected when message was received
    private final Set<String> deliveredToUsers; // Users who received the message
    private final Set<String> ackedByUsers; // Users who acknowledged receipt
    private final Instant broadcastTime;
    private final String roomId;

    public MessageDeliveryState(ChatEvent event, Set<String> activeUserIds, String roomId) {
        this.event = event;
        this.activeUserIds = ConcurrentHashMap.newKeySet();
        this.activeUserIds.addAll(activeUserIds);
        this.deliveredToUsers = ConcurrentHashMap.newKeySet();
        this.ackedByUsers = ConcurrentHashMap.newKeySet();
        this.broadcastTime = Instant.now();
        this.roomId = roomId;
    }

    public ChatEvent getEvent() {
        return event;
    }

    public String getMessageId() {
        return event.getMessageId();
    }

    public Set<String> getActiveUserIds() {
        return activeUserIds;
    }

    public Set<String> getDeliveredToUsers() {
        return deliveredToUsers;
    }

    public Set<String> getAckedByUsers() {
        return ackedByUsers;
    }

    public Instant getBroadcastTime() {
        return broadcastTime;
    }

    public String getRoomId() {
        return roomId;
    }

    /**
     * Marks that the message was delivered to a specific user.
     */
    public void markDelivered(String userId) {
        if (activeUserIds.contains(userId)) {
            deliveredToUsers.add(userId);
        }
    }

    /**
     * Marks that the message was acknowledged by a specific user.
     */
    public void markAcknowledged(String userId) {
        if (activeUserIds.contains(userId)) {
            ackedByUsers.add(userId);
        }
    }

    /**
     * Checks if all active users have acknowledged the message.
     */
    public boolean isFullyAcknowledged() {
        return activeUserIds.equals(ackedByUsers);
    }

    /**
     * Checks if the message was intended for a specific user.
     * If this returns false, the message should not be acknowledged.
     */
    public boolean isIntendedForUser(String userId) {
        return activeUserIds.contains(userId);
    }

    /**
     * Gets the number of users who still haven't acknowledged.
     */
    public int getPendingAcknowledgements() {
        return activeUserIds.size() - ackedByUsers.size();
    }

    public boolean isDeliveredToAll() {
        return activeUserIds.equals(deliveredToUsers);
    }

    @Override
    public String toString() {
        return "MessageDeliveryState{" +
                "messageId='" + getMessageId() + '\'' +
                ", roomId='" + roomId + '\'' +
                ", activeUsers=" + activeUserIds.size() +
                ", delivered=" + deliveredToUsers.size() +
                ", acked=" + ackedByUsers.size() +
                ", pendingAcks=" + getPendingAcknowledgements() +
                ", fullyAcknowledged=" + isFullyAcknowledged() +
                '}';
    }
}
