package cs6650.chatflow.client.coordinator;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.MainPhaseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinator for main phase in client-part1.
 * Manages the main load testing phase after warmup.
 */
public class MainPhaseCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(MainPhaseCoordinator.class);

    private final String wsBaseUri;
    private final AtomicInteger totalMessagesSent;
    private final AtomicInteger totalMessagesReceived;

    /**
     * Creates main coordinator.
     * @param wsBaseUri WebSocket base URI (ws://server:port/servlet-context)
     * @param totalMessagesSent global sent counter
     * @param totalMessagesReceived global received counter
     */
    public MainPhaseCoordinator(String wsBaseUri, AtomicInteger totalMessagesSent, AtomicInteger totalMessagesReceived) {
        this.wsBaseUri = wsBaseUri;
        this.totalMessagesSent = totalMessagesSent;
        this.totalMessagesReceived = totalMessagesReceived;
    }

    /**
     * Executes the main phase.
     * @return MainPhaseResult containing test metrics
     */
    public MainPhaseResult execute() {
        logger.info("Starting main phase");

        long mainPhaseStartTime = System.currentTimeMillis();
        MainPhaseResult mainPhaseResult = null;

        try {
            int mainPhaseMessages = Constants.TOTAL_MESSAGES - Constants.WARMUP_TOTAL_MESSAGES;
            MainPhaseExecutor mainExecutor = new MainPhaseExecutor(wsBaseUri, mainPhaseMessages);
            mainPhaseResult = mainExecutor.execute();
            logger.info("Main phase completed successfully");
        } catch (Exception e) {
            logger.error("Error during main phase: {}", e.getMessage(), e);
            System.err.println("Error during main phase: " + e.getMessage());
            e.printStackTrace();
        }

        return mainPhaseResult;
    }
}
