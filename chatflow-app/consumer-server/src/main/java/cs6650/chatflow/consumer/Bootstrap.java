package cs6650.chatflow.consumer;

import cs6650.chatflow.consumer.messaging.MessageConsumerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Bootstrap listener that starts the message consumer infrastructure when the web application starts.
 * This ensures consumers are running before any WebSocket connections are established.
 */
@WebListener
public class Bootstrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

    private MessageConsumerManager consumerManager;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        logger.info("Initializing consumer server...");

        try {
            // Start the message consumer manager (creates consumers for all rooms)
            consumerManager = MessageConsumerManager.getInstance();
            consumerManager.start();

            logger.info("Consumer server initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize consumer server", e);
            throw new RuntimeException("Consumer server initialization failed", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        logger.info("Shutting down consumer server...");

        try {
            if (consumerManager != null && consumerManager.isStarted()) {
                consumerManager.stop();
                logger.info("Consumer server shut down successfully");
            }
        } catch (Exception e) {
            logger.error("Error shutting down consumer server", e);
        }
    }
}
