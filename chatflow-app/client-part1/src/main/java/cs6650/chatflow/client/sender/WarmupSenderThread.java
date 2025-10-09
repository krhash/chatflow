package cs6650.chatflow.client.sender;

import com.google.gson.Gson;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.util.MessageGenerator;

import cs6650.chatflow.client.model.MessageResponse;
import cs6650.chatflow.client.websocket.WebSocketClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Thread for executing WebSocket warmup phase.
 * Sends messages to server and tracks responses with retry logic.
 */
public class WarmupSenderThread implements Runnable {

    private static final Gson gson = new Gson();

    private final int totalMessages;
    private final String wsBaseUri;
    private final String roomId;
    private final URI uri;
    private final Map<String, Long> sentMessages;
    private final Map<String, Long> responseLatencies;
    private final CountDownLatch sendCompletionLatch;
    private final CountDownLatch responseCompletionLatch;
    private final AtomicInteger totalMessagesSent;
    private final AtomicInteger totalMessagesReceived;

    // Current connection's response latch
    private CountDownLatch currentResponseLatch;

    /**
     * Creates thread without global message counters.
     * @param wsBaseUri WebSocket base URI (ws://server:port/servlet-context)
     * @param roomId chat room identifier
     * @param totalMessages number of messages to send
     * @param sendCompletionLatch latch for send completion coordination
     * @param responseCompletionLatch latch for response completion coordination
     * @throws URISyntaxException if URI construction fails
     */
    public WarmupSenderThread(String wsBaseUri, String roomId,
                              int totalMessages, CountDownLatch sendCompletionLatch,
                              CountDownLatch responseCompletionLatch) throws URISyntaxException {
        this(wsBaseUri, roomId, totalMessages, sendCompletionLatch, responseCompletionLatch, null, null);
    }

    /**
     * Creates thread with global message counters.
     * @param wsBaseUri WebSocket base URI (ws://server:port/servlet-context)
     * @param roomId chat room identifier
     * @param totalMessages number of messages to send
     * @param sendCompletionLatch latch for send completion coordination
     * @param responseCompletionLatch latch for response completion coordination
     * @param totalMessagesSent global counter for sent messages
     * @param totalMessagesReceived global counter for received messages
     * @throws URISyntaxException if URI construction fails
     */
    public WarmupSenderThread(String wsBaseUri, String roomId,
                              int totalMessages, CountDownLatch sendCompletionLatch,
                              CountDownLatch responseCompletionLatch,
                              AtomicInteger totalMessagesSent, AtomicInteger totalMessagesReceived) throws URISyntaxException {
        this.totalMessages = totalMessages;
        this.wsBaseUri = wsBaseUri;
        this.roomId = roomId;
        this.sendCompletionLatch = sendCompletionLatch;
        this.responseCompletionLatch = responseCompletionLatch;
        this.totalMessagesSent = totalMessagesSent;
        this.totalMessagesReceived = totalMessagesReceived;

        // Pre-initialize objects
        this.uri = new URI(String.format("%s/chat/%s", wsBaseUri, roomId));
        // Pre-size HashMaps to avoid resizing (1000 messages / 0.75 load factor = ~1333 capacity)
        this.sentMessages = new ConcurrentHashMap<>(1400);
        this.responseLatencies = new ConcurrentHashMap<>(1400);
    }

    @Override
    public void run() {
        int messagesSent = 0;
        int reconnectAttempt = 0;
        boolean sendCompleted = false;

        // Step 1: Attempt to connect and send messages with reconnection on failure
        while (messagesSent < totalMessages && reconnectAttempt < Constants.MAX_RECONNECT_ATTEMPTS) {
            WebSocketClient client = null;
            try {
                // Step 2: Prepare for message sending
                prepareForSending(messagesSent);

                // Step 3: Connect to WebSocket server
                client = connectToServer(messagesSent);

                // Step 4: Send all remaining messages with retry logic
                messagesSent = sendAllMessages(client, messagesSent);

                // Step 5: Signal send completion (only once per thread)
                if (!sendCompleted && sendCompletionLatch != null) {
                    sendCompletionLatch.countDown();
                    sendCompleted = true;
                }

                // Step 6: Wait for all responses
                waitForAllResponses(client);

                // Step 7: Clean shutdown
                cleanup(client, "warmup complete");
                break; // Success - exit retry loop

            } catch (Exception e) {
                // Step 8: Handle connection failure - attempt reconnection
                handleConnectionFailure(++reconnectAttempt, e, client);
            }
        }

        // Step 9: Signal send completion if not already done (failed threads)
        if (!sendCompleted && sendCompletionLatch != null) {
            sendCompletionLatch.countDown();
        }

        // Step 10: Report final status
        reportFinalStatus(messagesSent);

        // Step 11: Signal response completion
        responseCompletionLatch.countDown();
    }

    /**
     * Clears message tracking data structures for reconnection.
     * @param messagesSent number of messages already sent (for context)
     */
    private void prepareForSending(int messagesSent) {
        sentMessages.clear();
        responseLatencies.clear();
    }

