# ChatFlow Consumer Server

## Overview

The ChatFlow Consumer Server is a WebSocket-based server that receives chat messages from RabbitMQ room queues and broadcasts them to connected WebSocket clients. This server works together with the ChatFlow Producer Server to provide a complete chat messaging system.

## Key Features

- **Immediate Acknowledgment**: Messages are acknowledged to RabbitMQ immediately after broadcast attempt
- **User-based State Tracking**: Each room tracks connected users via JOIN/LEAVE messages
- **Real-time Broadcasting**: Messages are delivered to all connected WebSocket clients in real-time
- **Optional Client Acknowledgments**: Client-side ACKs provide QoS feedback (not blocking)
- **At-least-once Delivery**: Every message reaches connected clients via reliable queuing

## Architecture

```
Producer Server → RabbitMQ Queue → Consumer Server → User Acknowledgments → Message ACK
                        ↓                                      ↑                    ↑
                     Messages in queue for offline users   WebSocket clients   RabbitMQ ACK
```

## Message Flow & Acknowledgments

### 1. Producer publishes message to room queue
- Message contains `userId`, `roomId`, `messageId`
- Stored in `room.{id}` queue

### 2. Consumer receives message
- Checks if target `userId` is connected to room
- If **user not connected**: **Do not acknowledge** - message stays in queue
- If **user connected**: Broadcast to all active users via WebSocket

### 3. Message delivery tracking
- Consumer tracks which users received the message
- Starts acknowledgment countdown for all active users

### 4. Client acknowledgments
- Clients send ACK messages: `{"messageType":"ack","messageId":"123","userId":"user1"}`
- Server marks each user as acknowledged

### 5. Complete message acknowledgment
- **Only when ALL active users acknowledge**: Send RabbitMQ ACK → Message removed from queue
- **If users disconnect without acknowledging**: Message stays in queue

## Enhanced State Management

### RoomState
- **Active Users**: Map of userId → User (with WebSocket session)
- **Message Delivery States**: Track per-message acknowledgment status
- **Concurrent Access**: Thread-safe for multi-user scenarios

### MessageDeliveryState
- **Active User Set**: Users connected when message was received
- **Delivered Set**: Users who received the message
- **Acknowledged Set**: Users who sent acknowledgment
- **Automatic Cleanup**: Removed when all users acknowledge

## WebSocket Client Protocol

### Connection
```
ws://localhost:8081/consumer-server/chatflow-receiver/{roomId}
```

**Note**: User identification is handled through JOIN messages from the producer server. WebSocket connections are anonymous initially.

### Message Receipt
Server forwards all message types to connected WebSocket clients:
- **JOIN** messages: User joined room - add to active users
- **TEXT** messages: Regular chat messages
- **LEAVE** messages: User left room - remove from active users

Clients **MUST** send acknowledgment for **every message received**.

### Client Acknowledgment (Client → Server)
Client identifies itself by including `userId` in the ACK message:
```json
{
  "messageType": "ack",
  "messageId": "join-123",
  "userId": "user1",
  "roomId": "1"
}
```

## User Management via Queue Messages

### JOIN Messages
When a **JOIN** message is received from the queue for a user:
1. Add user to room's active user list (without WebSocket session initially)
2. When WebSocket client connects and identifies via ACK, associate with user
3. User is now considered "active" for message delivery

### LEAVE Messages
When a **LEAVE** message is received:
1. Mark user as inactive in the room
2. Remove from active user list
3. User no longer receives room messages

## Offline User Handling

**Critical Requirement**: Messages are not acknowledged if intended user is offline.

### Scenario A: User online when message arrives
1. Message broadcast to all connected WebSocket clients
2. Track acknowledgments from all active users
3. When all users acknowledge → RabbitMQ ACK → Message removed

### Scenario B: User offline when message arrives
1. JOIN message hasn't been received yet
2. Target user not in active user list
3. No broadcast attempt → No ACK → Message stays in queue
4. Message redelivered when user sends JOIN

### Scenario C: User disconnects before acknowledging
1. User misses sending acknowledgment
2. Message stays in queue (incomplete acknowledgment)
3. When user reconnects → Receives message again
4. Acknowledges → Complete acknowledgment cycle

## Consumer Implementation

### RoomMessageConsumer Updates
- **Conditional Acknowledgment**: Only ACK if message delivered to intended recipients
- **User Validation**: Check if target user is active before attempting delivery
- **Delivery Tracking**: Start tracking when message broadcast successfully

### RoomManager Enhancements
- **User State Management**: Add/remove users with sessions
- **Message Delivery Tracking**: Per-message acknowledgment state
- **Acknowledgment Processing**: Handle client ACK messages and signal completion

### ChatReceiverWebSocketEndpoint
- **User Identification**: Extract `userId` from connection parameters
- **ACK Message Handling**: Process client acknowledgments
- **Connection Lifecycle**: Track user connections/disconnections

## Deployment & Testing

### Prerequisites
- Java 11+ with servlets
- RabbitMQ with topic exchange
- WebSocket-compliant clients

### Building and Testing
- Messages persist until all users acknowledge
- Offline users don't block message processing for online users
- Duplicate prevention handled at application level
- Redelivery works correctly when users reconnect

## Monitoring & Debugging

The enhanced consumer provides detailed logging for:
- User connection/disconnection events
- Message delivery attempts and failures
- Acknowledgment processing
- Queue depth management
- Delivery state transitions

## Fault Tolerance

- **Network Issues**: Automatic reconnection and redelivery
- **Client Crashes**: Unacknowledged messages redelivered
- **Server Restarts**: Message state recovered from queues
- **Partial Acknowledgments**: Wait for remaining users before ACK

This implementation ensures every message reaches every intended user at least once, with no message loss even in complex failure scenarios.
