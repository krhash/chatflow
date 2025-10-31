package cs6650.chatflow.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton manager for MessagePublisher that handles lifecycle and provides global access.
 * Integrates with Tomcat's servlet lifecycle for proper initialization and cleanup.
 */
@WebListener
public class MessagePublisherManager implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(MessagePublisherManager.class);
    private static final AtomicReference<MessagePublisher> instance = new AtomicReference<>();

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        try {
            logger.info("Initializing MessagePublisherManager");

            // Initialize channel pool
            ChannelPool channelPool = new ChannelPool();
            sce.getServletContext().setAttribute("channelPool", channelPool);

            // Initialize message publisher
            MessagePublisher publisher = new MessagePublisher(channelPool);
            instance.set(publisher);

            logger.info("MessagePublisherManager initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize MessagePublisherManager", e);
            throw new RuntimeException("MessagePublisher initialization failed", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        logger.info("Destroying MessagePublisherManager");

        MessagePublisher publisher = instance.get();
        if (publisher != null) {
            // Get channel pool and close it
            ChannelPool channelPool = (ChannelPool) sce.getServletContext().getAttribute("channelPool");
            if (channelPool != null) {
                channelPool.close();
            }

            instance.set(null);
        }

        logger.info("MessagePublisherManager destroyed");
    }

    /**
     * Gets the singleton MessagePublisher instance.
     * @return MessagePublisher instance or throws exception if not initialized
     */
    public static MessagePublisher getInstance() {
        MessagePublisher publisher = instance.get();
        if (publisher == null) {
            throw new IllegalStateException("MessagePublisherManager not initialized. Check Tomcat startup logs.");
        }
        return publisher;
    }

    /**
     * Gets the ChannelPool instance (for lifecycle management).
     */
    public static ChannelPool getChannelPool(ServletContextEvent sce) {
        return (ChannelPool) sce.getServletContext().getAttribute("channelPool");
    }
}
