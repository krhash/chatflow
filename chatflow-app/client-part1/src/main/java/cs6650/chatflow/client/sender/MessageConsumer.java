package cs6650.chatflow.client.sender;

import com.google.gson.Gson;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.util.MessageQueue;
import cs6650.chatflow.client.util.MessageTimer;
import cs6650.chatflow.client.websocket.WebSocketConnectionPool;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumer thread that takes messages from queue and sends them via WebSocket connection pool.
 * Implements retry logic and tracks message send timestamps.
 */
public class MessageConsumer implements Runnable {

    private final MessageQueue messageQueue;
    private final WebSocketConnectionPool connectionPool;
    private final MessageTimer messageTimer;
    private final AtomicInteger messagesSent;
    private final Gson gson = new Gson();

    /**
     * Creates message consumer.
     * @param messageQueue queue to consume messages from
     * @param connectionPool WebSocket connection pool
     * @param messageTimer timer for tracking message timeouts
     * @param messagesSent global counter for sent messages
     */
    public MessageConsumer(MessageQueue messageQueue, WebSocketConnectionPool connectionPool,
                          MessageTimer messageTimer, AtomicInteger messagesSent) {
        this.messageQueue = messageQueue;
        this.connectionPool = connectionPool;
        this.messageTimer = messageTimer;
        this.messagesSent = messagesSent;
    }

    @Override
    public void run() {
        System.out.println("MessageConsumer: Starting message consumption...");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Get next message from queue
                ChatMessage message = messageQueue.take();

                // Send message with retry logic
                sendMessageWithRetry(message);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("MessageConsumer: Interrupted, shutting down");
        }
    }

    /**
     * Sends a message with retry logic.
     * @param message message to send
     */
    private void sendMessageWithRetry(ChatMessage message) {
        String messageJson = gson.toJson(message);
        long delay = Constants.MESSAGE_RETRY_INITIAL_DELAY_MILLIS;

        for (int attempt = 1; attempt <= Constants.MESSAGE_RETRY_ATTEMPTS; attempt++) {
            try {
                // Get connection from pool
                org.java_websocket.client.WebSocketClient connection = connectionPool.getNextConnection();

                // Check if connection is open
                if (!connection.isOpen()) {
                    throw new IllegalStateException("WebSocket connection is closed");
                }

                // Record send timestamp for timeout tracking
                messageTimer.recordMessageSent(message);

                // Send message
                connection.send(messageJson);

                // Increment global counter
                messagesSent.incrementAndGet();

                // Success - return
                return;

            } catch (IllegalStateException e) {
                // Connection closed - don't retry, connection pool should handle this
                System.err.printf("Connection closed for message %s, skipping%n", message.getMessageId());
                return;
            } catch (Exception e) {
                if (attempt == Constants.MESSAGE_RETRY_ATTEMPTS) {
                    // All retries exhausted
                    System.err.printf("Failed to send message %s after %d attempts: %s%n",
                        message.getMessageId(), Constants.MESSAGE_RETRY_ATTEMPTS, e.getMessage());
                    return;
                }

                // Wait before retry with exponential backoff
                try {
                    Thread.sleep(delay);
                    delay = Math.min(delay * 2, Constants.MESSAGE_RETRY_MAX_DELAY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
