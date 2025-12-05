package cs6650.chatflow.server.commons;

public final class Constants {

    // ========== ENDPOINT PATHS ==========
    public static final String HEALTH_ENDPOINT = "/health";
    public static final String CHAT_ENDPOINT = "/chat";
    public static final String CHAT_ROOM_PATH = CHAT_ENDPOINT + "/{roomId}";

    // ========== MESSAGE TYPES ==========
    public static final String[] MESSAGE_TYPES = {"TEXT", "JOIN", "LEAVE", "ACK"};

    // ========== NEW: ACK Confirmation Type ==========
    public static final String MESSAGE_TYPE_ACK = "ACK";
    public static final String MESSAGE_TYPE_ACK_CONFIRMATION = "ACK_CONFIRMATION";
    // ================================================

    // ========== VALIDATION REGEX PATTERNS ==========
    public static final String USERNAME_REGEX = "[a-zA-Z0-9]{3,20}";

    // ========== USER VALIDATION LIMITS ==========
    public static final int USER_ID_MIN = 1;
    public static final int USER_ID_MAX = 100000;

    // ========== MESSAGE VALIDATION LIMITS ==========
    public static final int MESSAGE_LENGTH_MIN = 1;
    public static final int MESSAGE_LENGTH_MAX = 500;

    // ========== ERROR MESSAGES ==========
    public static final String ERROR_INVALID_USER_ID = "UserId invalid";
    public static final String ERROR_USER_ID_OUT_OF_RANGE = "UserId out of range";
    public static final String ERROR_INVALID_USERNAME = "Username invalid";
    public static final String ERROR_INVALID_MESSAGE_LENGTH = "Message length invalid";
    public static final String ERROR_INVALID_TIMESTAMP = "Timestamp invalid";
    public static final String ERROR_INVALID_MESSAGE_TYPE = "Invalid messageType";
    public static final String ERROR_INVALID_JSON = "Invalid JSON or message format";
    public static final String ERROR_INTERNAL_SERVER = "Internal server error";

    // ========== RESPONSE STATUS VALUES ==========
    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";

    // Heartbeat configuration
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    private Constants() {
        throw new AssertionError("This class cannot be instantiated.");
    }
}
