package cs6650.chatflow.client.model;

/**
 * Entry object for the message queue that contains both the message and its target room ID.
 * This allows consumers to route messages to the correct WebSocket connection.
 */
public class MessageQueueEntry {

    private final ChatMessage message;
    private final String roomId;

    /**
     * Creates a message queue entry.
     * @param message the chat message
     * @param roomId the target room ID (e.g., "room1", "room2", ..., "room20")
     */
    public MessageQueueEntry(ChatMessage message, String roomId) {
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
     * @return room ID (e.g., "room1", "room2", ..., "room20")
     */
    public String getRoomId() {
        return roomId;
    }

    /**
     * Gets the numeric room ID.
     * @return room number (1-20)
     */
    public int getRoomNumber() {
        return Integer.parseInt(roomId.substring(4)); // "room" + number
    }
}
