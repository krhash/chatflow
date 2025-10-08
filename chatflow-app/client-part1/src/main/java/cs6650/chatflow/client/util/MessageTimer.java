package cs6650.chatflow.client.util;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks message send timestamps for timeout detection.
 * Used by TimeoutMonitor to identify messages that haven't received responses.
 */
public class MessageTimer {

    private final ConcurrentMap<String, Long> messageTimestamps = new ConcurrentHashMap<>();

    /**
     * Records the send timestamp for a message.
     * @param message the message being sent
     */
    public void recordMessageSent(ChatMessage message) {
        messageTimestamps.put(message.getMessageId(), System.nanoTime());
    }

    /**
     * Records that a response was received for a message.
     * @param messageId the ID of the message that received a response
     */
    public void recordMessageResponse(String messageId) {
        messageTimestamps.remove(messageId);
    }

    /**
     * Gets all message IDs that have timed out.
     * @return array of timed-out message IDs
     */
    public String[] getTimedOutMessages() {
        long currentTime = System.nanoTime();
        long timeoutNanos = Constants.MESSAGE_TIMEOUT_MILLIS * 1_000_000L; // Convert to nanoseconds

        return messageTimestamps.entrySet().stream()
            .filter(entry -> (currentTime - entry.getValue()) > timeoutNanos)
            .map(entry -> entry.getKey())
            .toArray(String[]::new);
    }

    /**
     * Removes timed-out messages from tracking.
     * @param messageIds array of message IDs to remove
     */
    public void removeTimedOutMessages(String[] messageIds) {
        for (String messageId : messageIds) {
            messageTimestamps.remove(messageId);
        }
    }

    /**
     * Gets the current number of messages being tracked.
     * @return number of outstanding messages
     */
    public int getOutstandingMessageCount() {
        return messageTimestamps.size();
    }

    /**
     * Clears all message tracking data.
     */
    public void clear() {
        messageTimestamps.clear();
    }
}
