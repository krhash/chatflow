package cs6650.chatflow.consumer.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import cs6650.chatflow.consumer.cache.ValkeyCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

/**
 * Manages a pool of message consumers for all room queues.
 * Creates and manages consumer threads for each room (1-20).
 *
 * Updated for separated concerns:
 * - Consumers write ONLY to cache
 * - Database writes handled by separate CacheDBWriterService
 */
public class MessageConsumerManager {
    private static final Logger logger = LoggerFactory.getLogger(MessageConsumerManager.class);

    // Maps room ID to consumer
    private final Map<String, RoomMessageConsumer> consumers = new ConcurrentHashMap<>();
    private Connection connection;
    private Channel channel;
    private boolean started = false;
    private ValkeyCacheService cacheService;
    private ExecutorService messageProcessingExecutor;

    private static class SingletonHolder {
        private static final MessageConsumerManager INSTANCE = new MessageConsumerManager();
    }

    public static MessageConsumerManager getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private MessageConsumerManager() {
        // Private constructor for singleton
    }

    /**
     * Initializes the connection and starts all room consumers.
     * Now only requires ValkeyCacheService (database handled separately)
     *
     * @param cacheService Valkey cache service for fast writes
     */
    public synchronized void start(ValkeyCacheService cacheService, ExecutorService messageProcessingExecutor) {
        if (started) {
            logger.warn("MessageConsumerManager already started");
            return;
        }

        this.cacheService = cacheService;
        this.messageProcessingExecutor = messageProcessingExecutor;

        try {
            logger.info("Initializing MessageConsumerManager");

            // Create RabbitMQ connection
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(RabbitMQConfig.getHost());
            factory.setPort(RabbitMQConfig.getPort());
            factory.setUsername(RabbitMQConfig.getUsername());
            factory.setPassword(RabbitMQConfig.getPassword());
            factory.setVirtualHost(RabbitMQConfig.getVirtualHost());
            factory.setConnectionTimeout(RabbitMQConfig.getConnectionTimeout());

            logger.info("Connecting to RabbitMQ at {}:{}",
                    RabbitMQConfig.getHost(), RabbitMQConfig.getPort());
            connection = factory.newConnection();
            channel = connection.createChannel();
            logger.info("Connected to RabbitMQ successfully and created a channel");

            started = true;
            logger.info("✅ MessageConsumerManager initialized and ready for lazy subscriptions");

        } catch (IOException | TimeoutException e) {
            logger.error("Failed to initialize MessageConsumerManager", e);
            throw new RuntimeException("Failed to initialize MessageConsumerManager", e);
        }
    }

    public synchronized void startConsumerForRoom(String roomId) {
        if (!started) {
            logger.warn("MessageConsumerManager not started, cannot start consumer for room {}", roomId);
            return;
        }
        if (consumers.containsKey(roomId)) {
            logger.debug("Consumer for room {} is already running.", roomId);
            return;
        }

        try {
            RoomMessageConsumer consumer = new RoomMessageConsumer(
                    roomId,
                    channel,
                    cacheService,
                    messageProcessingExecutor
            );
            consumer.startConsuming();
            consumers.put(roomId, consumer);
            logger.info("✅ Lazily started consumer for room {}", roomId);
        } catch (Exception e) {
            logger.error("Failed to start consumer for room {}", roomId, e);
        }
    }

    public synchronized void stopConsumerForRoom(String roomId) {
        if (!started) {
            return;
        }
        RoomMessageConsumer consumer = consumers.remove(roomId);
        if (consumer != null) {
            consumer.stopConsuming();
            logger.info("✅ Lazily stopped consumer for room {}", roomId);
        }
    }


    /**
     * Stops all consumers and closes the connection.
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }

        logger.info("Stopping MessageConsumerManager");

        // Stop all consumers
        consumers.values().forEach(RoomMessageConsumer::stopConsuming);
        consumers.clear();

        // Close connection
        if (connection != null && connection.isOpen()) {
            try {
                connection.close();
                logger.info("Closed RabbitMQ connection");
            } catch (IOException e) {
                logger.error("Failed to close RabbitMQ connection", e);
            }
        }

        started = false;
        logger.info("MessageConsumerManager stopped");
    }

    /**
     * Gets the consumer for a specific room.
     */
    public RoomMessageConsumer getConsumer(String roomId) {
        return consumers.get(roomId);
    }

    /**
     * Gets the number of active consumers.
     */
    public int getConsumerCount() {
        return consumers.size();
    }

    /**
     * Checks if the manager is started.
     */
    public boolean isStarted() {
        return started;
    }
}
