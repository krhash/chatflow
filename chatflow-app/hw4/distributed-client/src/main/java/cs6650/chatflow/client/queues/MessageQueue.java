package cs6650.chatflow.client.queues;

import cs6650.chatflow.client.model.MessageQueueEntry;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Thread-safe message queue for distributing messages to sender threads.
 * Uses a bounded blocking queue to prevent memory issues.
 */
public class MessageQueue {

    private static final int DEFAULT_CAPACITY = 100_000;
    private final BlockingQueue<MessageQueueEntry> queue;

    public MessageQueue() {
        this(DEFAULT_CAPACITY);
    }

    public MessageQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Put a message in the queue (blocks if full).
     */
    public void put(MessageQueueEntry entry) throws InterruptedException {
        queue.put(entry);
    }

    /**
     * Take a message from the queue (blocks if empty).
     */
    public MessageQueueEntry take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Get current queue size.
     */
    public int size() {
        return queue.size();
    }

    /**
     * Get remaining capacity.
     */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }
}
