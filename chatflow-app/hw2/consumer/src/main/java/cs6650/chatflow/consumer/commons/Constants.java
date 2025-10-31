package cs6650.chatflow.consumer.commons;

/**
 * Constants used throughout the consumer server.
 */
public class Constants {
    // WebSocket paths
    public static final String CHAT_RECEIVER_PATH = "/chatflow-receiver/{roomId}";

    // Heartbeat configuration
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    // Error messages
    public static final String ERROR_INVALID_JSON = "Invalid JSON format";
    public static final String ERROR_INTERNAL_SERVER = "Internal server error";
    public static final String ERROR_ROOM_NOT_FOUND = "Room not found";
    public static final String ERROR_INVALID_ROOM_ID = "Invalid room ID";

    private Constants() {
        // Utility class
    }
}
