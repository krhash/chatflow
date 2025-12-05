package cs6650.chatflow.server.model;

/**
 * Represents a server response to a chat event/command.
 * Extends base ChatEvent with server-generated fields : processing timestamp and status.
 */
public class ChatEventResponse extends ChatEvent {
    private String serverTimestamp; // When the server processed the event
    private String status;          // Processing status; e.g., "OK" or error message

    public String getServerTimestamp() {
        return serverTimestamp;
    }

    public void setServerTimestamp(String serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

