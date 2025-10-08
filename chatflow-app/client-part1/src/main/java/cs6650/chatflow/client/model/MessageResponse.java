package cs6650.chatflow.client.model;

/**
 * Wrapper for WebSocket message responses with metadata.
 * Used in the response processing pipeline.
 */
public class MessageResponse {

    private final String messageJson;
    private final long receivedTimestampNanos;

    /**
     * Creates a message response wrapper.
     * @param messageJson raw JSON response from server
     * @param receivedTimestampNanos nanosecond timestamp when response was received
     */
    public MessageResponse(String messageJson, long receivedTimestampNanos) {
        this.messageJson = messageJson;
        this.receivedTimestampNanos = receivedTimestampNanos;
    }

    /**
     * Gets the raw JSON message response.
     * @return JSON string from server
     */
    public String getMessageJson() {
        return messageJson;
    }

    /**
     * Gets the timestamp when response was received.
     * @return nanosecond timestamp
     */
    public long getReceivedTimestampNanos() {
        return receivedTimestampNanos;
    }
}
