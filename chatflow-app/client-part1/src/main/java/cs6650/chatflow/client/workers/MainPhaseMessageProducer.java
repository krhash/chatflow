package cs6650.chatflow.client.workers;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.util.MessageGenerator;
import cs6650.chatflow.client.queues.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Producer thread that generates messages and puts them in the queue.
 * Runs continuously until all messages are generated.
 */
public class MainPhaseMessageProducer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(MainPhaseMessageProducer.class);

    private final MessageQueue messageQueue;
    private final int totalMessages;

    /**
     * Creates message producer.
     * @param messageQueue queue to put generated messages
     * @param totalMessages total number of messages to generate
     */
    public MainPhaseMessageProducer(MessageQueue messageQueue, int totalMessages) {
        this.messageQueue = messageQueue;
        this.totalMessages = totalMessages;
    }

    @Override
    public void run() {
        try {
            logger.info("Starting message generation...");

            for (int i = 0; i < totalMessages; i++) {
                ChatMessage message = MessageGenerator.generateRandomMessage();
                messageQueue.put(message);

                // Progress reporting - debug level to reduce verbosity
                if ((i + 1) % Constants.PROGRESS_REPORT_INTERVAL == 0) {
                    logger.debug("Generated {}/{} messages", i + 1, totalMessages);
                }
            }

            logger.info("Completed message generation");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted during message generation");
        }
    }
}
