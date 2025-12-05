package cs6650.chatflow.consumer;

import cs6650.chatflow.consumer.cache.ValkeyCacheService;
import cs6650.chatflow.consumer.database.CacheDBWriterService;
import cs6650.chatflow.consumer.database.DatabaseFactory;
import cs6650.chatflow.consumer.database.DatabaseService;
import cs6650.chatflow.consumer.database.batch.DeadLetterQueue;
import cs6650.chatflow.consumer.database.batch.DynamoDBBatchWriter;
import cs6650.chatflow.consumer.database.util.ThroughputTracker;
import cs6650.chatflow.consumer.messaging.MessageConsumerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Bootstrap listener with separated cache and database layers.
 *
 * Architecture:
 * RabbitMQ → Consumer → Valkey Cache (fast write)
 *                          ↓
 *                    [Background Service]
 *                          ↓
 *              CacheDBWriterService → DynamoDB
 */
@WebListener
public class Bootstrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

    private MessageConsumerManager consumerManager;
    private DatabaseService databaseService;
    private ValkeyCacheService cacheService;
    private CacheDBWriterService cacheDBWriterService;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        logger.info("========================================");
        logger.info("Initializing Consumer Server...");
        logger.info("========================================");

        try {
            // ========== Step 1: Initialize Valkey Cache Service ==========
            logger.info("Step 1: Initializing Valkey cache service...");
            cacheService = new ValkeyCacheService();
            cacheService.initialize();
            logger.info("✅ Valkey cache service initialized successfully");
            sce.getServletContext().setAttribute("cacheService", cacheService);
            // ==============================================================

            // ========== Step 2: Initialize Database Service ==========
            logger.info("Step 2: Initializing database service...");
            databaseService = DatabaseFactory.createFromEnvironment();
            databaseService.initialize();
            logger.info("✅ Database service initialized: {}", databaseService.getDatabaseType());
            sce.getServletContext().setAttribute("databaseService", databaseService);
            // ==========================================================

            // ========== Step 3: Initialize Background DB Writer ==========
            logger.info("Step 3: Initializing background database writer...");
            cacheDBWriterService = createCacheDBWriterService(cacheService);
            cacheDBWriterService.start();
            logger.info("✅ Background DB writer service started");
            sce.getServletContext().setAttribute("cacheDBWriterService", cacheDBWriterService);
            // ==============================================================

            // ========== Step 4: Start message consumers ==========
            logger.info("Step 4: Starting message consumers...");
            consumerManager = MessageConsumerManager.getInstance();
            consumerManager.start(cacheService);  // ✅ Only cache service needed
            logger.info("✅ Started {} room consumers", consumerManager.getConsumerCount());
            // =====================================================

            logger.info("========================================");
            logger.info("✅ Consumer Server Initialized Successfully");
            logger.info("✅ Architecture: RabbitMQ → Valkey Cache → [Background] → DynamoDB");
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("========================================");
            logger.error("❌ Failed to initialize consumer server", e);
            logger.error("========================================");
            throw new RuntimeException("Consumer server initialization failed", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        logger.info("========================================");
        logger.info("Shutting down Consumer Server...");
        logger.info("========================================");

        try {
            // ========== Step 1: Stop message consumers first ==========
            if (consumerManager != null && consumerManager.isStarted()) {
                logger.info("Step 1: Stopping message consumers...");
                consumerManager.stop();
                logger.info("✅ Message consumers stopped");
            }
            // ===========================================================

            // ========== Step 2: Stop background DB writer (flushes remaining) ==========
            if (cacheDBWriterService != null) {
                logger.info("Step 2: Stopping background DB writer...");
                cacheDBWriterService.stop();
                logger.info("✅ Background DB writer stopped");
            }
            // ============================================================================

            // ========== Step 3: Shutdown database service ==========
            if (databaseService != null) {
                logger.info("Step 3: Shutting down database service...");
                databaseService.shutdown();
                logger.info("✅ Database service shut down");
            }
            // ========================================================

            // ========== Step 4: Shutdown Valkey cache service ==========
            if (cacheService != null) {
                logger.info("Step 4: Shutting down Valkey cache service...");
                cacheService.shutdown();
                logger.info("✅ Valkey cache service shut down");
            }
            // ============================================================

            logger.info("========================================");
            logger.info("✅ Consumer Server Shut Down Successfully");
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("========================================");
            logger.error("❌ Error shutting down consumer server", e);
            logger.error("========================================");
        }
    }

    /**
     * Create CacheDBWriterService with proper configuration
     * This can be extracted to a factory later
     */
    private CacheDBWriterService createCacheDBWriterService(ValkeyCacheService cacheService) {
        // TODO: These should come from configuration
        int batchSize = 25;
        long pollIntervalMs = 1000; // Poll every 1 second
        int workerThreads = 4;

        // Get database components
        // Note: This is a bit hacky - ideally DatabaseService would expose these
        // For now, we create them directly
        DynamoDBBatchWriter batchWriter = getBatchWriter();
        DeadLetterQueue dlq = getDeadLetterQueue(batchWriter);
        ThroughputTracker tracker = new ThroughputTracker();

        return new CacheDBWriterService(
                cacheService,
                batchWriter,
                dlq,
                tracker,
                batchSize,
                pollIntervalMs,
                workerThreads
        );
    }

    /**
     * Get batch writer from database service
     * TODO: DatabaseService should expose this
     */
    private DynamoDBBatchWriter getBatchWriter() {
        // This is a workaround - we need to refactor DatabaseService
        // to expose its internal components
        // For now, create a new one
        return new DynamoDBBatchWriter(
                null, // Will be set properly when DynamoDBService is refactored
                "ChatMessages" // Table name
        );
    }

    /**
     * Get DLQ from database service
     * TODO: DatabaseService should expose this
     */
    private DeadLetterQueue getDeadLetterQueue(DynamoDBBatchWriter batchWriter) {
        return new DeadLetterQueue(
                batchWriter,
                1000,  // Max queue size
                5,     // Max retries
                2000   // Initial delay
        );
    }
}
