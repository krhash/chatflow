package cs6650.chatflow.consumer;

import cs6650.chatflow.consumer.messaging.MessageConsumerManager;
import cs6650.chatflow.consumer.messaging.RoomMessageConsumer;
import cs6650.chatflow.consumer.util.RoomManager;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes message acknowledgments and signals RabbitMQ message acknowledgment
 * when all users in a room have acknowledged receipt.
 */
public class MessageAcknowledgmentProcessor {
    private static final Logger logger = LoggerFactory.getLogger(MessageAcknowledgmentProcessor.class);

    private final RoomManager roomManager;
    private final MessageConsumerManager consumerManager;

    private static class SingletonHolder {
        private static final MessageAcknowledgmentProcessor INSTANCE = new MessageAcknowledgmentProcessor();
    }

    public static MessageAcknowledgmentProcessor getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private MessageAcknowledgmentProcessor() {
        this.roomManager = RoomManager.getInstance();
        this.consumerManager = MessageConsumerManager.getInstance();
    }

    /**
     * Processes an acknowledgment from a user and sends RabbitMQ ACK if message is fully acknowledged.
     */
    public void processAcknowledgment(String messageId, String roomId) {
        if (roomManager.canAcknowledgeMessage(messageId, roomId)) {
            // Find the consumer for this room and send acknowledgment
            RoomMessageConsumer consumer = consumerManager.getConsumer(roomId);
            if (consumer != null) {
                // This would need to be implemented to allow ACK from the processor
                // For now, we'll rely on the consumer checking acknowledgment state periodically
                // or we could add a callback mechanism
                logger.info("Message {} in room {} ready for acknowledgment", messageId, roomId);
            }
        }
    }

    /**
     * Periodically checks for fully acknowledged messages and sends RabbitMQ ACKs.
     * This should be called from a scheduled task.
     */
    public void checkPendingAcknowledgments() {
        // This method would iterate through all room states and check for fully acknowledged messages
        // Implementation depends on MessageConsumerManager having access to channels for ACK
        logger.debug("Checking pending acknowledgments");
    }
}
