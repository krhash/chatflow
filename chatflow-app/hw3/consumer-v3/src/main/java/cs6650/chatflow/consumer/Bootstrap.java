package cs6650.chatflow.consumer;

import cs6650.chatflow.consumer.database.DatabaseFactory;
import cs6650.chatflow.consumer.database.DatabaseService;
import cs6650.chatflow.consumer.messaging.MessageConsumerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Bootstrap listener that starts the message consumer infrastructure when the web application starts.
 * This ensures database and consumers are running before any WebSocket connections are established.
 */
@WebListener
public class Bootstrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

    private MessageConsumerManager consumerManager;
    private DatabaseService databaseService;  // ← NEW: Database service

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        logger.info("========================================");
        logger.info("Initializing Consumer Server...");
        logger.info("========================================");

        try {
            // ========== NEW: Initialize Database Service FIRST ==========
            logger.info("Step 1: Initializing database service...");
            databaseService = DatabaseFactory.createFromEnvironment();
            databaseService.initialize();
            logger.info("✅ Database service initialized: {}", databaseService.getDatabaseType());

            // Store in servlet context for access by servlets (e.g., MetricsServlet)
            sce.getServletContext().setAttribute("databaseService", databaseService);
            // ============================================================

            // ========== UPDATED: Start message consumers with database service ==========
            logger.info("Step 2: Starting message consumers...");
            consumerManager = MessageConsumerManager.getInstance();
            consumerManager.start(databaseService);  // ← UPDATED: Pass database service
            logger.info("✅ Started {} room consumers with database integration",
                    consumerManager.getConsumerCount());
            // ===========================================================================

            logger.info("========================================");
            logger.info("✅ Consumer Server Initialized Successfully");
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
            // ========== UPDATED: Stop consumers first ==========
            if (consumerManager != null && consumerManager.isStarted()) {
                logger.info("Step 1: Stopping message consumers...");
                consumerManager.stop();
                logger.info("✅ Message consumers stopped");
            }
            // ==================================================

            // ========== NEW: Shutdown database service (flushes pending writes) ==========
            if (databaseService != null) {
                logger.info("Step 2: Shutting down database service...");
                databaseService.shutdown();
                logger.info("✅ Database service shut down");
            }
            // ============================================================================

            logger.info("========================================");
            logger.info("✅ Consumer Server Shut Down Successfully");
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("========================================");
            logger.error("❌ Error shutting down consumer server", e);
            logger.error("========================================");
        }
    }
}
