package cs6650.chatflow.server.commons;

/**
 * Constants used throughout the chat server.
 * Includes endpoint paths, message types, validation regex, and validation limits.
 * All validation defaults are centralized here for easy maintenance and refactoring.
 */
public final class Constants {

    // ========== ENDPOINT PATHS ==========
    /** Health check REST endpoint path */
    public static final String HEALTH_ENDPOINT = "/health";

    /** Base chat WebSocket endpoint path */
    public static final String CHAT_ENDPOINT = "/chat";

    /** Chat room WebSocket endpoint path with room ID parameter */
    public static final String CHAT_ROOM_PATH = CHAT_ENDPOINT + "/{roomId}";

    // ========== MESSAGE TYPES (using enum for type safety) ==========
    /** Allowed message types for chat commands - kept as array for JSON compatibility */
    public static final String[] MESSAGE_TYPES = {"TEXT", "JOIN", "LEAVE"};

    // ========== VALIDATION REGEX PATTERNS ==========
    /** Username validation regex pattern (3-20 alphanumeric characters only, no underscores) */
    public static final String USERNAME_REGEX = "[a-zA-Z0-9]{3,20}";

    // ========== USER VALIDATION LIMITS ==========
    /** Minimum valid user ID value */
    public static final int USER_ID_MIN = 1;

    /** Maximum valid user ID value */
    public static final int USER_ID_MAX = 100000;

    // ========== MESSAGE VALIDATION LIMITS ==========
    /** Minimum message length */
    public static final int MESSAGE_LENGTH_MIN = 1;

    /** Maximum message length */
    public static final int MESSAGE_LENGTH_MAX = 500;

    // ========== ERROR MESSAGES ==========
    /** Error message for invalid user ID */
    public static final String ERROR_INVALID_USER_ID = "UserId invalid";

    /** Error message for user ID out of range */
    public static final String ERROR_USER_ID_OUT_OF_RANGE = "UserId out of range";

    /** Error message for invalid username */
    public static final String ERROR_INVALID_USERNAME = "Username invalid";

    /** Error message for invalid message length */
    public static final String ERROR_INVALID_MESSAGE_LENGTH = "Message length invalid";

    /** Error message for invalid timestamp */
    public static final String ERROR_INVALID_TIMESTAMP = "Timestamp invalid";

    /** Error message for invalid message type */
    public static final String ERROR_INVALID_MESSAGE_TYPE = "Invalid messageType";

    /** Error message for invalid JSON format */
    public static final String ERROR_INVALID_JSON = "Invalid JSON or message format";

    /** Error message for internal server errors */
    public static final String ERROR_INTERNAL_SERVER = "Internal server error";

    // ========== RESPONSE STATUS VALUES ==========
    /** Success status for server responses */
    public static final String STATUS_OK = "OK";

    // Heartbeat configuration
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30; // Send ping every 30 seconds

    /** Error status for server responses */
    public static final String STATUS_ERROR = "ERROR";

    private Constants() {
        throw new AssertionError("This class cannot be instantiated.");
    }
}
