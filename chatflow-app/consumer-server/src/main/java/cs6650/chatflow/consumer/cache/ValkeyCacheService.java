package cs6650.chatflow.consumer.cache;

import com.google.gson.Gson;
import cs6650.chatflow.consumer.model.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Enhanced Valkey cache service with atomic deduplication support.
 *
 * Key Features:
 * - SETNX (SET if Not eXists) for atomic deduplication
 * - Prevents duplicate broadcasts in horizontal scaling
 * - Batch operations for background DB writer
 */
public class ValkeyCacheService {
    private static final Logger logger = LoggerFactory.getLogger(ValkeyCacheService.class);
    private static final Gson gson = new Gson();

    // Connection pool
    private JedisPool jedisPool;
    private boolean initialized = false;

    // Metrics
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong successfulWrites = new AtomicLong(0);
    private final AtomicLong failedWrites = new AtomicLong(0);
    private final AtomicLong duplicateWrites = new AtomicLong(0); // NEW: Track duplicates prevented
    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong successfulReads = new AtomicLong(0);
    private final AtomicLong failedReads = new AtomicLong(0);
    private final AtomicLong totalScans = new AtomicLong(0);

    /**
     * Initialize the Valkey cache service
     */
    public void initialize() {
        if (initialized) {
            logger.warn("ValkeyCacheService already initialized");
            return;
        }

        try {
            logger.info("Initializing Valkey cache service with atomic deduplication...");

            // Validate configuration
            ValkeyConfig.validateConfiguration();
            ValkeyConfig.logConfiguration();

            // Configure connection pool
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(ValkeyConfig.getMaxTotal());
            poolConfig.setMaxIdle(ValkeyConfig.getMaxIdle());
            poolConfig.setMinIdle(ValkeyConfig.getMinIdle());
            poolConfig.setTestOnBorrow(ValkeyConfig.isTestOnBorrow());
            poolConfig.setMaxWait(java.time.Duration.ofMillis(ValkeyConfig.getMaxWaitMillis()));
            poolConfig.setTestWhileIdle(true);
            poolConfig.setMinEvictableIdleDuration(java.time.Duration.ofMillis(60000));
            poolConfig.setTimeBetweenEvictionRuns(java.time.Duration.ofMillis(30000));
            poolConfig.setNumTestsPerEvictionRun(3);
            poolConfig.setBlockWhenExhausted(true);

            // Create Jedis pool
            String password = ValkeyConfig.getPassword();

            if (ValkeyConfig.isSslEnabled()) {
                logger.info("Creating Jedis pool with SSL enabled");
                if (password != null && !password.isEmpty()) {
                    jedisPool = new JedisPool(
                            poolConfig,
                            ValkeyConfig.getHost(),
                            ValkeyConfig.getPort(),
                            ValkeyConfig.getConnectionTimeout(),
                            password,
                            true
                    );
                } else {
                    jedisPool = new JedisPool(
                            poolConfig,
                            ValkeyConfig.getHost(),
                            ValkeyConfig.getPort(),
                            true
                    );
                }
            } else {
                logger.info("Creating Jedis pool without SSL");
                if (password != null && !password.isEmpty()) {
                    jedisPool = new JedisPool(
                            poolConfig,
                            ValkeyConfig.getHost(),
                            ValkeyConfig.getPort(),
                            ValkeyConfig.getConnectionTimeout(),
                            password
                    );
                } else {
                    jedisPool = new JedisPool(
                            poolConfig,
                            ValkeyConfig.getHost(),
                            ValkeyConfig.getPort()
                    );
                }
            }

            // Test connection
            testConnection();

            initialized = true;
            logger.info("✅ Valkey cache service initialized with atomic deduplication");

        } catch (Exception e) {
            logger.error("Failed to initialize Valkey cache service", e);
            throw new RuntimeException("Valkey initialization failed", e);
        }
    }

    private void testConnection() {
        try (Jedis jedis = jedisPool.getResource()) {
            String response = jedis.ping();
            logger.info("Valkey connection test successful: {}", response);
        } catch (Exception e) {
            logger.error("Valkey connection test failed", e);
            throw new RuntimeException("Failed to connect to Valkey", e);
        }
    }

