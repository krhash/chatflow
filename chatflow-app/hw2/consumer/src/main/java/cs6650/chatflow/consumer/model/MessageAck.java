package cs6650.chatflow.consumer.model;

/**
 * Acknowledgment message sent by clients when they receive a chat message.
 */
public class MessageAck {
    private String messageId;
    private String userId;
    private String roomId;
    private String messageType = "ack";

    // Getters and setters
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
}
