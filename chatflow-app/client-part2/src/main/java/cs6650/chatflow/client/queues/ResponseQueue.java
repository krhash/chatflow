package cs6650.chatflow.client.queues;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.MessageResponse;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe queue for WebSocket response processing.
 * Decouples response reception from response processing for better performance.
 */
public class ResponseQueue {

    private final BlockingQueue<MessageResponse> queue;

    /**
     * Creates a response queue with configured capacity.
     */
    public ResponseQueue() {
        this.queue = new ArrayBlockingQueue<>(Constants.RESPONSE_QUEUE_CAPACITY);
    }

    /**
     * Adds a response to the queue, blocking if queue is full.
     * @param response the response to add
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public void put(MessageResponse response) throws InterruptedException {
        queue.put(response);
    }

    /**
     * Retrieves a response from the queue, blocking if queue is empty.
     * @return the next response
     * @throws InterruptedException if thread is interrupted while waiting
     */
    public MessageResponse take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Attempts to add a response without blocking.
     * @param response the response to add
     * @return true if added successfully, false if queue is full
     */
    public boolean offer(MessageResponse response) {
        return queue.offer(response);
    }

    /**
     * Attempts to retrieve a response with timeout.
     * @param timeoutMillis maximum time to wait
     * @return the response, or null if timeout
     * @throws InterruptedException if thread is interrupted
     */
    public MessageResponse poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Gets the current queue size.
     * @return number of responses in queue
     */
    public int size() {
        return queue.size();
    }

    /**
     * Gets the remaining capacity.
     * @return number of additional responses that can be added
     */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    /**
     * Checks if queue is empty.
     * @return true if no responses in queue
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Clears all responses from the queue.
     */
    public void clear() {
        queue.clear();
    }
}
