package cs6650.chatflow.server.util;

import cs6650.chatflow.server.model.ChatCommand;
import cs6650.chatflow.server.commons.Constants;

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
    private static final Pattern USERNAME_PATTERN = Pattern.compile(Constants.USERNAME_REGEX);

    /**
     * Validates the fields of a ChatCommand.
     * @param cmd ChatCommand object to validate.
     * @return null if valid, else error message string from ChatConstants.
     */
    public static String validate(ChatCommand cmd) {
        // Validate message ID
        String messageIdValidation = validateMessageId(cmd.getMessageId());
        if (messageIdValidation != null) {
            return messageIdValidation;
        }

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
            return Constants.ERROR_INVALID_USER_ID;
        }

        try {
            int userIdInt = Integer.parseInt(userId.trim());

            if (userIdInt < Constants.USER_ID_MIN || userIdInt > Constants.USER_ID_MAX) {
                return Constants.ERROR_USER_ID_OUT_OF_RANGE;
            }
        } catch (NumberFormatException ex) {
            return Constants.ERROR_INVALID_USER_ID;
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
            return Constants.ERROR_INVALID_USERNAME;
        }

        // Use pre-compiled pattern - regex handles both length and character validation
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return Constants.ERROR_INVALID_USERNAME;
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
            return Constants.ERROR_INVALID_MESSAGE_LENGTH;
        }

        if (message.length() < Constants.MESSAGE_LENGTH_MIN || message.length() > Constants.MESSAGE_LENGTH_MAX) {
            return Constants.ERROR_INVALID_MESSAGE_LENGTH;
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
            return Constants.ERROR_INVALID_TIMESTAMP;
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
                return Constants.ERROR_INVALID_TIMESTAMP;
            }
        }
    }

    /**
     * Validates the message ID field.
     * @param messageId Message ID string to validate.
     * @return null if valid, else error message from ChatConstants.
     */
    public static String validateMessageId(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) {
            return "MessageId invalid";
        }

        // Basic UUID format validation (simplified)
        if (messageId.length() < 10) {
            return "MessageId invalid";
        }

        return null;
    }

    /**
     * Validates the message type field.
     * @param messageType Message type string to validate.
     * @return null if valid, else error message from ChatConstants.
     */
    public static String validateMessageType(String messageType) {
        if (messageType == null || messageType.trim().isEmpty()) {
            return Constants.ERROR_INVALID_MESSAGE_TYPE;
        }

        // Check against allowed message types
        for (String allowed : Constants.MESSAGE_TYPES) {
            if (allowed.equals(messageType.trim())) {
                return null;
            }
        }

        return Constants.ERROR_INVALID_MESSAGE_TYPE;
    }

    private ValidationUtils() {
        throw new AssertionError("This utility class cannot be instantiated.");
    }
}
