package cs6650.chatflow.client.queues;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe queue for message passing between producer and consumer threads.
 * Uses bounded capacity to prevent memory overflow.
 */
public class MessageQueue {

    private final BlockingQueue<ChatMessage> queue;

    /**
     * Creates a message queue with configured capacity.
     */
    public MessageQueue() {
        this.queue = new ArrayBlockingQueue<>(Constants.MESSAGE_QUEUE_CAPACITY);
    }

    /**
     * Adds a message to the queue, blocking if queue is full.
     * @param message the message to add
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public void put(ChatMessage message) throws InterruptedException {
        queue.put(message);
    }

    /**
     * Retrieves a message from the queue, blocking if queue is empty.
     * @return the next message
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public ChatMessage take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Attempts to add a message without blocking.
     * @param message the message to add
     * @return true if added successfully, false if queue is full
     */
    public boolean offer(ChatMessage message) {
        return queue.offer(message);
    }

    /**
     * Attempts to retrieve a message with timeout.
     * @param timeoutMillis maximum time to wait
     * @return the message, or null if timeout
     * @throws InterruptedException if thread is interrupted
     */
    public ChatMessage poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Gets the current queue size.
     * @return number of messages in queue
     */
    public int size() {
        return queue.size();
    }

    /**
     * Gets the remaining capacity.
     * @return number of additional messages that can be added
     */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    /**
     * Checks if queue is empty.
     * @return true if no messages in queue
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Clears all messages from the queue.
     */
    public void clear() {
        queue.clear();
    }
}
