package cs6650.chatflow.consumer.database.model;

import cs6650.chatflow.consumer.model.ChatEvent;

/**
 * Database representation of a chat message.
 * Uses composition pattern - wraps ChatEvent data with database-optimized types.
 */
public class DatabaseMessage {

    // Core fields (optimized for database storage)
    private final String messageId;
    private final int roomId;           // Stored as int in DynamoDB
    private final String userId;
    private final String username;
    private final String message;
    private final String messageType;
    private final long timestamp;       // Stored as long (epoch millis)
    private final long createdAt;       // DB record creation timestamp

    // Private constructor - use factory method or builder
    private DatabaseMessage(String messageId, int roomId, String userId, String username,
                            String message, String messageType, long timestamp, long createdAt) {
        this.messageId = messageId;
        this.roomId = roomId;
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.messageType = messageType;
        this.timestamp = timestamp;
        this.createdAt = createdAt;
    }

    /**
     * Create DatabaseMessage from ChatEvent and roomId
     * This is the main factory method used by the application
     */
    public static DatabaseMessage fromChatEvent(ChatEvent event, String roomId) {
        return new DatabaseMessage(
                event.getMessageId(),
                parseRoomId(roomId),
                event.getUserId(),
                event.getUsername(),
                event.getMessage(),
                event.getMessageType(),
                parseTimestamp(event.getTimestamp()),
                System.currentTimeMillis()
        );
    }

    /**
     * Convert DatabaseMessage back to ChatEvent
     * Used when reading from database for queries
     */
    public ChatEvent toChatEvent() {
        ChatEvent event = new ChatEvent();
        event.setMessageId(messageId);
        event.setUserId(userId);
        event.setUsername(username);
        event.setMessage(message);
        event.setMessageType(messageType);
        event.setTimestamp(String.valueOf(timestamp));
        return event;
    }

    // Parsing helper methods
    private static int parseRoomId(String roomId) {
        try {
            if (roomId == null || roomId.isEmpty()) {
                return 0;
            }

            // Handle both "room1" and "1" formats
            if (roomId.startsWith("room")) {
                return Integer.parseInt(roomId.substring(4));
            }
            return Integer.parseInt(roomId);

        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseTimestamp(String timestamp) {
        try {
            if (timestamp == null || timestamp.isEmpty()) {
                return System.currentTimeMillis();
            }
            return Long.parseLong(timestamp);

        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    // Getters (immutable object)
    public String getMessageId() {
        return messageId;
    }

    public int getRoomId() {
        return roomId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getMessage() {
        return message;
    }

    public String getMessageType() {
        return messageType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return String.format("DatabaseMessage{messageId='%s', roomId=%d, userId='%s', type='%s', timestamp=%d}",
                messageId, roomId, userId, messageType, timestamp);
    }
}
