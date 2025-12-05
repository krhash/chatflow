package cs6650.chatflow.server.messaging;

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
        // First try to load from external file specified by system property
        String configPath = System.getProperty("rabbitmq.config.path");
        if (configPath != null && !configPath.trim().isEmpty()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(configPath)) {
                properties.load(fis);
                logger.info("RabbitMQ configuration loaded from external file: {}", configPath);
                return;
            } catch (IOException e) {
                logger.warn("Failed to load RabbitMQ configuration from external file {}, falling back to classpath", configPath, e);
            }
        }

        // Fall back to classpath resource
        try (InputStream input = RabbitMQConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input != null) {
                properties.load(input);
                logger.info("RabbitMQ configuration loaded from {}", CONFIG_FILE);
            } else {
                logger.warn("RabbitMQ configuration not found, using default values");
                // Set some defaults
                properties.setProperty("rabbitmq.host", "54.173.148.11");
                properties.setProperty("rabbitmq.port", "5672");
                properties.setProperty("rabbitmq.username", "guest");
                properties.setProperty("rabbitmq.password", "guest");
                properties.setProperty("rabbitmq.virtualhost", "/");
                properties.setProperty("rabbitmq.exchange", "chat.exchange");
                properties.setProperty("rabbitmq.exchange.type", "topic");
                properties.setProperty("rabbitmq.connection.timeout", "60000");
                properties.setProperty("rabbitmq.channel.pool.size", "10");
            }
        } catch (IOException e) {
            logger.error("Failed to load RabbitMQ configuration", e);
        }
    }

    public static String getHost() {
        return properties.getProperty("rabbitmq.host", "54.173.148.11");
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

    public static int getChannelPoolSize() {
        return Integer.parseInt(properties.getProperty("rabbitmq.channel.pool.size", "10"));
    }
}
