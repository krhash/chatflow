package cs6650.chatflow.client.coordinator;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.util.DeadLetterQueue;
import cs6650.chatflow.client.util.MessageTimer;

/**
 * Monitors message timeouts and moves failed messages to dead letter queue.
 * Runs periodically to check for messages that haven't received responses.
 */
public class TimeoutMonitor implements Runnable {

    private final MessageTimer messageTimer;
    private final DeadLetterQueue deadLetterQueue;

    /**
     * Creates timeout monitor.
     * @param messageTimer timer tracking message send times
     * @param deadLetterQueue queue for failed messages
     */
    public TimeoutMonitor(MessageTimer messageTimer, DeadLetterQueue deadLetterQueue) {
        this.messageTimer = messageTimer;
        this.deadLetterQueue = deadLetterQueue;
    }

    @Override
    public void run() {
        System.out.println("TimeoutMonitor: Starting timeout monitoring...");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Check for timed-out messages
                String[] timedOutMessages = messageTimer.getTimedOutMessages();

                if (timedOutMessages.length > 0) {
                    System.out.printf("TimeoutMonitor: Found %d timed-out messages%n", timedOutMessages.length);

                    // Move timed-out messages to dead letter queue
                    for (String messageId : timedOutMessages) {
                        // In a real implementation, we'd need to store the actual message
                        // For now, we'll create a placeholder message for demonstration
                        ChatMessage failedMessage = new ChatMessage(messageId, 0, "unknown",
                            "Message timed out", "timeout", "TIMEOUT");
                        deadLetterQueue.add(failedMessage);
                    }

                    // Remove from timer tracking
                    messageTimer.removeTimedOutMessages(timedOutMessages);
                }

                // Sleep before next check
                Thread.sleep(1000); // Check every second
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("TimeoutMonitor: Interrupted, shutting down");
        }
    }
}
