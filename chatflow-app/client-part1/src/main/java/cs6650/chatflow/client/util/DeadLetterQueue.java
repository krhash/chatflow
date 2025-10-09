package cs6650.chatflow.client.util;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Queue for messages that failed to receive responses within timeout.
 * Used for final retry attempts and failure analysis.
 */
public class DeadLetterQueue {

    private final BlockingQueue<ChatMessage> queue;

    /**
     * Creates a dead letter queue with configured capacity.
     */
    public DeadLetterQueue() {
        this.queue = new ArrayBlockingQueue<>(Constants.DEAD_LETTER_QUEUE_CAPACITY);
    }

    /**
     * Adds a failed message to the queue.
     * @param message the message that timed out
     * @return true if added successfully, false if queue is full
     */
    public boolean add(ChatMessage message) {
        return queue.offer(message);
    }

    /**
     * Retrieves a message for retry processing.
     * @return the next message to retry, or null if empty
     */
    public ChatMessage poll() {
        return queue.poll();
    }

    /**
     * Retrieves and removes the head of the queue, waiting if necessary until an element becomes available.
     * @return the head of the queue
     * @throws InterruptedException if interrupted while waiting
     */
    public ChatMessage take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Gets the current queue size.
     * @return number of failed messages
     */
    public int size() {
        return queue.size();
    }

    /**
     * Checks if queue is empty.
     * @return true if no failed messages
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Gets all failed messages for analysis.
     * @return array of all failed messages
     */
    public ChatMessage[] getAllMessages() {
        return queue.toArray(new ChatMessage[0]);
    }

    /**
     * Clears all messages from the queue.
     */
    public void clear() {
        queue.clear();
    }
}
