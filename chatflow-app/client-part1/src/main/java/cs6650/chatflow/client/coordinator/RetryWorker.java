package cs6650.chatflow.client.coordinator;

import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.util.DeadLetterQueue;
import cs6650.chatflow.client.websocket.WebSocketConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retry worker thread for main phase.
 * Processes messages from dead letter queue for retry attempts.
 */
public class RetryWorker implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(RetryWorker.class);

    private final WebSocketConnectionPool connectionPool;
    private final DeadLetterQueue deadLetterQueue;
    private volatile boolean running = true;

    /**
     * Creates retry worker.
     * @param connectionPool WebSocket connection pool for retries
     * @param deadLetterQueue queue for messages to retry
     */
    public RetryWorker(WebSocketConnectionPool connectionPool, DeadLetterQueue deadLetterQueue) {
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
                    logger.debug("Retrying message: {}", message.getMessageId());

                    // Attempt to resend the message through the connection pool
                    boolean retrySuccess = connectionPool.sendMessage(message);

                    if (retrySuccess) {
                        logger.debug("Retry successful for message: {}", message.getMessageId());
                    } else {
                        logger.warn("Retry failed for message: {}, re-queuing for another attempt", message.getMessageId());
                        // Put back in queue for another retry attempt
                        deadLetterQueue.add(message);
                    }

                } catch (Exception e) {
                    logger.warn("Retry failed for message: {}, re-queuing for another attempt: {}",
                        message.getMessageId(), e.getMessage());
                    // Put back in queue for another retry attempt
                    deadLetterQueue.add(message);
                }

            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted, shutting down");
        }

        logger.debug("Retry worker stopped");
    }

    /**
     * Stops the retry worker.
     */
    public void stop() {
        running = false;
    }
}