    /**
     * Atomically cache message using SETNX (SET if Not eXists).
     * Returns true only if this is the FIRST instance to cache this message.
     *
     * This is the KEY method for preventing duplicate broadcasts!
     *
     * @param event The chat event
     * @param roomId The room ID
     * @return true if message was cached (first instance), false if already exists
     */
    public boolean cacheMessageAtomic(ChatEvent event, String roomId) {
        if (!initialized) {
            logger.warn("ValkeyCacheService not initialized");
            return false;
        }

        if (event == null || event.getMessageId() == null) {
            logger.warn("Invalid event or messageId is null");
            return false;
        }

        totalWrites.incrementAndGet();
        String key = ValkeyConfig.getKeyPrefix() + event.getMessageId();

        try (Jedis jedis = jedisPool.getResource()) {
            String jsonValue = gson.toJson(event);

            // Use SET with NX (not exists) and EX (expiry) options
            // This is ATOMIC - only one instance will succeed
            SetParams params = new SetParams()
                    .nx()  // Only set if key doesn't exist
                    .ex(ValkeyConfig.getDefaultTTLSeconds());  // Set TTL

            String result = jedis.set(key, jsonValue, params);

            if ("OK".equals(result)) {
                // Success - we are the FIRST instance to cache this message
                successfulWrites.incrementAndGet();
                logger.debug("✅ Atomically cached message {} (first instance)",
                        event.getMessageId());
                return true;
            } else {
                // Failed - another instance already cached this message
                duplicateWrites.incrementAndGet();
                logger.debug("🔄 Message {} already cached by another instance (duplicate prevented)",
                        event.getMessageId());
                return false;
            }

        } catch (JedisException e) {
            logger.error("Failed to atomically cache message {}: {}",
                    event.getMessageId(), e.getMessage());
            failedWrites.incrementAndGet();
            return false;

        } catch (Exception e) {
            logger.error("Unexpected error caching message {}: {}",
                    event.getMessageId(), e.getMessage(), e);
            failedWrites.incrementAndGet();
            return false;
        }
    }

    /**
     * Legacy method for backward compatibility.
     * Prefer cacheMessageAtomic() for deduplication.
     */
    public boolean cacheMessage(ChatEvent event, String roomId) {
        return cacheMessageAtomic(event, roomId);
    }

    /**
     * Retrieve a message from cache
     */
    public ChatEvent getMessage(String messageId) {
        if (!initialized) {
            logger.warn("ValkeyCacheService not initialized");
            return null;
        }

        if (messageId == null) {
            return null;
        }

        totalReads.incrementAndGet();
        String key = ValkeyConfig.getKeyPrefix() + messageId;

        try (Jedis jedis = jedisPool.getResource()) {
            String jsonValue = jedis.get(key);

            if (jsonValue != null) {
                ChatEvent event = gson.fromJson(jsonValue, ChatEvent.class);
                successfulReads.incrementAndGet();
                logger.debug("Cache hit for message {}", messageId);
                return event;
            } else {
                logger.debug("Cache miss for message {}", messageId);
                return null;
            }

        } catch (Exception e) {
            failedReads.incrementAndGet();
            logger.error("Failed to retrieve message {} from cache: {}", messageId, e.getMessage());
            return null;
        }
    }

    /**
     * Scan cache for messages matching pattern
     */
    public List<String> scanKeys(String pattern, int limit) {
        if (!initialized) {
            return new ArrayList<>();
        }

        totalScans.incrementAndGet();
        List<String> keys = new ArrayList<>();

        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams scanParams = new ScanParams().match(pattern).count(limit);

            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                List<String> batch = scanResult.getResult();
                keys.addAll(batch);

                cursor = scanResult.getCursor();

                if (keys.size() >= limit) {
                    break;
                }

            } while (!"0".equals(cursor) && keys.size() < limit);

