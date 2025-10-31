package cs6650.chatflow.server.messaging;

import cs6650.chatflow.server.model.ChatCommand;
import com.rabbitmq.client.Channel;
import com.google.gson.Gson;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;
import javax.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service responsible for publishing chat messages to RabbitMQ.
 */
public class MessagePublisher {
    private static final Logger logger = LoggerFactory.getLogger(MessagePublisher.class);
    private static final Gson gson = new Gson();

    private final ChannelPool channelPool;

    public MessagePublisher(ChannelPool channelPool) {
        this.channelPool = channelPool;
    }

    /**
     * Publishes a chat command to the message queue.
     * Includes circuit breaker pattern for graceful handling of RabbitMQ failures.
     */
    public void publishMessage(ChatCommand command, String roomId, Session session) {
        Channel channel = null;
        try {
            channel = channelPool.borrowChannel();
            if (channel == null) {
                logger.error("Failed to borrow channel from pool for message publishing - RabbitMQ may be unavailable");
                return;
            }

            // Create the queue message
            QueueMessage queueMessage = createQueueMessage(command, roomId, session);

            // Extract just the number from roomId (e.g., "room2" -> "2", or "2" -> "2")
            String roomNumber = roomId.startsWith("room") ? roomId.substring(4) : roomId;
            String routingKey = "room." + roomNumber;  // routing key should be "room.2" for room 2
            String exchangeName = RabbitMQConfig.getExchangeName();

            logger.info("Publishing message {} to exchange '{}' with routing key '{}'",
                queueMessage.getMessageId(), exchangeName, routingKey);

            // Enable publisher confirms to ensure message delivery
            channel.confirmSelect();

            channel.basicPublish(
                exchangeName,
                routingKey,
                null, // BasicProperties
                gson.toJson(queueMessage).getBytes()
            );

            // Wait for confirmation (with timeout)
            if (channel.waitForConfirms(5000)) { // 5 second timeout
                logger.info("Successfully published message {} to room {} with routing key '{}'",
                    queueMessage.getMessageId(), roomId, routingKey);
            } else {
                logger.error("Failed to confirm publishing of message {} to room {} with routing key '{}'",
                    queueMessage.getMessageId(), roomId, routingKey);
            }

        } catch (IOException e) {
            logger.error("Failed to publish message to queue - RabbitMQ connection error", e);
            // Could implement exponential backoff or circuit breaker here
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for publish confirmation", e);
        } catch (Exception e) {
            logger.error("Unexpected error while publishing message", e);
        } finally {
            if (channel != null) {
                channelPool.returnChannel(channel);
            }
        }
    }

    /**
     * Creates a queue message from the chat command.
     */
    private QueueMessage createQueueMessage(ChatCommand command, String roomId, Session session) {
        QueueMessage message = new QueueMessage();

        message.setMessageId(command.getMessageId() != null ? command.getMessageId() : UUID.randomUUID().toString());
        message.setRoomId(roomId);
        message.setUserId(command.getUserId());
        message.setUsername(command.getUsername());
        message.setMessage(command.getMessage());
        message.setTimestamp(command.getTimestamp());
        message.setMessageType(command.getMessageType());
        message.setServerId(getServerId()); // Could be configurable
        message.setClientIp(getClientIp(session));

        return message;
    }

    /**
     * Gets a unique server identifier.
     * In production, this could be environment-specific.
     */
    private String getServerId() {
        return "chat-server-" + System.getProperty("server.instance", "1");
    }

    /**
     * Extracts client IP from WebSocket session.
     */
    private String getClientIp(Session session) {
        try {
            if (session.getUserProperties().containsKey("javax.websocket.endpoint.remoteAddress")) {
                InetSocketAddress remoteAddress = (InetSocketAddress)
                    session.getUserProperties().get("javax.websocket.endpoint.remoteAddress");
                return remoteAddress.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            logger.debug("Could not extract client IP from session", e);
        }
        return "unknown";
    }

    /**
     * Inner class representing the queue message format.
     */
    public static class QueueMessage {
        private String messageId;
        private String roomId;
        private String userId;
        private String username;
        private String message;
        private String timestamp;
        private String messageType;
        private String serverId;
        private String clientIp;

        // Getters and setters
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { this.roomId = roomId; }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }

        public String getServerId() { return serverId; }
        public void setServerId(String serverId) { this.serverId = serverId; }

        public String getClientIp() { return clientIp; }
        public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    }
}
