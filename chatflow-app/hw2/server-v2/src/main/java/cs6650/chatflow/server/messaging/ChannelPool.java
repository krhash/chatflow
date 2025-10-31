package cs6650.chatflow.server.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe channel pool for RabbitMQ connections.
 * Manages a pool of pre-created channels to improve performance and resource utilization.
 */
public class ChannelPool {
    private static final Logger logger = LoggerFactory.getLogger(ChannelPool.class);

    private final BlockingQueue<Channel> pool;
    private final Connection connection;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final int poolSize;

    public ChannelPool() throws IOException, TimeoutException {
        this.poolSize = RabbitMQConfig.getChannelPoolSize();
        this.pool = new LinkedBlockingQueue<>(poolSize);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RabbitMQConfig.getHost());
        factory.setPort(RabbitMQConfig.getPort());
        factory.setUsername(RabbitMQConfig.getUsername());
        factory.setPassword(RabbitMQConfig.getPassword());
        factory.setVirtualHost(RabbitMQConfig.getVirtualHost());
        factory.setConnectionTimeout(RabbitMQConfig.getConnectionTimeout());

        logger.info("Connecting to RabbitMQ at {}:{}", RabbitMQConfig.getHost(), RabbitMQConfig.getPort());
        this.connection = factory.newConnection();

        // Pre-create channels and add to pool
        for (int i = 0; i < poolSize; i++) {
            Channel channel = connection.createChannel();
            pool.offer(channel);
            logger.debug("Created channel {} for pool", i);
        }

        // Declare exchange
        initializeExchange();

        logger.info("ChannelPool initialized with {} channels", poolSize);
    }

    private void initializeExchange() throws IOException {
        Channel tempChannel = null;
        try {
            tempChannel = connection.createChannel();
            String exchangeName = RabbitMQConfig.getExchangeName();
            String exchangeType = RabbitMQConfig.getExchangeType();

            // Declare topic exchange (idempotent operation)
            tempChannel.exchangeDeclare(exchangeName, exchangeType, true); // durable=true
            logger.info("Declared exchange: {} of type: {}", exchangeName, exchangeType);

            // Declare queues for each room (1-20)
            // Use routing keys in format "room.{id}" to match producer
            for (int roomId = 1; roomId <= 20; roomId++) {
                String queueName = "room." + roomId;
                String routingKey = "room." + roomId;  // routing key: "room.1", "room.2", etc.

                // Declare queue (durable=true)
                tempChannel.queueDeclare(queueName, true, false, false, null);
                logger.info("Declared queue: {}", queueName);

                // Bind queue to exchange with routing key
                tempChannel.queueBind(queueName, exchangeName, routingKey);
                logger.info("Bound queue {} to exchange {} with routing key '{}'", queueName, exchangeName, routingKey);
            }

            logger.info("Initialized exchange and queues for rooms 1-20");
        } finally {
            if (tempChannel != null) {
                try {
                    tempChannel.close();
                } catch (Exception e) {
                    logger.warn("Error closing temporary channel", e);
                }
            }
        }
    }

    /**
     * Borrows a channel from the pool.
     * @return Channel from pool or null if timeout
     */
    public Channel borrowChannel() {
        if (closed.get()) {
            logger.warn("ChannelPool is closed, cannot borrow channel");
            return null;
        }

        try {
            Channel channel = pool.poll(5, TimeUnit.SECONDS); // Wait up to 5 seconds
            if (channel != null && !channel.isOpen()) {
                logger.warn("Borrowed channel is closed, creating new one");
                channel = connection.createChannel();
            }
            return channel;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while borrowing channel", e);
            return null;
        } catch (Exception e) {
            logger.error("Error borrowing channel from pool", e);
            return null;
        }
    }

    /**
     * Returns a channel to the pool.
     * @param channel The channel to return
     */
    public void returnChannel(Channel channel) {
        if (closed.get() || channel == null) {
            return;
        }

        if (channel.isOpen()) {
            try {
                // After using publisher confirms, clear the confirm state by calling waitForConfirms with timeout 0
                // This resets the channel state so it can be reused safely
                channel.waitForConfirms(0);
                pool.offer(channel);
            } catch (Exception e) {
                logger.warn("Error returning channel to pool, closing it", e);
                try {
                    channel.close();
                } catch (Exception closeEx) {
                    logger.warn("Error closing channel", closeEx);
                }
            }
        } else {
            logger.debug("Channel is closed, not returning to pool");
        }
    }

    /**
     * Closes the channel pool and underlying connection.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.info("Closing ChannelPool");

            // Close all channels in pool
            Channel channel;
            while ((channel = pool.poll()) != null) {
                try {
                    if (channel.isOpen()) {
                        channel.close();
                    }
                } catch (Exception e) {
                    logger.warn("Error closing channel in pool", e);
                }
            }

            // Close connection
            try {
                connection.close();
            } catch (Exception e) {
                logger.warn("Error closing RabbitMQ connection", e);
            }

            logger.info("ChannelPool closed");
        }
    }

    /**
     * Gets the current pool size.
     */
    public int getPoolSize() {
        return pool.size();
    }
}
