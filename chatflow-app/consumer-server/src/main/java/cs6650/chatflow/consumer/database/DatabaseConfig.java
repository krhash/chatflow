package cs6650.chatflow.consumer.database;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database configuration loader
 * Loads configuration from database.properties with fallback to environment variables and system properties
 */
public class DatabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);
    private static final Properties properties = new Properties();

    static {
        loadProperties();
    }

    private static void loadProperties() {
        // Try to load from classpath
        try (InputStream input = DatabaseConfig.class.getClassLoader()
                .getResourceAsStream("database.properties")) {
            if (input != null) {
                properties.load(input);
                logger.info("Loaded database configuration from database.properties");
            } else {
                logger.info("No database.properties found, using defaults/environment");
            }
        } catch (IOException e) {
            logger.warn("Failed to load database.properties", e);
        }
    }

    // ========== AWS Configuration ==========

    public static String getAwsRegion() {
        return getProperty("aws.region", "us-east-1");
    }

    // ========== DynamoDB Configuration ==========

    public static String getDynamoDBTableName() {
        return getProperty("dynamodb.tableName", "chatflow-messages");
    }

    public static String getDynamoDBRoomIndex() {
        return getProperty("dynamodb.gsi.roomTimestamp", "room-timestamp-index");
    }

    public static String getDynamoDBUserIndex() {
        return getProperty("dynamodb.gsi.userTimestamp", "user-timestamp-index");
    }

    public static String getDynamoDBTypeIndex() {
        return getProperty("dynamodb.gsi.typeTimestamp", "type-timestamp-index");
    }

    // ========== Batch Configuration ==========

    /**
     * Number of messages per batch write
     * DynamoDB limit: 25 items per BatchWriteItem request
     */
    public static int getBatchSize() {
        return getIntProperty("batch.size", 25);
    }

    /**
     * Time interval (milliseconds) between automatic flushes
     * Lower value = more frequent writes, higher throughput
     */
    public static long getFlushIntervalMs() {
        return getLongProperty("batch.flushIntervalMs", 500);
    }

    // ========== Thread Pool Configuration ==========

    /**
     * Core number of database writer threads (always alive)
     * These threads handle batch write operations
     */
    public static int getCorePoolSize() {
        return getIntProperty("threadPool.coreSize", 4);
    }

    /**
     * Maximum number of database writer threads under load
     * Threads beyond core size are created when queue is full
     */
    public static int getMaxPoolSize() {
        return getIntProperty("threadPool.maxSize", 12);
    }

    /**
     * Thread pool keep-alive time in seconds
     * Excess threads (beyond core size) will be terminated after this idle time
     */
    public static long getKeepAliveTimeSeconds() {
        return getLongProperty("threadPool.keepAliveSeconds", 60);
    }

    /**
     * Size of the thread pool's task queue
     * This is separate from the message buffer queue
     * Holds BatchWriteTask objects waiting to be executed
     */
    public static int getTaskQueueSize() {
        return getIntProperty("threadPool.taskQueueSize", 100);
    }

    // ========== Message Queue Configuration ==========

    /**
     * Maximum size of the message buffer queue
     * When full, new messages are dropped and logged
     * Prevents memory overflow under extreme load
     */
    public static int getMaxQueueSize() {
        return getIntProperty("queue.maxSize", 10000);
    }

    /**
     * Alert throttle time (milliseconds) for queue full warnings
     * Prevents log spam when queue is consistently full
     */
    public static long getRejectionAlertThrottleMs() {
        return getLongProperty("queue.rejectionAlertThrottleMs", 5000);
    }

    // ========== Retry Configuration ==========

    /**
     * Maximum retry attempts for failed batch writes
     */
    public static int getMaxRetries() {
        return getIntProperty("retry.maxAttempts", 3);
    }

    /**
     * Base delay (milliseconds) for retry backoff
     * Actual delay = baseDelay * 2^retryAttempt (exponential backoff)
     */
    public static long getRetryBaseDelayMs() {
        return getLongProperty("retry.baseDelayMs", 100);
    }

    // ========== Helper Methods ==========

    /**
     * Get property with priority: System property > Environment variable > Properties file > Default
     */
    public static String getProperty(String key, String defaultValue) {
        // Priority 1: System property
        String value = System.getProperty(key);
        if (value != null) return value;

        // Priority 2: Environment variable (convert dots to underscores and uppercase)
        value = System.getenv(key.replace('.', '_').toUpperCase());
        if (value != null) return value;

        // Priority 3: Properties file
        // Priority 4: Default value
        return properties.getProperty(key, defaultValue);
    }

    private static int getIntProperty(String key, int defaultValue) {
        try {
            return Integer.parseInt(getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer for {}, using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    private static long getLongProperty(String key, long defaultValue) {
        try {
            return Long.parseLong(getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            logger.warn("Invalid long for {}, using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    // ========== Validation and Debug ==========

    /**
     * Log current configuration (useful for debugging)
     */
    public static void logConfiguration() {
        logger.info("=== Database Configuration ===");
        logger.info("AWS Region: {}", getAwsRegion());
        logger.info("DynamoDB Table: {}", getDynamoDBTableName());
        logger.info("Batch Size: {}", getBatchSize());
        logger.info("Flush Interval: {}ms", getFlushIntervalMs());
        logger.info("Thread Pool - Core: {}, Max: {}, Keep-Alive: {}s",
                getCorePoolSize(), getMaxPoolSize(), getKeepAliveTimeSeconds());
        logger.info("Queue - Max Size: {}, Rejection Alert Throttle: {}ms",
                getMaxQueueSize(), getRejectionAlertThrottleMs());
        logger.info("Retry - Max Attempts: {}, Base Delay: {}ms",
                getMaxRetries(), getRetryBaseDelayMs());
        logger.info("===============================");
    }

    /**
     * Validate configuration values
     * @throws IllegalStateException if configuration is invalid
     */
    public static void validateConfiguration() {
        // Validate batch size
        if (getBatchSize() < 1 || getBatchSize() > 25) {
            throw new IllegalStateException("batch.size must be between 1 and 25 (DynamoDB limit)");
        }

        // Validate thread pool
        if (getCorePoolSize() < 1) {
            throw new IllegalStateException("threadPool.coreSize must be at least 1");
        }
        if (getMaxPoolSize() < getCorePoolSize()) {
            throw new IllegalStateException("threadPool.maxSize must be >= threadPool.coreSize");
        }

        // Validate queue size
        if (getMaxQueueSize() < getBatchSize()) {
            throw new IllegalStateException("queue.maxSize must be >= batch.size");
        }

        // Validate timings
        if (getFlushIntervalMs() < 10) {
            throw new IllegalStateException("batch.flushIntervalMs must be at least 10ms");
        }

        logger.info("Configuration validation passed");
    }
}
