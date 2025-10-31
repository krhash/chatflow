package cs6650.chatflow.consumer.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration class for RabbitMQ connection settings.
 * Uses properties file for configuration.
 */
public class RabbitMQConfig {
    private static final Logger logger = LoggerFactory.getLogger(RabbitMQConfig.class);

    private static final String CONFIG_FILE = "rabbitmq.properties";
    private static final Properties properties = new Properties();

    static {
        loadProperties();
    }

    private static void loadProperties() {
        try (InputStream input = RabbitMQConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input != null) {
                properties.load(input);
                logger.info("RabbitMQ configuration loaded from {}", CONFIG_FILE);
            } else {
                logger.warn("RabbitMQ configuration not found, using default values");
                // Set some defaults
                properties.setProperty("rabbitmq.host", "localhost");
                properties.setProperty("rabbitmq.port", "5672");
                properties.setProperty("rabbitmq.username", "guest");
                properties.setProperty("rabbitmq.password", "guest");
                properties.setProperty("rabbitmq.virtualhost", "/");
                properties.setProperty("rabbitmq.exchange", "chat.exchange");
                properties.setProperty("rabbitmq.exchange.type", "topic");
                properties.setProperty("rabbitmq.connection.timeout", "60000");
                properties.setProperty("rabbitmq.consumer.pool.size", "20");
            }
        } catch (IOException e) {
            logger.error("Failed to load RabbitMQ configuration", e);
        }
    }

    public static String getHost() {
        return properties.getProperty("rabbitmq.host", "localhost");
    }

    public static int getPort() {
        return Integer.parseInt(properties.getProperty("rabbitmq.port", "5672"));
    }

    public static String getUsername() {
        return properties.getProperty("rabbitmq.username", "guest");
    }

    public static String getPassword() {
        return properties.getProperty("rabbitmq.password", "guest");
    }

    public static String getVirtualHost() {
        return properties.getProperty("rabbitmq.virtualhost", "/");
    }

    public static String getExchangeName() {
        return properties.getProperty("rabbitmq.exchange", "chat.exchange");
    }

    public static String getExchangeType() {
        return properties.getProperty("rabbitmq.exchange.type", "topic");
    }

    public static int getConnectionTimeout() {
        return Integer.parseInt(properties.getProperty("rabbitmq.connection.timeout", "60000"));
    }

    public static int getConsumerPoolSize() {
        return Integer.parseInt(properties.getProperty("rabbitmq.consumer.pool.size", "20"));
    }

    public static boolean isAutoAck() {
        return Boolean.parseBoolean(properties.getProperty("rabbitmq.consumer.auto.ack", "false"));
    }
}
