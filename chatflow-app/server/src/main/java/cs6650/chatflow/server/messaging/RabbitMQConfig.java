package cs6650.chatflow.server.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration class for RabbitMQ connection settings.
 * Uses hardcoded configuration for Assignment 2.
 */
public class RabbitMQConfig {
    private static final Logger logger = LoggerFactory.getLogger(RabbitMQConfig.class);

    // Hardcoded RabbitMQ configuration for Assignment 2
    private static final String HOST = "localhost";  // Update this to your RabbitMQ server IP
    private static final int PORT = 5672;
    private static final String USERNAME = "guest";
    private static final String PASSWORD = "guest";
    private static final String VIRTUAL_HOST = "/";
    private static final String EXCHANGE_NAME = "chat.exchange";
    private static final String EXCHANGE_TYPE = "topic";
    private static final int CONNECTION_TIMEOUT = 60000;
    private static final int CHANNEL_POOL_SIZE = 10;

    public static String getHost() {
        return HOST;
    }

    public static int getPort() {
        return PORT;
    }

    public static String getUsername() {
        return USERNAME;
    }

    public static String getPassword() {
        return PASSWORD;
    }

    public static String getVirtualHost() {
        return VIRTUAL_HOST;
    }

    public static String getExchangeName() {
        return EXCHANGE_NAME;
    }

    public static String getExchangeType() {
        return EXCHANGE_TYPE;
    }

    public static int getConnectionTimeout() {
        return CONNECTION_TIMEOUT;
    }

    public static int getChannelPoolSize() {
        return CHANNEL_POOL_SIZE;
    }
}
