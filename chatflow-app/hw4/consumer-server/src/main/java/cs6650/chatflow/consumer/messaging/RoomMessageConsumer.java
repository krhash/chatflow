package cs6650.chatflow.consumer.messaging;

import cs6650.chatflow.consumer.cache.ValkeyCacheService;
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
 * Message consumer with cache-based deduplication for horizontal scaling.
 *
 * DEDUPLICATION STRATEGY:
 * 1. Check if message exists in cache
 * 2. If EXISTS → Another instance already processed it → Skip broadcast
 * 3. If NOT EXISTS → First instance to see it → Write to cache + Broadcast
 *
 * This prevents duplicate WebSocket broadcasts when multiple consumer instances
 * receive the same message from RabbitMQ.
 */
public class RoomMessageConsumer {
    private static final Logger logger = LoggerFactory.getLogger(RoomMessageConsumer.class);
    private static final Gson gson = new Gson();

    private final String roomId;
    private final String queueName;
    private final Channel channel;
    private final RoomManager roomManager;
    private final ValkeyCacheService cacheService;
    private String consumerTag;

    /**
     * Create room message consumer with cache-based deduplication
     *
     * @param roomId Room identifier
     * @param channel RabbitMQ channel
     * @param cacheService Valkey cache service (required for deduplication)
     */
    public RoomMessageConsumer(String roomId, Channel channel, ValkeyCacheService cacheService) {
        this.roomId = roomId;
        this.queueName = "room." + roomId;
        this.channel = channel;
        this.roomManager = RoomManager.getInstance();
        this.cacheService = cacheService;
    }

    /**
     * Starts consuming messages from the room queue.
     */
    public void startConsuming() {
        try {
            logger.info("Starting consumer for room {} with cache-based deduplication", roomId);

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
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        return;
                    }

                    // ========== CACHE-BASED DEDUPLICATION ==========
                    // Check if message already exists in cache (another instance processed it)
                    boolean shouldBroadcast = checkAndCacheMessage(event, roomId);

                    if (!shouldBroadcast) {
                        logger.debug("⏩ Message {} already in cache, skipping broadcast (processed by another instance)",
                                event.getMessageId());
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        return;
                    }
                    // ===============================================

                    // Handle different message types
                    String messageType = event.getMessageType();

                    if ("JOIN".equals(messageType)) {
                        // Handle JOIN message
                        roomManager.addUserToRoom(event.getUserId(), roomId);
                        logger.debug("User {} joined room {}", event.getUserId(), roomId);
                        roomManager.broadcastToRoom(event, roomId);
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        return;

                    } else if ("LEAVE".equals(messageType)) {
                        // Handle LEAVE message
                        roomManager.removeUserFromRoom(event.getUserId(), roomId);
                        logger.debug("User {} left room {}", event.getUserId(), roomId);
                        roomManager.broadcastToRoom(event, roomId);
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        return;

                    } else if ("ACK".equals(messageType)) {
                        // Handle ACK messages
                        try {
                            boolean broadcastSuccessful = roomManager.broadcastToRoom(event, roomId);
                            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                            if (broadcastSuccessful) {
                                logger.debug("ACK message {} broadcast successfully", event.getMessageId());
                            } else {
                                logger.debug("ACK message {} acknowledged but no connected clients",
                                        event.getMessageId());
                            }
                        } catch (Exception broadcastError) {
                            logger.error("Failed to broadcast ACK message {}: {}",
                                    event.getMessageId(), broadcastError.getMessage());
                            try {
                                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            } catch (IOException ackEx) {
                                logger.error("Failed to acknowledge ACK message: {}",
                                        ackEx.getMessage());
                            }
                        }

                    } else {
                        // Handle regular TEXT messages
                        try {
                            boolean broadcastSuccessful = roomManager.broadcastToRoom(event, roomId);

                            // Always acknowledge immediately after broadcast
                            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                            if (broadcastSuccessful) {
                                logger.debug("✅ Message {} broadcast to clients (first instance to process)",
                                        event.getMessageId());
                            } else {
                                logger.warn("Message {} acknowledged but no connected clients",
                                        event.getMessageId());
                            }
                        } catch (Exception broadcastError) {
                            logger.error("Failed to broadcast message {}: {}",
                                    event.getMessageId(), broadcastError.getMessage());
                            try {
                                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            } catch (IOException ackEx) {
                                logger.error("Failed to acknowledge message: {}",
                                        ackEx.getMessage());
                            }
                        }
                    }

                } catch (JsonSyntaxException e) {
                    logger.error("Failed to parse JSON message for room {}: {}",
                            roomId, e.getMessage());
                    try {
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    } catch (IOException ackEx) {
                        logger.error("Failed to acknowledge invalid message: {}", ackEx.getMessage());
                    }

                } catch (Exception e) {
                    logger.error("Error processing message for room {}: {}",
                            roomId, e.getMessage(), e);
                    try {
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    } catch (IOException ackEx) {
                        logger.error("Failed to acknowledge message after error: {}",
                                ackEx.getMessage());
                    }
                }
            };

            // Create cancel callback
            CancelCallback cancelCallback = consumerTag -> {
                logger.warn("Consumer cancelled for room {}", roomId);
            };

            // Start consuming (manual acknowledgment)
            consumerTag = channel.basicConsume(queueName, false, deliverCallback, cancelCallback);
            logger.info("✅ Started consuming messages for room {} with cache deduplication",
                    roomId, consumerTag);

        } catch (IOException e) {
            logger.error("Failed to start consumer for room {}", roomId, e);
            throw new RuntimeException("Failed to start consumer for room " + roomId, e);
        }
    }

    /**
     * Atomically cache message to prevent duplicate broadcasts.
     *
     * Uses Redis SETNX (SET if Not eXists) - ATOMIC operation:
     * - Returns true → This instance wrote it FIRST → Broadcast
     * - Returns false → Another instance already wrote it → Skip broadcast
     *
     * This is a single atomic operation, not check-then-set (no race condition).
     *
     * @param event The chat event
     * @param roomId The room ID
     * @return true if this instance should broadcast (first to cache), false otherwise
     */
    private boolean checkAndCacheMessage(ChatEvent event, String roomId) {
        if (cacheService == null) {
            logger.warn("Cache service not available, broadcasting anyway (no deduplication)");
            return true;
        }

        try {
            // Atomic SETNX operation - only succeeds if key doesn't exist
            boolean isFirstInstance = cacheService.cacheMessageAtomic(event, roomId);

            if (isFirstInstance) {
                logger.trace("✅ First instance to cache message {} - will broadcast",
                        event.getMessageId());
                return true; // We are the first - broadcast
            } else {
                logger.debug("🔄 Message {} already cached by another instance - skipping broadcast",
                        event.getMessageId());
                return false; // Another instance beat us - skip broadcast
            }

        } catch (Exception e) {
            logger.error("Error during atomic cache operation for message {}: {}",
                    event.getMessageId(), e.getMessage());
            // On error, DON'T broadcast to avoid duplicates
            // Better to miss one broadcast than send duplicates
            return false;
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
