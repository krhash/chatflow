package cs6650.chatflow.client.workers;

import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.queues.DeadLetterQueue;
import cs6650.chatflow.client.util.MessageTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors message timeouts and moves failed messages to dead letter queue.
 * Runs periodically to check for messages that haven't received responses.
 */
public class TimeoutMonitor implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(TimeoutMonitor.class);

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
        logger.debug("Starting timeout monitoring...");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Check for timed-out messages
                ChatMessage[] timedOutMessages = messageTimer.getTimedOutMessages();

                if (timedOutMessages.length > 0) {
                    logger.info("Found {} timed-out messages", timedOutMessages.length);

                    // Move timed-out messages to dead letter queue
                    for (ChatMessage failedMessage : timedOutMessages) {
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
            logger.debug("Interrupted, shutting down");
        }
    }
}
