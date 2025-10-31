# ChatFlow Consumer Server

## Overview

The ChatFlow Consumer Server is a WebSocket-based server that receives chat messages from RabbitMQ room queues and broadcasts them to connected WebSocket clients. This server works together with the ChatFlow Producer Server to provide a complete chat messaging system.

## Architecture

```
RabbitMQ Room Queues → Consumer Server → WebSocket Broadcast
       ↓                       ↓               ↓
   room.1 to room.20     RoomMessageConsumer  Clients in rooms
                         MessageConsumerManager
                         RoomManager (state)
```

## Key Components

### WebSocket Endpoint
- **Path**: `/chatflow-receiver/{roomId}`
- **Purpose**: Clients connect to specific room endpoints to receive messages
- **Heartbeat**: Automatic ping/pong to keep connections alive
- **Validation**: Room ID validation (1-20)

### Message Consumer Infrastructure
- **MessageConsumerManager**: Singleton manager for all room consumers
- **RoomMessageConsumer**: Individual consumer for each room queue
- **Manual Acknowledgments**: Ensures at-least-once delivery

### Room State Management
- **RoomManager**: Thread-safe management of WebSocket sessions per room
- **ConcurrentHashMap**: Room → Set of active sessions
- **Broadcasting**: Efficient message distribution to all room clients

## Deployment

### Prerequisites
- Java 11+
- Apache Tomcat 9+
- RabbitMQ server running with chat.exchange topic exchange

### Building
```bash
cd consumer-server
mvn clean package
```

### WebSocket Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/chatflow-receiver/{roomId}` | WebSocket | Connect to receive messages for room {roomId} (1-20) |

### Error Messages
- `Invalid room ID`: Room ID must be integer 1-20
- `Internal server error`: Generic server error

## Configuration

Configuration is managed via `src/main/resources/rabbitmq.properties`:

```properties
# RabbitMQ connection settings
rabbitmq.host=localhost
rabbitmq.port=5672
rabbitmq.username=guest
rabbitmq.password=guest

# Exchange configuration
rabbitmq.exchange=chat.exchange
rabbitmq.exchange.type=topic

# Consumer settings
rabbitmq.consumer.pool.size=20
rabbitmq.consumer.auto.ack=false
```

## Startup Process

1. **Bootstrap Listener**: Starts on webapp initialization
2. **MessageConsumerManager**: Initializes RabbitMQ connection
3. **Room Consumers**: Creates 20 consumers (one per room)
4. **WebSocket Ready**: Clients can now connect and receive messages

## Monitoring

The server provides detailed logging for:
- Consumer connection status
- Message processing and acknowledgments
- WebSocket session management
- Room activity (clients per room)

## Fault Tolerance

- **Connection Recovery**: Automatic reconnection to RabbitMQ
- **Message Acknowledgment**: ACK only after successful broadcast to connected clients
- **Message Persistence**: Unbroadcast messages remain in queue until clients connect
- **At-Least-Once Delivery**: Messages are delivered to all connected clients, at-least-once
- **Session Cleanup**: Automatic removal of closed WebSocket sessions
- **Consumer Restart**: Failed consumers don't stop the entire system

## Message Flow & Acknowledgments

1. **Producer** publishes message to `chat.exchange` with `room.{id}` routing key
2. **Consumer** receives message from `room.{id}` queue
3. **Consumer** attempts broadcast to all WebSocket clients in room `{id}`
   - If **clients are connected**: Message is broadcast → ACK sent → Message removed from queue
   - If **no clients connected**: Message stays in queue (not acknowledged) → Redelivery when clients connect
4. **Clients** receive messages via WebSocket `/chatflow-receiver/{roomId}`
