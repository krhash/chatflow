package cs6650.chatflow.client.workers;

import com.google.gson.Gson;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.util.MessageGenerator;

import cs6650.chatflow.client.model.MessageResponse;
import cs6650.chatflow.client.websocket.ChatflowWebSocketClient;

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
public class WarmupWorker implements Runnable {

    private static final Gson gson = new Gson();

    private final int totalMessages;
    private final URI uri;
    private final Map<String, Long> sentMessages;
    private final Map<String, Long> responseLatencies;
    private final Map<String, ChatMessage> pendingMessages;
    private final CountDownLatch sendCompletionLatch;
    private final CountDownLatch responseCompletionLatch;
    private final AtomicInteger totalMessagesSent;
    private final AtomicInteger totalMessagesReceived;
    private final AtomicInteger reconnections;
    private final AtomicInteger connections;

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
    public WarmupWorker(String wsBaseUri, String roomId,
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
    public WarmupWorker(String wsBaseUri, String roomId,
                        int totalMessages, CountDownLatch sendCompletionLatch,
                        CountDownLatch responseCompletionLatch,
                        AtomicInteger totalMessagesSent, AtomicInteger totalMessagesReceived) throws URISyntaxException {
        this(wsBaseUri, roomId, totalMessages, sendCompletionLatch, responseCompletionLatch,
             totalMessagesSent, totalMessagesReceived, null);
    }

    /**
     * Creates thread with global message counters and reconnection tracking.
     * @param wsBaseUri WebSocket base URI (ws://server:port/servlet-context)
     * @param roomId chat room identifier
     * @param totalMessages number of messages to send
     * @param sendCompletionLatch latch for send completion coordination
     * @param responseCompletionLatch latch for response completion coordination
     * @param totalMessagesSent global counter for sent messages
     * @param totalMessagesReceived global counter for received messages
     * @param reconnections global counter for reconnection attempts
     * @throws URISyntaxException if URI construction fails
     */
    public WarmupWorker(String wsBaseUri, String roomId,
                        int totalMessages, CountDownLatch sendCompletionLatch,
                        CountDownLatch responseCompletionLatch,
                        AtomicInteger totalMessagesSent, AtomicInteger totalMessagesReceived,
                        AtomicInteger reconnections) throws URISyntaxException {
        this(wsBaseUri, roomId, totalMessages, sendCompletionLatch, responseCompletionLatch,
             totalMessagesSent, totalMessagesReceived, reconnections, null);
    }

    /**
     * Creates thread with global message counters, reconnection tracking, and connection counting.
     * @param wsBaseUri WebSocket base URI (ws://server:port/servlet-context)
     * @param roomId chat room identifier
     * @param totalMessages number of messages to send
     * @param sendCompletionLatch latch for send completion coordination
     * @param responseCompletionLatch latch for response completion coordination
     * @param totalMessagesSent global counter for sent messages
     * @param totalMessagesReceived global counter for received messages
     * @param reconnections global counter for reconnection attempts
     * @param connections global counter for successful connections
     * @throws URISyntaxException if URI construction fails
     */
    public WarmupWorker(String wsBaseUri, String roomId,
                        int totalMessages, CountDownLatch sendCompletionLatch,
                        CountDownLatch responseCompletionLatch,
                        AtomicInteger totalMessagesSent, AtomicInteger totalMessagesReceived,
                        AtomicInteger reconnections, AtomicInteger connections) throws URISyntaxException {
        this.totalMessages = totalMessages;
        this.sendCompletionLatch = sendCompletionLatch;
        this.responseCompletionLatch = responseCompletionLatch;
        this.totalMessagesSent = totalMessagesSent;
        this.totalMessagesReceived = totalMessagesReceived;
        this.reconnections = reconnections;
        this.connections = connections;

        // Pre-initialize objects
        this.uri = new URI(String.format("%s/chat/%s", wsBaseUri, roomId));
        // Pre-size HashMaps to avoid resizing (1000 messages / 0.75 load factor = ~1333 capacity)
        this.sentMessages = new ConcurrentHashMap<>(1400);
        this.responseLatencies = new ConcurrentHashMap<>(1400);
        this.pendingMessages = new ConcurrentHashMap<>(1400);
    }

    @Override
    public void run() {
        int messagesSent = 0;
        int reconnectAttempt = 0;
        boolean sendCompleted = false;

        // Step 1: Attempt to connect and send messages with reconnection on failure
        while (messagesSent < totalMessages && reconnectAttempt < Constants.MAX_RECONNECT_ATTEMPTS) {
            ChatflowWebSocketClient client = null;
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

                // Step 7: Retry any timed-out messages
                retryTimedOutMessages(client);

                // Step 8: Clean shutdown
                cleanup(client);
                break; // Success

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
    private ChatflowWebSocketClient connectToServer(int messagesSent) throws Exception {
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
                    // Remove from pending - response received successfully
                    pendingMessages.remove(responseMessage.getMessageId());
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

        ChatflowWebSocketClient client = new ChatflowWebSocketClient(uri, responseHandler);
        client.connectBlocking();

        // Track successful connection
        if (connections != null) {
            connections.incrementAndGet();
        }

        return client;
    }

    /**
     * Sends all remaining messages for this thread.
     * @param client WebSocket client to send through
     * @param messagesSent number of messages already sent
     * @return updated count of messages sent
     */
    private int sendAllMessages(ChatflowWebSocketClient client, int messagesSent) {
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
    private void sendMessageWithRetry(ChatflowWebSocketClient client, ChatMessage message) throws Exception {
        String messageJson = gson.toJson(message);
        long delay = Constants.MESSAGE_RETRY_INITIAL_DELAY_MILLIS;

        for (int attempt = 1; attempt <= Constants.MESSAGE_RETRY_ATTEMPTS; attempt++) {
            try {
                // Check if connection is still open
                if (!client.isOpen()) {
                    throw new IllegalStateException("WebSocket connection is closed");
                }

                // Record send time and track pending message
                sentMessages.put(message.getMessageId(), System.nanoTime());
                pendingMessages.put(message.getMessageId(), message);
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
    private void waitForAllResponses(ChatflowWebSocketClient client) throws InterruptedException {
        boolean allReceived = currentResponseLatch.await(Constants.WARMUP_RESPONSE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

        if (!allReceived) {
            System.err.printf("Thread %s timed out waiting for responses. Received %d/%d%n",
                    Thread.currentThread().getName(), responseLatencies.size(), totalMessages);
        }
    }

    /**
     * Cleans up WebSocket connection.
     *
     * @param client WebSocket client to close
     * @throws Exception if close operation fails
     */
    private void cleanup(ChatflowWebSocketClient client) throws Exception {
        if (client != null) {
            client.close(1000, "warmup complete");
        }
    }

    /**
     * Handles connection failure with exponential backoff.
     * @param attempt current reconnection attempt number
     * @param e exception that caused failure
     * @param client WebSocket client to clean up
     */
    private void handleConnectionFailure(int attempt, Exception e, ChatflowWebSocketClient client) {
        System.err.printf("Thread %s connection failed on attempt %d: %s%n",
                Thread.currentThread().getName(), attempt, e.getMessage());

        if (client != null) {
            client.close();
        }

        // Track reconnection attempt
        if (reconnections != null) {
            reconnections.incrementAndGet();
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
     * Retries messages that timed out waiting for responses.
     * @param client WebSocket client to use for retries
     */
    private void retryTimedOutMessages(ChatflowWebSocketClient client) {
        if (pendingMessages.isEmpty()) {
            return; // All messages received responses
        }

        System.out.printf("Thread %s retrying %d timed-out messages...%n",
            Thread.currentThread().getName(), pendingMessages.size());

        int retrySuccessCount = 0;
        for (ChatMessage message : pendingMessages.values()) {
            if (retryMessageWithBackoff(client, message)) {
                retrySuccessCount++;
            }
        }

        int remainingFailed = pendingMessages.size();
        if (remainingFailed > 0) {
            System.err.printf("Thread %s: %d messages failed permanently after retry%n",
                Thread.currentThread().getName(), remainingFailed);
        } else {
            System.out.printf("Thread %s: All %d timed-out messages successfully retried%n",
                Thread.currentThread().getName(), retrySuccessCount);
        }
    }

    /**
     * Retries a single message with exponential backoff.
     * @param client WebSocket client
     * @param message message to retry
     * @return true if retry succeeded, false if failed
     */
    private boolean retryMessageWithBackoff(ChatflowWebSocketClient client, ChatMessage message) {
        long delay = Constants.MESSAGE_RETRY_INITIAL_DELAY_MILLIS;

        for (int attempt = 1; attempt <= Constants.MESSAGE_RETRY_ATTEMPTS; attempt++) {
            try {
                if (!client.isOpen()) {
                    System.err.printf("Client closed during retry attempt %d for message %s%n",
                        attempt, message.getMessageId());
                    return false;
                }

                // Update send timestamp for latency tracking
                sentMessages.put(message.getMessageId(), System.nanoTime());

                // Send message
                String messageJson = gson.toJson(message);
                client.send(messageJson);

                // Wait for response with timeout
                Thread.sleep(1000); // Wait 1 second for response

                // Check if response was received (message removed from pending)
                if (!pendingMessages.containsKey(message.getMessageId())) {
                    return true; // Success
                }

            } catch (Exception e) {
                System.err.printf("Retry attempt %d failed for message %s: %s%n",
                    attempt, message.getMessageId(), e.getMessage());
            }

            // Wait before next attempt (except on last attempt)
            if (attempt < Constants.MESSAGE_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(delay);
                    delay = Math.min(delay * 2, Constants.MESSAGE_RETRY_MAX_DELAY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false; // All attempts failed
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
