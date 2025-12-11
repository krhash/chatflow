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
import java.util.concurrent.ExecutorService;

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
    private static final int PREFETCH_COUNT = 200; // QoS setting

    private final String roomId;
    private final String queueName;
    private final Channel channel;
    private final RoomManager roomManager;
    private final ValkeyCacheService cacheService;
    private final ExecutorService messageProcessingExecutor;
    private String consumerTag;

    /**
     * Create room message consumer with cache-based deduplication
     *
     * @param roomId Room identifier
     * @param channel RabbitMQ channel
     * @param cacheService Valkey cache service (required for deduplication)
     */
    public RoomMessageConsumer(String roomId, Channel channel, ValkeyCacheService cacheService, ExecutorService messageProcessingExecutor) {
        this.roomId = roomId;
        this.queueName = "room." + roomId;
        this.channel = channel;
        this.roomManager = RoomManager.getInstance();
        this.cacheService = cacheService;
        this.messageProcessingExecutor = messageProcessingExecutor;
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

            // Set QoS (prefetch count) to prevent consumer from hoarding messages
            channel.basicQos(PREFETCH_COUNT);
            logger.info("Set prefetch count to {} for room {}", PREFETCH_COUNT, roomId);

            // Create delivery callback
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                // ACK immediately
                try {
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                } catch (IOException e) {
                    logger.error("Failed to acknowledge message: {}", e.getMessage());
                }

                // Process asynchronously
                messageProcessingExecutor.submit(() -> {
                    try {
                        String messageBody = new String(delivery.getBody(), StandardCharsets.UTF_8);
                        logger.debug("Received message for room {}: {}", roomId, messageBody);

                        ChatEvent event = gson.fromJson(messageBody, ChatEvent.class);
                        if (event == null) {
                            logger.warn("Failed to parse message for room {}: null event", roomId);
                            return;
                        }

                        String messageType = event.getMessageType();

                        // Always process JOIN/LEAVE to maintain user state
                        if ("JOIN".equals(messageType)) {
                            roomManager.addUserToRoom(event.getUserId(), roomId);
                            logger.debug("User {} joined room {}", event.getUserId(), roomId);
                            return;
                        } else if ("LEAVE".equals(messageType)) {
                            roomManager.removeUserFromRoom(event.getUserId(), roomId);
                            logger.debug("User {} left room {}", event.getUserId(), roomId);
                            return;
                        }

                        // For broadcastable messages, check for active connections first.
                        if (roomManager.getRoomSessionCount(roomId) == 0) {
                            logger.trace("No active connections in room {}, ignoring message {}", roomId, event.getMessageId());
                            return;
                        }

                        // ========== CACHE-BASED DEDUPLICATION ==========
                        boolean shouldBroadcast = checkAndCacheMessage(event, roomId);
                        if (!shouldBroadcast) {
                            logger.debug("⏩ Message {} already in cache, skipping broadcast (processed by another instance)", event.getMessageId());
                            return;
                        }
                        // ===============================================

                        // Broadcast all other message types (TEXT, ACK, etc.)
                        boolean broadcastSuccessful = roomManager.broadcastToRoom(event, roomId);
                        if (broadcastSuccessful) {
                            logger.debug("✅ Message {} broadcast to clients (first instance to process)", event.getMessageId());
                        } else {
                            logger.warn("Message {} was cached but broadcast failed (no clients connected at broadcast time)", event.getMessageId());
                        }

                    } catch (JsonSyntaxException e) {
                        logger.error("Failed to parse JSON message for room {}: {}", roomId, e.getMessage());
                    } catch (Exception e) {
                        logger.error("Error processing message for room {}: {}", roomId, e.getMessage(), e);
                    }
                });
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
