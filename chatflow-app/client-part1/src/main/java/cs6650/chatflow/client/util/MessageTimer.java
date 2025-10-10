package cs6650.chatflow.client.util;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks message send timestamps for timeout detection.
 * Used by TimeoutMonitor to identify messages that haven't received responses.
 *
 * Now stores complete ChatMessage objects instead of just timestamps,
 * ensuring that timed-out messages retain their original data for dead letter queue and retries.
 */
public class MessageTimer {

    /**
     * Entry containing both timestamp and the complete message data.
     */
    public static class MessageEntry {
        private final long timestamp;
        private final ChatMessage message;

        public MessageEntry(long timestamp, ChatMessage message) {
            this.timestamp = timestamp;
            this.message = message;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public ChatMessage getMessage() {
            return message;
        }
    }

    private final ConcurrentMap<String, MessageEntry> messageEntries = new ConcurrentHashMap<>();

    /**
     * Records the send timestamp for a message.
     * @param message the message being sent
     */
    public void recordMessageSent(ChatMessage message) {
        messageEntries.put(message.getMessageId(), new MessageEntry(System.nanoTime(), message));
    }

    /**
     * Records that a response was received for a message.
     * @param messageId the ID of the message that received a response
     */
    public void recordMessageResponse(String messageId) {
        messageEntries.remove(messageId);
    }

    /**
     * Gets all timed-out messages.
     * @return array of timed-out ChatMessage objects
     */
    public ChatMessage[] getTimedOutMessages() {
        long currentTime = System.nanoTime();
        long timeoutNanos = Constants.MESSAGE_TIMEOUT_MILLIS * 1_000_000L; // Convert to nanoseconds

        return messageEntries.entrySet().stream()
            .filter(entry -> (currentTime - entry.getValue().getTimestamp()) > timeoutNanos)
            .map(entry -> entry.getValue().getMessage())
            .toArray(ChatMessage[]::new);
    }

    /**
     * Removes timed-out messages from tracking.
     * @param messages array of ChatMessage objects to remove
     */
    public void removeTimedOutMessages(ChatMessage[] messages) {
        for (ChatMessage message : messages) {
            messageEntries.remove(message.getMessageId());
        }
    }

    /**
     * Gets the current number of messages being tracked.
     * @return number of outstanding messages
     */
    public int getOutstandingMessageCount() {
        return messageEntries.size();
    }

    /**
     * Clears all message tracking data.
     */
    public void clear() {
        messageEntries.clear();
    }
}
