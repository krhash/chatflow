package cs6650.chatflow.server.model;

/**
 * Base class representing a generic chat event.
 * Contains common fields shared by client commands and server responses.
 */
public class ChatEvent {
    private String messageId;
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;

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
}
