package cs6650.chatflow.client.workers;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.queues.DeadLetterQueue;
import cs6650.chatflow.client.websocket.WebSocketConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retry worker thread for main phase.
 * Processes messages from dead letter queue for retry attempts.
 */
public class MainPhaseRetryWorker implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(MainPhaseRetryWorker.class);

    private final WebSocketConnectionPool connectionPool;
    private final DeadLetterQueue deadLetterQueue;
    private volatile boolean running = true;

    /**
     * Creates retry worker.
     * @param connectionPool WebSocket connection pool for retries
     * @param deadLetterQueue queue for messages to retry
     */
    public MainPhaseRetryWorker(WebSocketConnectionPool connectionPool, DeadLetterQueue deadLetterQueue) {
        this.connectionPool = connectionPool;
        this.deadLetterQueue = deadLetterQueue;
    }

    @Override
    public void run() {
        logger.debug("Starting retry worker...");

        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                // Wait for messages to retry (blocking)
                ChatMessage message = deadLetterQueue.take();

                try {
                    logger.debug("Retrying message: {} with up to {} attempts", message.getMessageId(),
                        Constants.MESSAGE_RETRY_ATTEMPTS);

                    // Retry the message up to DLQ_RETRY_ATTEMPTS times with exponential backoff
                    boolean retrySuccess = retryMessageWithBackoff(message);

                    if (retrySuccess) {
                        logger.debug("Retry successful for message: {}", message.getMessageId());
                    } else {
                        logger.warn("All retry attempts failed for message: {}, message permanently failed",
                            message.getMessageId());
                        // Message has failed all retry attempts - do not re-queue
                    }

                } catch (Exception e) {
                    logger.error("Unexpected error retrying message: {}, message permanently failed: {}",
                        message.getMessageId(), e.getMessage());
                    // Do not re-queue on unexpected errors
                }

            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted, shutting down");
        }

        logger.debug("Retry worker stopped");
    }

    /**
     * Retries a message with exponential backoff.
     * @param message the message to retry
     * @return true if retry succeeded, false if all attempts failed
     */
    private boolean retryMessageWithBackoff(ChatMessage message) {
        long delay = cs6650.chatflow.client.commons.Constants.MESSAGE_RETRY_INITIAL_DELAY_MILLIS;

        for (int attempt = 1; attempt <= Constants.MESSAGE_RETRY_ATTEMPTS; attempt++) {
            try {
                logger.debug("Retry attempt {}/{} for message: {}", attempt,
                        Constants.MESSAGE_RETRY_ATTEMPTS, message.getMessageId());

                // Attempt to resend the message through the connection pool
                boolean success = connectionPool.sendMessage(message);

                if (success) {
                    logger.debug("Retry attempt {} succeeded for message: {}", attempt, message.getMessageId());
                    return true;
                }

                // If this was the last attempt, don't wait
                if (attempt == Constants.MESSAGE_RETRY_ATTEMPTS) {
                    logger.error("Retry attempt {} failed for message: {} - all attempts exhausted",
                        attempt, message.getMessageId());
                    break;
                }

                // Wait before next retry with exponential backoff
                logger.debug("Retry attempt {} failed for message: {}, waiting {}ms before next attempt",
                    attempt, message.getMessageId(), delay);
                Thread.sleep(delay);
                delay = Math.min(delay * 2, cs6650.chatflow.client.commons.Constants.MESSAGE_RETRY_MAX_DELAY_MILLIS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Interrupted during retry backoff for message: {}", message.getMessageId());
                return false;
            } catch (Exception e) {
                logger.warn("Retry attempt {} failed for message: {} with exception: {}",
                    attempt, message.getMessageId(), e.getMessage());

                // If this was the last attempt, don't wait
                if (attempt == Constants.MESSAGE_RETRY_ATTEMPTS) {
                    break;
                }

                // Wait before next retry even on exceptions
                try {
                    Thread.sleep(delay);
                    delay = Math.min(delay * 2, cs6650.chatflow.client.commons.Constants.MESSAGE_RETRY_MAX_DELAY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * Stops the retry worker.
     */
    public void stop() {
        running = false;
    }
}
