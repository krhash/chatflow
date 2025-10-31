package cs6650.chatflow.consumer.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Manages a pool of message consumers for all room queues.
 * Creates and manages consumer threads for each room (1-20).
 */
public class MessageConsumerManager {
    private static final Logger logger = LoggerFactory.getLogger(MessageConsumerManager.class);

    // Maps room ID to consumer
    private final Map<String, RoomMessageConsumer> consumers = new ConcurrentHashMap<>();
    private Connection connection;
    private boolean started = false;

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
     */
    public synchronized void start() {
        if (started) {
            logger.warn("MessageConsumerManager already started");
            return;
        }

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

            logger.info("Connecting to RabbitMQ at {}:{}", RabbitMQConfig.getHost(), RabbitMQConfig.getPort());
            connection = factory.newConnection();
            logger.info("Connected to RabbitMQ successfully");

            // Start consumer for each room (1-20)
            for (int roomId = 1; roomId <= 20; roomId++) {
                String roomIdStr = String.valueOf(roomId);
                try {
                    Channel channel = connection.createChannel();
                    RoomMessageConsumer consumer = new RoomMessageConsumer(roomIdStr, channel);
                    consumer.startConsuming();
                    consumers.put(roomIdStr, consumer);
                    logger.info("Started consumer for room {}", roomIdStr);
                } catch (Exception e) {
                    logger.error("Failed to start consumer for room {}", roomIdStr, e);
                    // Continue with other rooms
                }
            }

            started = true;
            logger.info("Started {} room consumers", consumers.size());

        } catch (IOException | TimeoutException e) {
            logger.error("Failed to initialize MessageConsumerManager", e);
            throw new RuntimeException("Failed to initialize MessageConsumerManager", e);
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
