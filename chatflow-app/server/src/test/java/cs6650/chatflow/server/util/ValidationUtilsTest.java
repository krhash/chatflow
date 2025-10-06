package cs6650.chatflow.server.util;

import cs6650.chatflow.server.model.ChatCommand;
import cs6650.chatflow.server.commons.ChatConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ValidationUtils class.
 */
class ValidationUtilsTest {

    private ChatCommand validCommand;

    @BeforeEach
    void setUp() {
        validCommand = new ChatCommand();
        validCommand.setUserId("12345");
        validCommand.setUsername("testuser123");
        validCommand.setMessage("Hello, World!");
        validCommand.setTimestamp("2023-12-01T12:00:00Z");
        validCommand.setMessageType("TEXT");
    }

    // ========== COMPREHENSIVE VALIDATION TESTS ==========

    @Test
    @DisplayName("Valid command should pass all validation")
    void testValidCommand() {
        String result = ValidationUtils.validate(validCommand);
        assertNull(result, "Valid command should pass validation");
    }

    // ========== USER ID VALIDATION TESTS ==========

    @Test
    @DisplayName("User ID validation - valid range")
    void testValidUserIds() {
        String[] validUserIds = {"1", "100000", "50000", "99999"};

        for (String userId : validUserIds) {
            validCommand.setUserId(userId);
            String result = ValidationUtils.validate(validCommand);
            assertNull(result, "User ID " + userId + " should be valid");
        }
    }

    @ParameterizedTest
    @DisplayName("User ID validation - invalid range")
    @CsvSource({
        "0, UserId out of range",
        "-1, UserId out of range",
        "100001, UserId out of range",
        "999999, UserId out of range"
    })
    void testInvalidUserIdRange(String userId, String expectedError) {
        validCommand.setUserId(userId);
        String result = ValidationUtils.validate(validCommand);
        assertEquals(expectedError, result);
    }

