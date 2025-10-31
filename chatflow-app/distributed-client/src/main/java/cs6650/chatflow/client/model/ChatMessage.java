package cs6650.chatflow.client.model;

/**
 * Chat message model for the distributed client.
 * Adapted from client-part1 but designed for distributed architecture.
 */
public class ChatMessage {
    private String messageId;
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;
    private String roomId;
    private String userIdAck; // For acknowledgment tracking
    private String roomIdAck; // For acknowledgment tracking

    // Getters and setters
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getUserIdAck() { return userIdAck; }
    public void setUserIdAck(String userIdAck) { this.userIdAck = userIdAck; }

    public String getRoomIdAck() { return roomIdAck; }
    public void setRoomIdAck(String roomIdAck) { this.roomIdAck = roomIdAck; }
}
