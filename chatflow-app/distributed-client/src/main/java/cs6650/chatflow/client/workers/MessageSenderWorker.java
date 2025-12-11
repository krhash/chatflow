package cs6650.chatflow.client.workers;

import cs6650.chatflow.client.DistributedClient;
import cs6650.chatflow.client.commons.ClientMetrics;
import cs6650.chatflow.client.connection.ProducerConnectionPool;
import cs6650.chatflow.client.connection.ProducerWebSocketClient;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.model.MessageQueueEntry;
import cs6650.chatflow.client.queues.MessageQueue;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker thread that sends messages to the producer server.
 */
public class MessageSenderWorker implements Runnable, DistributedClient.Stoppable {

    private static final Logger logger = LoggerFactory.getLogger(MessageSenderWorker.class);

    private final MessageQueue messageQueue;
    private final ProducerConnectionPool connectionPool;
    private final AtomicLong messagesSent;
    private final Set<String> sentMessageIds;
    private final ClientMetrics metrics;
    private final Gson gson;
    private volatile boolean running = true;
    private Thread workerThread;

    public MessageSenderWorker(MessageQueue messageQueue,
                               ProducerConnectionPool connectionPool,
                               AtomicLong messagesSent,
                               Set<String> sentMessageIds,
                               ClientMetrics metrics,
                               Gson gson) {
        this.messageQueue = messageQueue;
        this.connectionPool = connectionPool;
        this.messagesSent = messagesSent;
        this.sentMessageIds = sentMessageIds;
        this.metrics = metrics;
        this.gson = gson;
    }

    @Override
    public void run() {
        this.workerThread = Thread.currentThread();
        try {
            while (running) {
                MessageQueueEntry entry = messageQueue.take();
                ChatMessage message = entry.getMessage();
                String roomId = entry.getRoomId();

                sentMessageIds.add(message.getMessageId());
                sendMessage(message, roomId);
            }
        } catch (InterruptedException e) {
            if (running) {
                logger.debug("Sender worker interrupted, shutting down");
            }
        }
    }

    private void sendMessage(ChatMessage message, String roomId) {
        ProducerWebSocketClient connection = connectionPool.getConnection(roomId);

        if (connection != null && connection.isOpen()) {
            try {
                String jsonMessage = gson.toJson(message);
                connection.send(jsonMessage);
                messagesSent.incrementAndGet();
                metrics.recordMessageSent();

            } catch (Exception e) {
                metrics.recordConnectionFailure();
                logger.error("Failed to send message to room {}: {}", roomId, e.getMessage());
            }
        } else {
            metrics.recordConnectionFailure();
            logger.warn("No open connection available for room {}", roomId);
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