    /**
     * Creates and connects WebSocket client.
     * @param messagesSent number of messages already sent
     * @return connected WebSocket client
     * @throws Exception if connection fails
     */
    private WebSocketClient connectToServer(int messagesSent) throws Exception {
        currentResponseLatch = new CountDownLatch(totalMessages - messagesSent);

        // Create response handler for warmup phase
        Consumer<MessageResponse> responseHandler = response -> {
            try {
                // Parse response and correlate with sent message
                ChatMessage responseMessage = gson.fromJson(response.getMessageJson(), ChatMessage.class);

                if (responseMessage.getMessageId() != null) {
                    Long sentTime = sentMessages.get(responseMessage.getMessageId());
                    if (sentTime != null) {
                        long latencyNanos = response.getReceivedTimestampNanos() - sentTime;
                        responseLatencies.put(responseMessage.getMessageId(), latencyNanos);
                    }
                }

                // Increment global counter
                if (totalMessagesReceived != null) {
                    totalMessagesReceived.incrementAndGet();
                }

                // Signal response received
                currentResponseLatch.countDown();

            } catch (Exception e) {
                System.err.println("Error processing warmup response: " + e.getMessage());
                // Even on error, count it as a response to avoid hanging
                currentResponseLatch.countDown();
            }
        };

        WebSocketClient client = new WebSocketClient(uri, responseHandler);
        client.connectBlocking();
        return client;
    }

    /**
     * Sends all remaining messages for this thread.
     * @param client WebSocket client to send through
     * @param messagesSent number of messages already sent
     * @return updated count of messages sent
     */
    private int sendAllMessages(WebSocketClient client, int messagesSent) {
        for (int i = messagesSent; i < totalMessages; i++) {
            try {
                sendMessageWithRetry(client, MessageGenerator.generateRandomMessage());
                messagesSent++;
            } catch (IllegalStateException e) {
                // Connection closed - break out to reconnection logic
                throw e;
            } catch (Exception e) {
                // Message failed permanently - log and continue
                System.err.printf("Failed to send message after %d retries: %s%n",
                    Constants.MESSAGE_RETRY_ATTEMPTS, e.getMessage());
            }
        }
        return messagesSent;
    }

    /**
     * Sends a single message with retry logic.
     * @param client WebSocket client to send through
     * @param message message to send
     * @throws Exception if all retry attempts fail
     */
    private void sendMessageWithRetry(WebSocketClient client, ChatMessage message) throws Exception {
        String messageJson = gson.toJson(message);
        long delay = Constants.MESSAGE_RETRY_INITIAL_DELAY_MILLIS;

        for (int attempt = 1; attempt <= Constants.MESSAGE_RETRY_ATTEMPTS; attempt++) {
            try {
                // Check if connection is still open
                if (!client.isOpen()) {
                    throw new IllegalStateException("WebSocket connection is closed");
                }

                // Record send time and send message
                sentMessages.put(message.getMessageId(), System.nanoTime());
                client.send(messageJson);

                // Increment global counter
                if (totalMessagesSent != null) {
                    totalMessagesSent.incrementAndGet();
                }

                // Success - return
                return;

            } catch (IllegalStateException e) {
                // Connection closed - don't retry, let reconnection logic handle it
                throw e;
            } catch (Exception e) {
                if (attempt == Constants.MESSAGE_RETRY_ATTEMPTS) {
                    // All retries exhausted
                    throw new RuntimeException("Failed to send message after " + Constants.MESSAGE_RETRY_ATTEMPTS + " attempts", e);
                }

                // Wait before retry with exponential backoff
                try {
                    Thread.sleep(delay);
                    delay = Math.min(delay * 2, Constants.MESSAGE_RETRY_MAX_DELAY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during message retry", ie);
                }
            }
        }
    }

    /**
     * Waits for all expected responses with timeout.
     * @param client WebSocket client (for compatibility)
     * @throws InterruptedException if thread is interrupted
     */
    private void waitForAllResponses(WebSocketClient client) throws InterruptedException {
        boolean allReceived = currentResponseLatch.await(Constants.RESPONSE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

        if (!allReceived) {
            System.err.printf("Thread %s timed out waiting for responses. Received %d/%d%n",
                    Thread.currentThread().getName(), responseLatencies.size(), totalMessages);
        }
    }

    /**
     * Cleans up WebSocket connection.
     * @param client WebSocket client to close
     * @param reason close reason for logging
     * @throws Exception if close operation fails
     */
    private void cleanup(WebSocketClient client, String reason) throws Exception {
        if (client != null) {
            client.close(1000, reason);
        }
    }

    /**
     * Handles connection failure with exponential backoff.
     * @param attempt current reconnection attempt number
     * @param e exception that caused failure
     * @param client WebSocket client to clean up
     */
    private void handleConnectionFailure(int attempt, Exception e, WebSocketClient client) {
        System.err.printf("Thread %s connection failed on attempt %d: %s%n",
                Thread.currentThread().getName(), attempt, e.getMessage());

        if (client != null) {
            client.close();
        }

        // Exponential backoff before retry
        if (attempt < Constants.MAX_RECONNECT_ATTEMPTS) {
            try {
                Thread.sleep(Constants.BASE_BACKOFF_MILLIS * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Reports final thread status if messages were not fully sent.
     * @param messagesSent number of messages successfully sent
     */
    private void reportFinalStatus(int messagesSent) {
        if (messagesSent < totalMessages) {
            System.err.printf("Thread %s failed to send %d/%d messages after %d attempts%n",
                    Thread.currentThread().getName(), messagesSent, totalMessages, Constants.MAX_RECONNECT_ATTEMPTS);
        }
    }
} // end class
