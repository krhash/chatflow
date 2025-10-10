package cs6650.chatflow.client.model;

import com.google.gson.annotations.SerializedName;

/**
 * POJO representing a chat message to be sent over WebSocket.
 */
public class ChatMessage {
    @SerializedName("messageId")
    private String messageId;

    @SerializedName("userId")
    private String userId;

    @SerializedName("username")
    private String username;

    @SerializedName("message")
    private String message;

    @SerializedName("timestamp")
    private String timestamp;

    @SerializedName("messageType")
    private String messageType;

    // Server response fields
    @SerializedName("serverTimestamp")
    private String serverTimestamp;

    @SerializedName("status")
    private String status;

    public ChatMessage(String messageId, int userId, String username, String message, String timestamp, String messageType) {
        this.messageId = messageId;
        this.userId = Integer.toString(userId);
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
    }

    // Getters
    public String getMessageId() { return messageId; }
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getMessage() { return message; }
    public String getTimestamp() { return timestamp; }
    public String getMessageType() { return messageType; }
    public String getServerTimestamp() { return serverTimestamp; }
    public String getStatus() { return status; }
}