    @ParameterizedTest
    @DisplayName("User ID validation - invalid formats")
    @ValueSource(strings = {"", "abc", "12.34", "1a2b3c", " "})
    void testInvalidUserIdFormats(String userId) {
        validCommand.setUserId(userId);
        String result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_USER_ID, result);
    }

    // ========== USERNAME VALIDATION TESTS ==========

    @ParameterizedTest
    @DisplayName("Username validation - valid usernames")
    @ValueSource(strings = {
        "abc", "ABC", "123", "a1b2c3",
        "user123", "testUser", "myName",
        "abcdefghijk", "ABCDEFGHIJK", "abc123def456",
        "a1B2c3D4e5", "userName123", "testUserName"
    })
    void testValidUsernames(String username) {
        validCommand.setUsername(username);
        String result = ValidationUtils.validate(validCommand);
        assertNull(result, "Username '" + username + "' should be valid");
    }

    @ParameterizedTest
    @DisplayName("Username validation - too short")
    @ValueSource(strings = {"", "a", "ab", "1", "12"})
    void testUsernameTooShort(String username) {
        validCommand.setUsername(username);
        String result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_USERNAME, result);
    }

    @ParameterizedTest
    @DisplayName("Username validation - too long")
    @ValueSource(strings = {
        "abcdefghijklmnopqrstu", // 21 chars - should fail
        "thisUsernameIsWayTooLongForTheValidation" // 40+ chars - should fail
    })
    void testUsernameTooLong(String username) {
        validCommand.setUsername(username);
        String result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_USERNAME, result);
    }

    @Test
    @DisplayName("Username validation - exactly 20 chars should be valid")
    void testUsernameExactlyMaxLength() {
        String exactly20Chars = "abcdefghijklmnopqrst"; // exactly 20 chars
        validCommand.setUsername(exactly20Chars);
        String result = ValidationUtils.validate(validCommand);
        assertNull(result, "Username with exactly 20 characters should be valid");
    }

    @ParameterizedTest
    @DisplayName("Username validation - invalid characters")
    @ValueSource(strings = {
        "user_name", "user-name", "user.name", "user@name",
        "user#name", "user$name", "user%name", "user&name",
        "user*name", "user(name)", "user+name", "user=name",
        "user{name}", "user[name]", "user|name", "user\\name",
        "user?name", "user<name>", "user\"name", "user'name",
        "user name", "user\tname", "user\nname", "user\rname"
    })
    void testUsernameInvalidCharacters(String username) {
        validCommand.setUsername(username);
        String result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_USERNAME, result);
    }

    // ========== MESSAGE VALIDATION TESTS ==========

    @Test
    @DisplayName("Message validation - valid lengths")
    void testValidMessageLengths() {
        // Test minimum length (1 char)
        validCommand.setMessage("a");
        assertNull(ValidationUtils.validate(validCommand));

        // Test maximum length (500 chars)
        String maxMessage = "a".repeat(500);
        validCommand.setMessage(maxMessage);
        assertNull(ValidationUtils.validate(validCommand));

        // Test medium length
        validCommand.setMessage("This is a test message with normal length.");
        assertNull(ValidationUtils.validate(validCommand));
    }

    @Test
    @DisplayName("Message validation - empty/null message")
    void testEmptyMessage() {
        validCommand.setMessage("");
        String result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_MESSAGE_LENGTH, result);

        validCommand.setMessage(null);
        result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_MESSAGE_LENGTH, result);
    }

    @Test
    @DisplayName("Message validation - too long")
    void testMessageTooLong() {
        String tooLongMessage = "a".repeat(501); // 501 characters
        validCommand.setMessage(tooLongMessage);
        String result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_MESSAGE_LENGTH, result);
    }

    // ========== TIMESTAMP VALIDATION TESTS ==========

    @ParameterizedTest
    @DisplayName("Timestamp validation - valid ISO-8601 formats")
    @ValueSource(strings = {
        "2023-12-01T12:00:00Z",
        "2023-12-01T12:00:00.123Z",
        "2023-12-01T12:00:00+00:00",
        "2023-12-01T12:00:00.123+05:30",
        "2023-12-01T12:00:00-08:00",
        "2023-01-31T23:59:59.999Z",
        "2023-02-28T12:30:45Z"
    })
    void testValidTimestamps(String timestamp) {
        validCommand.setTimestamp(timestamp);
        String result = ValidationUtils.validate(validCommand);
        assertNull(result, "Timestamp '" + timestamp + "' should be valid");
    }

    @ParameterizedTest
    @DisplayName("Timestamp validation - invalid formats")
    @ValueSource(strings = {
        "",
        "2023-12-01",
        "12:00:00",
        "2023-12-01 12:00:00",
        "2023-12-01T12:00:00",
        "2023-12-01T12:00:00.123",
        "2023-12-01T12:00:00XYZ",
        "23-12-01T12:00:00Z",
        "2023-13-01T12:00:00Z",
        "2023-12-32T12:00:00Z",
        "2023-12-01T25:00:00Z",
        "2023-12-01T12:60:00Z",
        "2023-12-01T12:00:60Z",
        "not-a-timestamp",
        "null",
        "undefined"
    })
    void testInvalidTimestamps(String timestamp) {
        validCommand.setTimestamp(timestamp);
        String result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_TIMESTAMP, result);
    }

    // ========== MESSAGE TYPE VALIDATION TESTS ==========

    @ParameterizedTest
    @DisplayName("Message type validation - valid types")
    @ValueSource(strings = {"TEXT", "JOIN", "LEAVE"})
    void testValidMessageTypes(String messageType) {
        validCommand.setMessageType(messageType);
        String result = ValidationUtils.validate(validCommand);
        assertNull(result, "Message type '" + messageType + "' should be valid");
    }

    @ParameterizedTest
    @DisplayName("Message type validation - invalid types")
    @ValueSource(strings = {
        "",
        "text",
        "join",
        "leave",
        "TEXT_MESSAGE",
        "CHAT",
        "MESSAGE",
        "EMOJI",
        "FILE",
        "IMAGE",
        "UNKNOWN",
        "null",
        "undefined",
        "1",
        "123",
        "TEXT_EXTRA",
        "JOIN_ROOM",
        "LEAVE_CHAT"
    })
    void testInvalidMessageTypes(String messageType) {
        validCommand.setMessageType(messageType);
        String result = ValidationUtils.validate(validCommand);
        assertEquals(ChatConstants.ERROR_INVALID_MESSAGE_TYPE, result);
    }

    // ========== EDGE CASES AND BOUNDARY TESTS ==========

    @Test
    @DisplayName("Null command should be handled gracefully")
    void testNullCommand() {
        assertThrows(NullPointerException.class, () -> {
            ValidationUtils.validate(null);
        });
    }

    @Test
    @DisplayName("Command with all null fields should fail validation")
    void testAllNullFields() {
        ChatCommand nullCommand = new ChatCommand();
        // All fields are null by default

        String result = ValidationUtils.validate(nullCommand);
        assertNotNull(result);
        assertTrue(result.contains("invalid") || result.contains("Invalid"));
    }

    @Test
    @DisplayName("Boundary values for user ID")
    void testUserIdBoundaries() {
        // Test exact boundaries
        validCommand.setUserId("1"); // Minimum
        assertNull(ValidationUtils.validate(validCommand));

        validCommand.setUserId("100000"); // Maximum
        assertNull(ValidationUtils.validate(validCommand));
    }

    @Test
    @DisplayName("Boundary values for message length")
    void testMessageLengthBoundaries() {
        // Test exact boundaries
        validCommand.setMessage("a"); // Minimum (1 char)
        assertNull(ValidationUtils.validate(validCommand));

        String maxMessage = "a".repeat(500); // Maximum (500 chars)
        validCommand.setMessage(maxMessage);
        assertNull(ValidationUtils.validate(validCommand));
    }

    @Test
    @DisplayName("Boundary values for username length")
    void testUsernameLengthBoundaries() {
        // Test exact boundaries (3 and 20 chars)
        validCommand.setUsername("abc"); // Minimum (3 chars)
        assertNull(ValidationUtils.validate(validCommand));

        validCommand.setUsername("abcdefghijklmnopqrst"); // Maximum (20 chars)
        assertNull(ValidationUtils.validate(validCommand));
    }

    // ========== ASSIGNMENT REQUIREMENTS VERIFICATION ==========

    @Test
    @DisplayName("Assignment Requirement: userId must be between 1 and 100000")
    void verifyUserIdRequirement() {
        // Test minimum boundary
        validCommand.setUserId("1");
        assertNull(ValidationUtils.validate(validCommand));

        // Test maximum boundary
        validCommand.setUserId("100000");
        assertNull(ValidationUtils.validate(validCommand));

        // Test just outside boundaries
        validCommand.setUserId("0");
        assertEquals(ChatConstants.ERROR_USER_ID_OUT_OF_RANGE, ValidationUtils.validate(validCommand));

        validCommand.setUserId("100001");
        assertEquals(ChatConstants.ERROR_USER_ID_OUT_OF_RANGE, ValidationUtils.validate(validCommand));
    }

    @Test
    @DisplayName("Assignment Requirement: username must be 3-20 alphanumeric characters")
    void verifyUsernameRequirement() {
        // Test minimum length
        validCommand.setUsername("abc");
        assertNull(ValidationUtils.validate(validCommand));

        // Test maximum length
        validCommand.setUsername("abcdefghijklmnopqrst");
        assertNull(ValidationUtils.validate(validCommand));

        // Test alphanumeric only (no underscores, hyphens, etc.)
        validCommand.setUsername("user_name");
        assertEquals(ChatConstants.ERROR_INVALID_USERNAME, ValidationUtils.validate(validCommand));

        validCommand.setUsername("test-user");
        assertEquals(ChatConstants.ERROR_INVALID_USERNAME, ValidationUtils.validate(validCommand));
    }

    @Test
    @DisplayName("Assignment Requirement: message must be 1-500 characters")
    void verifyMessageRequirement() {
        // Test minimum length
        validCommand.setMessage("a");
        assertNull(ValidationUtils.validate(validCommand));

        // Test maximum length
        String maxMessage = "a".repeat(500);
        validCommand.setMessage(maxMessage);
        assertNull(ValidationUtils.validate(validCommand));

        // Test just over limit
        String tooLongMessage = "a".repeat(501);
        validCommand.setMessage(tooLongMessage);
        assertEquals(ChatConstants.ERROR_INVALID_MESSAGE_LENGTH, ValidationUtils.validate(validCommand));
    }

    @Test
    @DisplayName("Assignment Requirement: messageType must be TEXT, JOIN, or LEAVE")
    void verifyMessageTypeRequirement() {
        String[] validTypes = {"TEXT", "JOIN", "LEAVE"};

        for (String type : validTypes) {
            validCommand.setMessageType(type);
            assertNull(ValidationUtils.validate(validCommand),
                      "Message type '" + type + "' should be valid");
        }

        // Test invalid types
        validCommand.setMessageType("CHAT");
        assertEquals(ChatConstants.ERROR_INVALID_MESSAGE_TYPE, ValidationUtils.validate(validCommand));

        validCommand.setMessageType("text");
        assertEquals(ChatConstants.ERROR_INVALID_MESSAGE_TYPE, ValidationUtils.validate(validCommand));
    }

    @Test
    @DisplayName("Assignment Requirement: timestamp must be valid ISO-8601")
    void verifyTimestampRequirement() {
        // Test valid ISO-8601 formats
        String[] validTimestamps = {
            "2023-12-01T12:00:00Z",
            "2023-12-01T12:00:00.123Z",
            "2023-12-01T12:00:00+05:30"
        };

        for (String timestamp : validTimestamps) {
            validCommand.setTimestamp(timestamp);
            assertNull(ValidationUtils.validate(validCommand),
                      "Timestamp '" + timestamp + "' should be valid");
        }

        // Test invalid formats
        validCommand.setTimestamp("2023-12-01 12:00:00");
        assertEquals(ChatConstants.ERROR_INVALID_TIMESTAMP, ValidationUtils.validate(validCommand));

        validCommand.setTimestamp("not-a-timestamp");
        assertEquals(ChatConstants.ERROR_INVALID_TIMESTAMP, ValidationUtils.validate(validCommand));
    }
}
