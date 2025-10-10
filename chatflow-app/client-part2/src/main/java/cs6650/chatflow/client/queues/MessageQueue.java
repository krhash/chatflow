package cs6650.chatflow.client.queues;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.model.MessageQueueEntry;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe queue for message passing between producer and consumer threads.
 * Uses bounded capacity to prevent memory overflow.
 * Now holds MessageQueueEntry objects that include both message and target room ID.
 */
public class MessageQueue {

    private final BlockingQueue<MessageQueueEntry> queue;

    /**
     * Creates a message queue with configured capacity.
     */
    public MessageQueue() {
        this.queue = new ArrayBlockingQueue<>(Constants.MESSAGE_QUEUE_CAPACITY);
    }

    /**
     * Adds a message entry to the queue, blocking if queue is full.
     * @param entry the message entry to add
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public void put(MessageQueueEntry entry) throws InterruptedException {
        queue.put(entry);
    }

    /**
     * Retrieves a message entry from the queue, blocking if queue is empty.
     * @return the next message entry
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public MessageQueueEntry take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Attempts to add a message entry without blocking.
     * @param entry the message entry to add
     * @return true if added successfully, false if queue is full
     */
    public boolean offer(MessageQueueEntry entry) {
        return queue.offer(entry);
    }

    /**
     * Attempts to retrieve a message entry with timeout.
     * @param timeoutMillis maximum time to wait
     * @return the message entry, or null if timeout
     * @throws InterruptedException if thread is interrupted
     */
    public MessageQueueEntry poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Legacy method for backward compatibility - creates entry with random room ID.
     * @param message the message to add
     * @return true if added successfully, false if queue is full
     * @deprecated Use put(MessageQueueEntry) instead
     */
    @Deprecated
    public boolean offer(ChatMessage message) {
        // Generate random room ID for backward compatibility
        int roomId = (int) (Math.random() * Constants.ROOM_COUNT) + 1;
        MessageQueueEntry entry = new MessageQueueEntry(message, roomId);
        return queue.offer(entry);
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
