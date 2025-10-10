package cs6650.chatflow.client.model;

/**
 * Entry object for the message queue that contains both the message and its target room ID.
 * This allows consumers to route messages to the correct WebSocket connection.
 */
public class MessageQueueEntry {

    private final ChatMessage message;
    private final int roomId;

    /**
     * Creates a message queue entry.
     * @param message the chat message
     * @param roomId the target room ID (1-20)
     */
    public MessageQueueEntry(ChatMessage message, int roomId) {
        this.message = message;
        this.roomId = roomId;
    }

    /**
     * Gets the chat message.
     * @return the message
     */
    public ChatMessage getMessage() {
        return message;
    }

    /**
     * Gets the target room ID.
     * @return room ID (1-20)
     */
    public int getRoomId() {
        return roomId;
    }
}
