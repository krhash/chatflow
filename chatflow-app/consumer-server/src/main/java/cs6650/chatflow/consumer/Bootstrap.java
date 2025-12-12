package cs6650.chatflow.consumer;

import cs6650.chatflow.consumer.api.MetricsService;
import cs6650.chatflow.consumer.cache.ValkeyCacheService;
import cs6650.chatflow.consumer.database.CacheDBWriterService;
import cs6650.chatflow.consumer.database.DatabaseConfig;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private ExecutorService messageProcessingExecutor;
    private MetricsService metricsService;

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
            cacheDBWriterService = createCacheDBWriterService(cacheService, databaseService);
            cacheDBWriterService.start();
            logger.info("✅ Background DB writer service started");
            sce.getServletContext().setAttribute("cacheDBWriterService", cacheDBWriterService);
            // ==============================================================

            // ========== Step 4: Initialize Message Processing Executor ==========
            logger.info("Step 4: Initializing message processing executor...");
            messageProcessingExecutor = Executors.newFixedThreadPool(100); // Adjust size as needed
            sce.getServletContext().setAttribute("messageProcessingExecutor", messageProcessingExecutor);
            logger.info("✅ Message processing executor started");
            // =================================================================

            // ========== Step 5: Initialize Metrics Service ==========
            logger.info("Step 5: Initializing metrics service...");
            metricsService = new MetricsService(databaseService);
            metricsService.start();
            sce.getServletContext().setAttribute("metricsService", metricsService);
            logger.info("✅ Metrics service started");
            // ========================================================

            // ========== Step 6: Start message consumers ==========
            logger.info("Step 6: Initializing message consumer manager for lazy subscriptions...");
            consumerManager = MessageConsumerManager.getInstance();
            consumerManager.start(cacheService, messageProcessingExecutor);
            logger.info("✅ Message consumer manager started");
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

            // ========== Step 2: Stop message processing executor ==========
            if (messageProcessingExecutor != null) {
                logger.info("Step 2: Stopping message processing executor...");
                messageProcessingExecutor.shutdown();
                if (!messageProcessingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    messageProcessingExecutor.shutdownNow();
                }
                logger.info("✅ Message processing executor stopped");
            }
            // ==============================================================

            // ========== Step 3: Stop metrics service ==========
            if (metricsService != null) {
                logger.info("Step 3: Stopping metrics service...");
                metricsService.stop();
                logger.info("✅ Metrics service stopped");
            }
            // ==================================================

            // ========== Step 4: Stop background DB writer (flushes remaining) ==========
            if (cacheDBWriterService != null) {
                logger.info("Step 4: Stopping background DB writer...");
                cacheDBWriterService.stop();
                logger.info("✅ Background DB writer stopped");
            }
            // ============================================================================

            // ========== Step 5: Shutdown database service ==========
            if (databaseService != null) {
                logger.info("Step 5: Shutting down database service...");
                databaseService.shutdown();
                logger.info("✅ Database service shut down");
            }
            // ========================================================

            // ========== Step 6: Shutdown Valkey cache service ==========
            if (cacheService != null) {
                logger.info("Step 6: Shutting down Valkey cache service...");
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
    private CacheDBWriterService createCacheDBWriterService(ValkeyCacheService cacheService, DatabaseService databaseService) {
        // High-throughput configuration
        int batchSize = 500;        // Fetch up to 500 keys from cache per poll
        long pollIntervalMs = 50;   // Poll every 50 milliseconds
        int workerThreads = 20;     // Use 20 threads to process writes in parallel

        // Get database components
        DynamoDBBatchWriter batchWriter = getBatchWriter(databaseService);
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
     */
    private DynamoDBBatchWriter getBatchWriter(DatabaseService databaseService) {
        return new DynamoDBBatchWriter(
                databaseService.getDynamoDB(),
                DatabaseConfig.getDynamoDBTableName()
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
