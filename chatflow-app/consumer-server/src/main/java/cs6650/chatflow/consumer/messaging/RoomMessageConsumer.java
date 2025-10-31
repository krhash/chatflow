package cs6650.chatflow.consumer.messaging;

import cs6650.chatflow.consumer.model.ChatEvent;
import cs6650.chatflow.consumer.util.RoomManager;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.rabbitmq.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Message consumer that reads messages from a specific room queue and broadcasts them to WebSocket clients.
 */
public class RoomMessageConsumer {
    private static final Logger logger = LoggerFactory.getLogger(RoomMessageConsumer.class);
    private static final Gson gson = new Gson();

    private final String roomId;
    private final String queueName;
    private final Channel channel;
    private final RoomManager roomManager;
    private String consumerTag;

    public RoomMessageConsumer(String roomId, Channel channel) {
        this.roomId = roomId;
        this.queueName = "room." + roomId;
        this.channel = channel;
        this.roomManager = RoomManager.getInstance();
    }

    /**
     * Starts consuming messages from the room queue.
     */
    public void startConsuming() {
        try {
            logger.info("Starting consumer for room {}", roomId);

            // Declare the queue in case it doesn't exist (idempotent)
            channel.queueDeclare(queueName, true, false, false, null);
            logger.info("Declared queue: {}", queueName);

            // Create delivery callback
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String messageBody = new String(delivery.getBody(), StandardCharsets.UTF_8);
                logger.debug("Received message for room {}: {}", roomId, messageBody);

                try {
                    // Parse the message
                    ChatEvent event = gson.fromJson(messageBody, ChatEvent.class);
                    if (event == null) {
                        logger.warn("Failed to parse message for room {}: null event", roomId);
                        // Acknowledge invalid message to prevent re-delivery
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        return;
                    }

                    // Broadcast to WebSocket clients
                    boolean broadcastSuccessful = roomManager.broadcastToRoom(event, roomId);

                    if (broadcastSuccessful) {
                        // Acknowledge the message only after successful broadcast
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        logger.debug("Acknowledged message {} for room {}", event.getMessageId(), roomId);
                    } else {
                        // Do not acknowledge - message stays in queue for redelivery
                        // when clients are connected
                        logger.warn("Message {} not broadcast to room {} (no connected clients), message stays in queue", event.getMessageId(), roomId);
                    }

                } catch (JsonSyntaxException e) {
                    logger.error("Failed to parse JSON message for room {}: {}", roomId, e.getMessage());
                    // Acknowledge invalid message
                    try {
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    } catch (IOException ackEx) {
                        logger.error("Failed to acknowledge invalid message for room {}", roomId, ackEx);
                    }
                } catch (Exception e) {
                    logger.error("Error processing message for room {}: {}", roomId, e.getMessage(), e);
                    // For processing errors, we could choose to not acknowledge (re-deliver)
                    // or acknowledge depending on the error type. For now, acknowledge to prevent infinite loops.
                    try {
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        logger.debug("Acknowledged message after error processing for room {}", roomId);
                    } catch (IOException ackEx) {
                        logger.error("Failed to acknowledge message after processing error for room {}", roomId, ackEx);
                    }
                }
            };

            // Create cancel callback
            CancelCallback cancelCallback = consumerTag -> {
                logger.warn("Consumer cancelled for room {}", roomId);
            };

            // Start consuming (manual acknowledgment)
            consumerTag = channel.basicConsume(queueName, false, deliverCallback, cancelCallback);
            logger.info("Started consuming messages for room {} with consumer tag {}", roomId, consumerTag);

        } catch (IOException e) {
            logger.error("Failed to start consumer for room {}", roomId, e);
            throw new RuntimeException("Failed to start consumer for room " + roomId, e);
        }
    }

    /**
     * Stops consuming messages.
     */
    public void stopConsuming() {
        if (consumerTag != null && channel.isOpen()) {
            try {
                channel.basicCancel(consumerTag);
                logger.info("Stopped consumer for room {} with tag {}", roomId, consumerTag);
            } catch (IOException e) {
                logger.error("Failed to stop consumer for room {}", roomId, e);
            }
        }
    }

    public String getRoomId() {
        return roomId;
    }

    public String getQueueName() {
        return queueName;
    }
}
