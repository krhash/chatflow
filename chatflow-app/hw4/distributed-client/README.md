# Distributed Chat Client

A high-performance distributed client for the ChatFlow messaging system that can generate and process up to 500,000 messages using concurrent threads and WebSocket connections.

## Overview

The Distributed Chat Client simulates a large-scale messaging workload by:

- **Generating 500,000 messages** consisting of JOIN (5%), TEXT (90%), and LEAVE (5%) messages
- **Using 100 concurrent sender threads** to send messages to the producer server
- **Maintaining connections to 20 chat rooms** with round-robin distribution
- **Tracking message delivery acknowledgments** to ensure reliable communication
- **Providing real-time metrics and throughput statistics**

The client connects to two separate servers:
- **Producer Server** (default: localhost:8080) - Sends chat messages
- **Consumer Server** (default: localhost:8081) - Receives broadcast messages and sends delivery acknowledgments

## System Requirements

- **Java**: JDK 11 or higher
- **Maven**: For dependency management and building
- **IntelliJ IDEA Ultimate** (recommended)
- **Operating System**: Windows, macOS, or Linux
- **Memory**: Minimum 4GB RAM (8GB recommended for full load)
- **Network**: Stable network connection to producer and consumer servers

## Prerequisites

Before running the distributed client, ensure the ChatFlow servers are running:

1. **ChatFlow Server** (producer server on port 8080)
2. **Consumer Server** (consumer server on port 8081)
3. **RabbitMQ** message broker (default configuration)

## IntelliJ IDEA Setup and Execution

### Step 1: Open Project in IntelliJ IDEA

1. Launch IntelliJ IDEA Ultimate
2. Select **File → Open**
3. Navigate to the `chatflow-app` project root directory
4. Select the `distributed-client` folder and click **OK**
5. IntelliJ should automatically detect this as a Maven project and import it

### Step 2: Ensure Dependencies Are Downloaded

1. In the Project Explorer, right-click on `pom.xml`
2. Select **Maven → Reload project** or click the reload button in the Maven tool window
3. Wait for Maven to download all dependencies (SLF4J, Gson, Java-WebSocket)

### Step 3: Build the Project

1. From the main menu: **Build → Build Project** (`Ctrl+F9`)
2. Or use the Maven tool window: **distributed-client → Lifecycle → compile**
3. Ensure the build completes successfully (no compilation errors)

### Step 4: Configure Run Configuration

#### Option A: Create a New Run Configuration

1. Go to **Run → Edit Configurations...**
2. Click the **+** button and select **Application**
3. Configure the following:
   - **Name**: `DistributedClient` (or any descriptive name)
   - **Main class**: `cs6650.chatflow.client.DistributedClient`
   - **Working directory**: Set to `$PROJECT_DIR$/../` (to run from project root)
   - **Use classpath of module**: `distributed-client`
4. For **Program arguments** (optional), enter server configurations:
   ```
   localhost 8080 localhost 8081
   ```
5. Click **Apply** then **OK**

#### Option B: Run from Main Class

1. In the Project Explorer, navigate to:
   ```
   distributed-client/src/main/java/cs6650/chatflow/client/DistributedClient.java
   ```
2. Right-click on `DistributedClient.java`
3. Select **Run 'DistributedClient.main()'** or **Debug 'DistributedClient.main()'**

### Step 5: Execute the Client

1. **Start the servers** (producer and consumer servers must be running first)
2. **Run the client** using either:
   - The green play button in the toolbar
   - **Run → Run 'DistributedClient'** (or your custom configuration name)
   - **Ctrl+Shift+F10** with DistributedClient.java file selected

## Command Line Arguments

The client accepts optional command line arguments for server configuration:

```
java -jar distributed-client.jar [producerHost] [producerPort] [consumerHost] [consumerPort]
```

**Default values:**
- Producer Host: `localhost`
- Producer Port: `8080`
- Consumer Host: `localhost`
- Consumer Port: `8081`

**Examples:**
```bash
# Use default configuration
java -jar distributed-client.jar

# Custom server hosts
java -jar distributed-client.jar chat-server.example.com 8080 consumer.example.com 8081
```

## Understanding the Output

### Real-time Progress Monitoring

During execution, you'll see progress updates every 5 seconds:

```
Progress: 150000/500000 sent (833.3 msg/sec, 30.0% complete) | ACK'd: 149500 | Pending ACKs: 500 | ACK rate: 825.3/sec | Failed: 0
```

### Key Metrics Explained

