package cs6650.chatflow.client.workers;

import cs6650.chatflow.client.DistributedClient;
import cs6650.chatflow.client.DistributedClient.AckQueueEntry;
import cs6650.chatflow.client.commons.ClientMetrics;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.connection.ProducerConnectionPool;
import cs6650.chatflow.client.connection.ProducerWebSocketClient;
import cs6650.chatflow.client.model.ChatMessage;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker that sends ACK messages and tracks them for echo detection.
 */
public class AckSenderWorker implements Runnable, DistributedClient.Stoppable {

    private static final Logger logger = LoggerFactory.getLogger(AckSenderWorker.class);
    private static final int MAX_RETRIES = 3;

    private final BlockingQueue<AckQueueEntry> ackQueue;
    private final ProducerConnectionPool connectionPool;
    private final AtomicLong acksSent;
    private final AtomicLong acksFailed;
    private final ClientMetrics metrics;
    private final Gson gson;
    private volatile boolean running = true;
    private Thread workerThread;

    public AckSenderWorker(BlockingQueue<AckQueueEntry> ackQueue,
                           ProducerConnectionPool connectionPool,
                           AtomicLong acksSent,
                           AtomicLong acksFailed,
                           Set<String> sentMessageIds,
                           ClientMetrics metrics,
                           Gson gson) {
        this.ackQueue = ackQueue;
        this.connectionPool = connectionPool;
        this.acksSent = acksSent;
        this.acksFailed = acksFailed;
        this.metrics = metrics;
        this.gson = gson;
    }

    @Override
    public void run() {
        this.workerThread = Thread.currentThread();
        try {
            while (running) {
                try {
                    AckQueueEntry entry = ackQueue.poll(1, TimeUnit.SECONDS);
                    if (entry != null) {
                        sendAck(entry);
                    }
                } catch (InterruptedException e) {
                    if (running) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in ACK sender worker", e);
        }
    }

    private void sendAck(AckQueueEntry entry) {
        ChatMessage originalMessage = entry.getOriginalMessage();

        try {
            ChatMessage ackMessage = createAckMessage(originalMessage);
            String roomId = originalMessage.getRoomId();

            ProducerWebSocketClient connection = connectionPool.getConnection(roomId);

            if (connection != null && connection.isOpen()) {
                String jsonMessage = gson.toJson(ackMessage);
                connection.send(jsonMessage);

                acksSent.incrementAndGet();
                metrics.recordMessageAcked();

                logger.debug("ACK sent for message {}", originalMessage.getMessageId());

            } else {
                handleAckFailure(entry, "Connection unavailable for room " + roomId);
            }

        } catch (Exception e) {
            handleAckFailure(entry, e.getMessage());
        }
    }

    private ChatMessage createAckMessage(ChatMessage originalMessage) {
        ChatMessage ackMessage = new ChatMessage();
        ackMessage.setMessageId(java.util.UUID.randomUUID().toString());
        ackMessage.setUserId(originalMessage.getUserId());
        ackMessage.setUsername(originalMessage.getUsername());
        ackMessage.setMessage("DELIVERY_ACK:" + originalMessage.getMessageId());
        ackMessage.setRoomId(originalMessage.getRoomId());
        ackMessage.setMessageType(Constants.MESSAGE_TYPE_ACK);
        ackMessage.setTimestamp(java.time.Instant.now().toString());
        return ackMessage;
    }

    private void handleAckFailure(AckQueueEntry entry, String reason) {
        entry.incrementRetryCount();

        if (entry.getRetryCount() <= MAX_RETRIES) {
            logger.warn("ACK failed for message {} (attempt {}/{}): {}. Retrying...",
                    entry.getOriginalMessage().getMessageId(),
                    entry.getRetryCount(), MAX_RETRIES, reason);

            try {
                Thread.sleep(100L * entry.getRetryCount());
                ackQueue.offer(entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acksFailed.incrementAndGet();
            }

        } else {
            logger.error("ACK permanently failed for message {} after {} attempts",
                    entry.getOriginalMessage().getMessageId(), MAX_RETRIES);
            acksFailed.incrementAndGet();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}
