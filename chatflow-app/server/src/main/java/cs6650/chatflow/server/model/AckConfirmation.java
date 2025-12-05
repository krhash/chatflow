package cs6650.chatflow.server.model;

/**
 * Represents an ACK confirmation sent directly back to the client.
 * This message confirms that the producer received and processed the client's ACK.
 */
public class AckConfirmation extends ChatEvent {
    private String originalMessageId;  // The ID of the original message (not the ACK message ID)
    private String ackMessageId;       // The ID of the ACK message we're confirming
    private String serverTimestamp;    // When server processed the ACK

    public AckConfirmation() {
        // Set message type to ACK_CONFIRMATION
        setMessageType("ACK_CONFIRMATION");
    }

    public String getOriginalMessageId() {
        return originalMessageId;
    }

    public void setOriginalMessageId(String originalMessageId) {
        this.originalMessageId = originalMessageId;
    }

    public String getAckMessageId() {
        return ackMessageId;
    }

    public void setAckMessageId(String ackMessageId) {
        this.ackMessageId = ackMessageId;
    }

    public String getServerTimestamp() {
        return serverTimestamp;
    }

    public void setServerTimestamp(String serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }
}
