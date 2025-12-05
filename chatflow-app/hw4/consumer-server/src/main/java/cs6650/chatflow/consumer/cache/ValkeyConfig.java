package cs6650.chatflow.consumer.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration class for Valkey (Redis-compatible) cache settings.
 * Loads configuration from valkey.properties with fallback to environment variables.
 */
public class ValkeyConfig {
    private static final Logger logger = LoggerFactory.getLogger(ValkeyConfig.class);
    private static final Properties properties = new Properties();

    static {
        loadProperties();
    }

    private static void loadProperties() {
        // Try to load from classpath
        try (InputStream input = ValkeyConfig.class.getClassLoader()
                .getResourceAsStream("valkey.properties")) {
            if (input != null) {
                properties.load(input);
                logger.info("Loaded Valkey configuration from valkey.properties");
            } else {
                logger.info("No valkey.properties found, using defaults/environment");
            }
        } catch (IOException e) {
            logger.warn("Failed to load valkey.properties", e);
        }
    }

    // ========== Connection Configuration ==========

    /**
     * Valkey/Redis server host
     */
    public static String getHost() {
        return getProperty("valkey.host", "redis-y79o1w.serverless.usw2.cache.amazonaws.com");
    }

    /**
     * Valkey/Redis server port
     */
    public static int getPort() {
        return getIntProperty("valkey.port", 6379);
    }

    /**
     * Connection timeout in milliseconds
     */
    public static int getConnectionTimeout() {
        return getIntProperty("valkey.connectionTimeout", 5000);
    }

    /**
     * Socket timeout in milliseconds
     */
    public static int getSocketTimeout() {
        return getIntProperty("valkey.socketTimeout", 5000);
    }

    /**
     * Password for authentication (optional)
     */
    public static String getPassword() {
        return getProperty("valkey.password", null);
    }

    /**
     * Enable SSL/TLS connection
     */
    public static boolean isSslEnabled() {
        return getBooleanProperty("valkey.ssl.enabled", true);
    }

    // ========== Connection Pool Configuration ==========

    /**
     * Maximum total connections in pool
     */
    public static int getMaxTotal() {
        return getIntProperty("valkey.pool.maxTotal", 20);
    }

    /**
     * Maximum idle connections in pool
     */
    public static int getMaxIdle() {
        return getIntProperty("valkey.pool.maxIdle", 10);
    }

    /**
     * Minimum idle connections in pool
     */
    public static int getMinIdle() {
        return getIntProperty("valkey.pool.minIdle", 2);
    }

    /**
     * Test connection on borrow
     */
    public static boolean isTestOnBorrow() {
        return getBooleanProperty("valkey.pool.testOnBorrow", true);
    }

    /**
     * Max wait time for connection (milliseconds)
     */
    public static long getMaxWaitMillis() {
        return getLongProperty("valkey.pool.maxWaitMillis", 3000);
    }

    // ========== Cache Configuration ==========

    /**
     * Default TTL for cached messages in seconds (1 hour = 3600 seconds)
     */
    public static int getDefaultTTLSeconds() {
        return getIntProperty("valkey.cache.ttl.seconds", 3600);
    }

    /**
     * Enable cache compression
     */
    public static boolean isCompressionEnabled() {
        return getBooleanProperty("valkey.cache.compression.enabled", false);
    }

    /**
     * Key prefix for all cache entries
     */
    public static String getKeyPrefix() {
        return getProperty("valkey.cache.keyPrefix", "chatflow:msg:");
    }

    /**
     * Maximum retries for failed operations
     */
    public static int getMaxRetries() {
        return getIntProperty("valkey.maxRetries", 3);
    }

    /**
     * Retry delay in milliseconds
     */
    public static long getRetryDelayMs() {
        return getLongProperty("valkey.retryDelayMs", 100);
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

    private static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    // ========== Validation and Debug ==========

    /**
     * Log current configuration (useful for debugging)
     */
    public static void logConfiguration() {
        logger.info("=== Valkey Cache Configuration ===");
        logger.info("Host: {}", getHost());
        logger.info("Port: {}", getPort());
        logger.info("SSL Enabled: {}", isSslEnabled());
        logger.info("Connection Timeout: {}ms", getConnectionTimeout());
        logger.info("Socket Timeout: {}ms", getSocketTimeout());
        logger.info("Pool - Max Total: {}, Max Idle: {}, Min Idle: {}",
                getMaxTotal(), getMaxIdle(), getMinIdle());
        logger.info("Cache TTL: {}s ({}h)", getDefaultTTLSeconds(), getDefaultTTLSeconds() / 3600.0);
        logger.info("Key Prefix: {}", getKeyPrefix());
        logger.info("Compression Enabled: {}", isCompressionEnabled());
        logger.info("Max Retries: {}, Retry Delay: {}ms", getMaxRetries(), getRetryDelayMs());
        logger.info("===================================");
    }

    /**
     * Validate configuration values
     * @throws IllegalStateException if configuration is invalid
     */
    public static void validateConfiguration() {
        if (getHost() == null || getHost().trim().isEmpty()) {
            throw new IllegalStateException("valkey.host must be configured");
        }

        if (getPort() < 1 || getPort() > 65535) {
            throw new IllegalStateException("valkey.port must be between 1 and 65535");
        }

        if (getMaxTotal() < 1) {
            throw new IllegalStateException("valkey.pool.maxTotal must be at least 1");
        }

        if (getMaxIdle() > getMaxTotal()) {
            throw new IllegalStateException("valkey.pool.maxIdle cannot exceed maxTotal");
        }

        if (getMinIdle() > getMaxIdle()) {
            throw new IllegalStateException("valkey.pool.minIdle cannot exceed maxIdle");
        }

        if (getDefaultTTLSeconds() < 1) {
            throw new IllegalStateException("valkey.cache.ttl.seconds must be at least 1");
        }

        logger.info("Valkey configuration validation passed");
    }
}
