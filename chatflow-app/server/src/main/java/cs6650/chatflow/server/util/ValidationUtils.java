package cs6650.chatflow.server.util;

import cs6650.chatflow.server.model.ChatCommand;
import cs6650.chatflow.server.commons.ChatConstants;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Utility class for validating ChatCommand fields.
 * Uses centralized constants from ChatConstants for all default values.
 */
public final class ValidationUtils {

    /** Compiled pattern for username validation - cached for performance */
    private static final Pattern USERNAME_PATTERN = Pattern.compile(ChatConstants.USERNAME_REGEX);

    /**
     * Validates the fields of a ChatCommand.
     * @param cmd ChatCommand object to validate.
     * @return null if valid, else error message string from ChatConstants.
     */
    public static String validate(ChatCommand cmd) {
        // Validate user ID
        String userIdValidation = validateUserId(cmd.getUserId());
        if (userIdValidation != null) {
            return userIdValidation;
        }

        // Validate username
        String usernameValidation = validateUsername(cmd.getUsername());
        if (usernameValidation != null) {
            return usernameValidation;
        }

        // Validate message
        String messageValidation = validateMessage(cmd.getMessage());
        if (messageValidation != null) {
            return messageValidation;
        }

        // Validate timestamp
        String timestampValidation = validateTimestamp(cmd.getTimestamp());
        if (timestampValidation != null) {
            return timestampValidation;
        }

        // Validate message type
        String messageTypeValidation = validateMessageType(cmd.getMessageType());
        if (messageTypeValidation != null) {
            return messageTypeValidation;
        }

        return null;
    }

    /**
     * Validates the user ID field.
     * @param userId User ID string to validate.
     * @return null if valid, else error message from ChatConstants.
     */
    public static String validateUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return ChatConstants.ERROR_INVALID_USER_ID;
        }

        try {
            int userIdInt = Integer.parseInt(userId.trim());

            if (userIdInt < ChatConstants.USER_ID_MIN || userIdInt > ChatConstants.USER_ID_MAX) {
                return ChatConstants.ERROR_USER_ID_OUT_OF_RANGE;
            }
        } catch (NumberFormatException ex) {
            return ChatConstants.ERROR_INVALID_USER_ID;
        }

        return null;
    }

    /**
     * Validates the username field using regex pattern.
     * The regex [a-zA-Z0-9]{3,20} handles both length and character validation efficiently.
     * @param username Username string to validate.
     * @return null if valid, else error message from ChatConstants.
     */
    public static String validateUsername(String username) {
        if (username == null) {
            return ChatConstants.ERROR_INVALID_USERNAME;
        }

        // Use pre-compiled pattern - regex handles both length and character validation
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return ChatConstants.ERROR_INVALID_USERNAME;
        }

        return null;
    }

    /**
     * Validates the message field.
     * @param message Message string to validate.
     * @return null if valid, else error message from ChatConstants.
     */
    public static String validateMessage(String message) {
        if (message == null) {
            return ChatConstants.ERROR_INVALID_MESSAGE_LENGTH;
        }

        if (message.length() < ChatConstants.MESSAGE_LENGTH_MIN || message.length() > ChatConstants.MESSAGE_LENGTH_MAX) {
            return ChatConstants.ERROR_INVALID_MESSAGE_LENGTH;
        }

        return null;
    }

    /**
     * Validates the timestamp field using ISO-8601 format.
     * Uses Java's built-in time parsing for accurate ISO-8601 validation.
     * @param timestamp Timestamp string to validate.
     * @return null if valid, else error message from ChatConstants.
     */
    public static String validateTimestamp(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return ChatConstants.ERROR_INVALID_TIMESTAMP;
        }

        // Try parsing as Instant first (for Z-suffixed timestamps)
        try {
            Instant.parse(timestamp.trim());
            return null;
        } catch (DateTimeParseException ex) {
            // If Instant parsing fails, try OffsetDateTime (for timezone offsets)
            try {
                OffsetDateTime.parse(timestamp.trim());
                return null;
            } catch (DateTimeParseException ex2) {
                // Both parsing methods failed - invalid format
                return ChatConstants.ERROR_INVALID_TIMESTAMP;
            }
        }
    }

    /**
     * Validates the message type field.
     * @param messageType Message type string to validate.
     * @return null if valid, else error message from ChatConstants.
     */
    public static String validateMessageType(String messageType) {
        if (messageType == null || messageType.trim().isEmpty()) {
            return ChatConstants.ERROR_INVALID_MESSAGE_TYPE;
        }

        // Check against allowed message types
        for (String allowed : ChatConstants.MESSAGE_TYPES) {
            if (allowed.equals(messageType.trim())) {
                return null;
            }
        }

        return ChatConstants.ERROR_INVALID_MESSAGE_TYPE;
    }

    private ValidationUtils() {
        throw new AssertionError("This utility class cannot be instantiated.");
    }
}
