package cs6650.chatflow.client.util;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.model.MessageQueueEntry;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/**
 * Utility class for generating random chat messages and message queue entries.
 */
public class MessageGenerator {
    private static final Random random = new Random();

    /**
     * Generates a random message queue entry with random room ID for load testing.
     * Message types: 90% TEXT, 5% JOIN, 5% LEAVE
     * Room IDs: randomly distributed across 1-20
     * @return MessageQueueEntry with randomized message and target room ID
     */
    public static MessageQueueEntry generateRandomMessageEntry() {
        ChatMessage message = generateRandomMessage();
        int roomId = random.nextInt(Constants.ROOM_COUNT) + 1; // 1 to ROOM_COUNT
        return new MessageQueueEntry(message, roomId);
    }

    /**
     * Generates a random chat message for load testing.
     * Message types: 90% TEXT, 5% JOIN, 5% LEAVE
     * @return ChatMessage with randomized fields
     */
    public static ChatMessage generateRandomMessage() {
        String messageId = UUID.randomUUID().toString();

        int userId = random.nextInt(100_000) + 1;
        String username = "user" + userId;

        String message = Constants.MESSAGE_POOL[random.nextInt(Constants.MESSAGE_POOL.length)];

        // 90% TEXT, 5% JOIN, 5% LEAVE distribution
        String messageType;
        int typeRand = random.nextInt(100);
        if (typeRand < 90) {
            messageType = "TEXT";
        } else if (typeRand < 95) {
            messageType = "JOIN";
        } else {
            messageType = "LEAVE";
        }

        String timestamp = Instant.now().toString();

        return new ChatMessage(messageId, userId, username, message, timestamp, messageType);
    }

    /**
     * Generates a random room ID between 1 and 20.
     * @return room ID as string
     */
    public static String generateRandomRoomId() {
        return "room" + (random.nextInt(Constants.ROOM_COUNT) + 1);
    }
}