- **Messages Sent**: Total messages transmitted to producer server
- **ACK'd**: Messages that received delivery confirmation
- **Pending ACKs**: Messages awaiting acknowledgment
- **ACK Rate**: Acknowledgments received per second
- **Failed**: Connection or transmission failures

### Final Summary Report

At completion, you'll receive a detailed performance report including:

```
================================================================================
                        SIMPLE DISTRIBUTED CLIENT REPORT
================================================================================
Total Runtime: 450.2 seconds
Messages Sent: 500,000 (1,111.0 msg/sec)
Messages Received: 500,000 (1,111.0 msg/sec)
Messages ACK'd: 499,980 (1,111.0 ACK/sec)
Connection Failures: 20

CONFIGURATION:
  Total Target Messages: 500000
  Sender Threads: 100

EXECUTOR SERVICE INFO:
  Message Generator: Single thread
  Sender Executors: Fixed thread pool (100 threads)
  Receiver Executors: Fixed thread pool (100 threads)
  Monitor: Scheduled executor

MESSAGE DISTRIBUTION:
  JOIN: 5% (25,000 messages)
  TEXT: 90% (450,000 messages)
  LEAVE: 5% (25,000 messages)
```

## Architecture Components

### Thread Pools

- **1 Message Generator Thread**: Creates all 500,000 messages
- **100 Sender Threads**: Send messages via WebSocket to producer server
- **100 Receiver Threads**: Listen for messages from consumer server
- **1 Monitor Thread**: Provides real-time progress updates

### Connection Pools

- **Producer Pool**: 100 WebSocket connections to producer server (5 per room)
- **Consumer Pool**: 20 WebSocket connections to consumer server (1 per room)
- **ACK Pool**: 20 dedicated connections for delivery acknowledgments

### Message Flow

1. **Generation** → Message Generator creates JOIN/TEXT/LEAVE messages
2. **Sending** → Sender threads transmit to Producer Server via WebSocket
3. **Routing** → Producer Server publishes to RabbitMQ message broker
4. **Consumption** → Consumer Server receives from RabbitMQ and broadcasts
5. **Delivery** → Consumer Server sends messages via WebSocket to clients
6. **Acknowledgment** → Clients send DELIVERY_ACK confirming message receipt

## Troubleshooting

### Common Issues

1. **Server Connection Failed**
   - Ensure producer server is running on port 8080
   - Ensure consumer server is running on port 8081
   - Check firewall settings

2. **Build Errors in IntelliJ**
   - Run **File → Invalidate Caches / Restart**
   - Delete `target/` directory and rebuild
   - Check JDK version is 11+

3. **OutOfMemoryError**
   - Increase JVM heap size in run configuration: `-Xmx4g`
   - Reduce thread count if memory is limited

4. **Slow Performance**
   - Ensure servers are running on same machine or fast network
   - Check RabbitMQ configuration and performance
   - Monitor system resources (CPU, memory, network)

### Server Requirements

Both servers must be configured and running:
- **Producer Server**: Handles WebSocket connections from clients
- **Consumer Server**: Processes RabbitMQ messages and provides WebSocket endpoints
- **RabbitMQ**: Default configuration (localhost:5672)

## Configuration Constants

Key configuration values in `Constants.java`:

- **Total Messages**: 500,000 (exactly)
- **Thread Pool Size**: 100 concurrent threads
- **Rooms**: 20 chat rooms (room1 through room20)
- **Message Distribution**: 5% JOIN, 90% TEXT, 5% LEAVE
- **Queue Capacity**: 5,000 message buffer

## Performance Tuning

### For Maximum Throughput

1. **Use SSD storage** for better I/O performance
2. **Increase JVM memory**: `-Xmx8g -Xms4g`
3. **Run servers and client on separate machines** if possible
4. **Tune RabbitMQ** for high throughput (increase channel limits)

### For Resource-Constrained Environments

1. **Reduce thread count** by modifying `THREAD_POOL_SIZE` constant
2. **Lower total message count** for testing
3. **Run all components on single machine** for development

## Development Notes

- **Logging**: Uses SLF4J with detailed debug/info logging
- **Thread Safety**: All shared data structures are thread-safe
- **Error Handling**: Connection failures and message errors are tracked but don't stop execution
- **Metrics**: Comprehensive performance tracking and reporting

## Related Components

- **Server**: Main chat server component
- **Consumer Server**: Message consumption and WebSocket broadcasting
- **Client Part 1**: Simpler single-threaded client for comparison
- **Monitoring Scripts**: RabbitMQ monitoring tools
