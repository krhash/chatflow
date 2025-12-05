package cs6650.chatflow.client.model;

/**
 * Chat message model for the distributed client.
 * Supports regular messages (JOIN, TEXT, LEAVE, ACK) and ACK confirmation messages from producer.
 */
public class ChatMessage {
    private String messageId;
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;
    private String roomId;

    // ========== ACK Confirmation Fields ==========
    private String originalMessageId;  // Original message ID in ACK confirmation
    private String ackMessageId;       // ACK message ID in confirmation
    private String serverTimestamp;    // Server processing timestamp
    // =============================================

    // Standard getters and setters
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

    // ACK confirmation getters and setters
    public String getOriginalMessageId() { return originalMessageId; }
    public void setOriginalMessageId(String originalMessageId) { this.originalMessageId = originalMessageId; }

    public String getAckMessageId() { return ackMessageId; }
    public void setAckMessageId(String ackMessageId) { this.ackMessageId = ackMessageId; }

    public String getServerTimestamp() { return serverTimestamp; }
    public void setServerTimestamp(String serverTimestamp) { this.serverTimestamp = serverTimestamp; }
}
