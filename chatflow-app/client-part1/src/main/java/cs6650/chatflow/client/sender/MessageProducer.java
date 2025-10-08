package cs6650.chatflow.client.sender;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.util.MessageGenerator;
import cs6650.chatflow.client.util.MessageQueue;

/**
 * Producer thread that generates messages and puts them in the queue.
 * Runs continuously until all messages are generated.
 */
public class MessageProducer implements Runnable {

    private final MessageQueue messageQueue;
    private final int totalMessages;

    /**
     * Creates message producer.
     * @param messageQueue queue to put generated messages
     * @param totalMessages total number of messages to generate
     */
    public MessageProducer(MessageQueue messageQueue, int totalMessages) {
        this.messageQueue = messageQueue;
        this.totalMessages = totalMessages;
    }

    @Override
    public void run() {
        try {
            System.out.println("MessageProducer: Starting message generation...");

            for (int i = 0; i < totalMessages; i++) {
                ChatMessage message = MessageGenerator.generateRandomMessage();
                messageQueue.put(message);

                // Progress reporting
                if ((i + 1) % Constants.PROGRESS_REPORT_INTERVAL == 0) {
                    System.out.printf("MessageProducer: Generated %d/%d messages%n",
                        i + 1, totalMessages);
                }
            }

            System.out.println("MessageProducer: Completed message generation");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("MessageProducer: Interrupted during message generation");
        }
    }
}