            logger.debug("Scanned cache: found {} keys matching {}", keys.size(), pattern);
            return keys.subList(0, Math.min(keys.size(), limit));

        } catch (Exception e) {
            logger.error("Failed to scan cache keys: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get multiple messages at once using a pipeline (cluster-safe).
     */
    public List<ChatEvent> getMessages(List<String> messageIds) {
        if (!initialized || messageIds == null || messageIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChatEvent> events = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            List<Response<String>> responses = new ArrayList<>();

            for (String id : messageIds) {
                responses.add(pipeline.get(ValkeyConfig.getKeyPrefix() + id));
            }
            pipeline.sync();

            for (Response<String> response : responses) {
                String jsonValue = response.get();
                if (jsonValue != null) {
                    try {
                        ChatEvent event = gson.fromJson(jsonValue, ChatEvent.class);
                        events.add(event);
                        successfulReads.incrementAndGet();
                    } catch (Exception e) {
                        logger.warn("Failed to parse cached message: {}", e.getMessage());
                        events.add(null);
                    }
                } else {
                    events.add(null);
                }
            }
            totalReads.addAndGet(messageIds.size());
        } catch (Exception e) {
            logger.error("Failed to get batch messages: {}", e.getMessage());
            failedReads.addAndGet(messageIds.size());
        }
        return events;
    }

    /**
     * Delete a message from cache
     */
    public boolean deleteMessage(String messageId) {
        if (!initialized || messageId == null) {
            return false;
        }

        String key = ValkeyConfig.getKeyPrefix() + messageId;

        try (Jedis jedis = jedisPool.getResource()) {
            Long deleted = jedis.del(key);
            logger.debug("Deleted message {} from cache (count: {})", messageId, deleted);
            return deleted > 0;

        } catch (Exception e) {
            logger.error("Failed to delete message {} from cache: {}", messageId, e.getMessage());
            return false;
        }
    }

    /**
     * Delete multiple messages at once using a pipeline (cluster-safe).
     */
    public long deleteMessages(List<String> messageIds) {
        if (!initialized || messageIds == null || messageIds.isEmpty()) {
            return 0;
        }

        long deletedCount = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            List<Response<Long>> responses = new ArrayList<>();

            for (String id : messageIds) {
                responses.add(pipeline.del(ValkeyConfig.getKeyPrefix() + id));
            }
            pipeline.sync();

            for (Response<Long> response : responses) {
                deletedCount += response.get();
            }
            logger.debug("Batch deleted {} messages from cache", deletedCount);
        } catch (Exception e) {
            logger.error("Failed to batch delete messages: {}", e.getMessage());
        }
        return deletedCount;
    }

    /**
     * Get Jedis pool for advanced operations
     */
    public JedisPool getJedisPool() {
        return jedisPool;
    }

    /**
     * Check if cache service is healthy
     */
    public boolean isHealthy() {
        if (!initialized || jedisPool == null || jedisPool.isClosed()) {
            return false;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String response = jedis.ping();
            return "PONG".equalsIgnoreCase(response);
        } catch (Exception e) {
            logger.error("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get cache statistics
     */
    public String getStats() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.info("stats");
        } catch (Exception e) {
            logger.error("Failed to get cache stats: {}", e.getMessage());
            return "Error retrieving stats";
        }
    }

    /**
     * Get service metrics with deduplication stats
     */
    public String getMetrics() {
        return String.format(
                "ValkeyCacheService Metrics - " +
                        "Writes: %d (successful: %d, duplicates: %d, failed: %d) [%.2f%% success] | " +
                        "Reads: %d (successful: %d, failed: %d) [%.2f%% success] | " +
                        "Scans: %d | " +
                        "Duplicate Prevention Rate: %.2f%%",
                totalWrites.get(),
                successfulWrites.get(),
                duplicateWrites.get(),
                failedWrites.get(),
                totalWrites.get() > 0 ? (successfulWrites.get() * 100.0 / totalWrites.get()) : 0.0,
                totalReads.get(),
                successfulReads.get(),
                failedReads.get(),
                totalReads.get() > 0 ? (successfulReads.get() * 100.0 / totalReads.get()) : 0.0,
                totalScans.get(),
                totalWrites.get() > 0 ? (duplicateWrites.get() * 100.0 / totalWrites.get()) : 0.0
        );
    }

    /**
     * Shutdown the cache service
     */
    public void shutdown() {
        if (!initialized) {
            return;
        }

        logger.info("Shutting down Valkey cache service...");
        logger.info("Final metrics: {}", getMetrics());

        try {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
                logger.info("Jedis pool closed successfully");
            }
        } catch (Exception e) {
            logger.error("Error closing Jedis pool", e);
        }

        initialized = false;
        logger.info("Valkey cache service shut down");
    }

    // ========== Getters for Metrics ==========

    public long getTotalWrites() {
        return totalWrites.get();
    }

    public long getSuccessfulWrites() {
        return successfulWrites.get();
    }

    public long getFailedWrites() {
        return failedWrites.get();
    }

    public long getDuplicateWrites() {
        return duplicateWrites.get();
    }

    public long getTotalReads() {
        return totalReads.get();
    }

    public long getSuccessfulReads() {
        return successfulReads.get();
    }

    public long getFailedReads() {
        return failedReads.get();
    }

    public long getTotalScans() {
        return totalScans.get();
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get duplicate prevention rate (percentage of writes that were duplicates)
     */
    public double getDuplicatePreventionRate() {
        long total = totalWrites.get();
        return total > 0 ? (duplicateWrites.get() * 100.0 / total) : 0.0;
    }
}
