# ChatFlow - WebSocket Performance Testing System

A high-throughput WebSocket chat system for performance testing with producer-consumer architecture.

## Architecture Overview

### Server Component
- **WebSocket Endpoint**: Handles chat commands and sends responses with heartbeat mechanism
- **REST Health Check**: Simple health endpoint for monitoring
- **Validation**: Comprehensive input validation with centralized constants

### Client Components
- **Client-Part1**: Multi-threaded load testing with producer-consumer pattern
- **Client-Part2**: Alternative client implementation (similar architecture)

## Prerequisites

- Java 8 or higher
- Apache Tomcat 9+ (for server deployment)
- Maven 3.6+

## Server Setup

### Build Server
```bash
cd server
mvn clean package
```

### Deploy to Tomcat
1. Copy `server/target/server-1.0-SNAPSHOT.war` to Tomcat's `webapps/` directory
2. Rename to `chatflow-server.war` (or your preferred context name)
3. Start Tomcat

### Server Endpoints
- **WebSocket**: `ws://localhost:8080/chatflow-server/chat/{roomId}`
- **Health Check**: `http://localhost:8080/chatflow-server/health`

### IntelliJ Run Configuration (Server)
The server is deployed as a WAR file to Tomcat. No direct IntelliJ run configuration needed.

## Client Setup

### Build Clients
```bash
# Build both clients
cd client-part1 && mvn clean package
cd ../client-part2 && mvn clean package
```

### Run Client-Part1
```bash
java -jar client-part1/target/client-part1-1.0-SNAPSHOT.jar <server-ip> <port> <servlet-context>
```

**Arguments:**
- `server-ip`: Server IP address (e.g., `localhost` or `192.168.1.100`)
- `port`: Server port (e.g., `8080`)
- `servlet-context`: Tomcat context path (e.g., `chatflow-server`)

**Examples:**
```bash
# Local development
java -jar client-part1/target/client-part1-1.0-SNAPSHOT.jar localhost 8080 chatflow-server

# Remote server
java -jar client-part1/target/client-part1-1.0-SNAPSHOT.jar 192.168.1.100 8080 chatflow-server
```

### IntelliJ Run Configuration (Client-Part1)

1. **Create Run Configuration:**
   - Go to `Run` → `Edit Configurations`
   - Click `+` → `Application`
   - Set `Main class`: `cs6650.chatflow.client.Main`
   - Set `Working directory`: `/path/to/chatflow-app/client-part1`
   - Set `Use classpath of module`: `client-part1`

2. **Program Arguments:**
   ```
   localhost 8080 chatflow-server
   ```

3. **VM Options** (optional):
   ```
   -Xmx2g -Xms1g
   ```

### Run Client-Part2
```bash
java -jar client-part2/target/client-part2-1.0-SNAPSHOT.jar <server-ip> <port> <servlet-context>
```
*(Same arguments as client-part1)*

## Flow of Control

### Server Flow
```
WebSocket Connection Request
├── Validate roomId parameter
├── Establish WebSocket connection
├── Start heartbeat scheduler (30s intervals)
└── Ready for message processing

Incoming ChatCommand (JSON)
├── Parse JSON → ChatCommand object
├── Validate fields (userId, username, message, etc.)
├── Create ChatEventResponse with server timestamp
├── Send response back to client
└── Log processing details

Connection Management
├── Handle ping/pong for keepalive
├── Process disconnections gracefully
└── Cleanup heartbeat schedulers
```

### Client-Part1 Flow

#### Phase 1: Warmup (Sequential)
```
Main Thread
├── Parse command line arguments
├── Initialize global counters
├── Create WarmupPhaseExecutor
│   ├── Launch WARMUP_THREADS worker threads
│   │   ├── Each WarmupWorker connects to assigned room
│   │   ├── Sends MESSAGES_PER_THREAD messages sequentially
│   │   └── Waits for all responses before completing
│   └── Wait for all threads to finish sending AND receiving
└── Generate warmup phase report
```

#### Phase 2: Main Load Test (Producer-Consumer)
```
Main Thread
├── Create MainPhaseExecutor
│   ├── Initialize components:
│   │   ├── MessageQueue (bounded)
│   │   ├── ResponseQueue (bounded)
│   │   ├── DeadLetterQueue (unbounded)
│   │   ├── MessageTimer
│   │   └── WebSocketConnectionPool (20 connections)
│   │
│   ├── Start worker threads:
│   │   ├── 1 MessageProducer → generates messages → MessageQueue
│   │   ├── N MessageConsumers → MessageQueue → WebSocket sends
│   │   ├── M ResponseWorkers → ResponseQueue → match responses
│   │   ├── 1 TimeoutMonitor → MessageTimer → DeadLetterQueue
│   │   └── P RetryWorkers → DeadLetterQueue → retry sends
│   │
│   ├── Wait for completion:
│   │   ├── All messages sent (MessageQueue empty)
│   │   └── All responses received (or global timeout)
│   │
│   └── Process dead letter queue for final counts
│
├── Generate performance report
└── Return MainPhaseResult to Main
```

#### Data Flow
```
Message Generation → MessageQueue → MessageConsumers → WebSocketConnectionPool → Server
                                      ↓
Server Responses → ResponseQueue → ResponseWorkers → Message matching
                                      ↓
Timeout Messages → DeadLetterQueue → RetryWorkers → Retry attempts
```

### Client-Part2 Flow
*(Similar to client-part1 but with different implementation details)*

## Configuration Constants

### Server Constants (`server/src/main/java/cs6650/chatflow/server/commons/Constants.java`)
- **Endpoints**: `/health`, `/chat/{roomId}`
- **Validation**: Username regex, message length limits, user ID ranges
- **Heartbeat**: 30-second ping intervals

### Client Constants (`client-part1/src/main/java/cs6650/chatflow/client/commons/Constants.java`)
- **Warmup Phase**: 32 threads × 1000 messages/thread = 32,000 total warmup messages
- **Main Phase**: 500,000 total messages, 20 WebSocket connections
- **Threading**: 8 consumer threads, 8 response workers, 4 retry workers
- **Queue Capacities**: Message queue (5,000), response queue (2,000), dead letter (1,000)
- **Timeouts**: Message timeout (60s), global timeout (3min)
- **Retry Logic**: 5 attempts with exponential backoff (100ms-2000ms)

## Performance Characteristics

- **High Throughput**: Multi-threaded producer-consumer architecture
- **Reliability**: Retry logic, connection pooling, timeout handling
- **Scalability**: Configurable thread pools and queue sizes
- **Monitoring**: Comprehensive logging and performance metrics

## Troubleshooting

### Common Issues
- **Connection Refused**: Check server is running and ports are open
- **Timeout Errors**: Increase timeout constants or check network latency
- **Memory Issues**: Adjust JVM heap size with `-Xmx` parameter
- **Thread Exhaustion**: Reduce thread pool sizes in Constants.java

### Logs
- Server logs: Tomcat `logs/catalina.out`
- Client logs: Console output with detailed timing information

## Architecture Diagrams

See `results/` directory for performance charts and architecture diagrams generated during testing.
