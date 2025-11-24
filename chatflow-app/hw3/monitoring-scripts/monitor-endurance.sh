#!/bin/bash

# ==========================================
# Endurance Test Monitoring Script
# Tracks system vitals during sustained load
# ==========================================

LOG_DIR="/home/ec2-user/endurance-logs"
mkdir -p $LOG_DIR

TEST_NAME="endurance-$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/${TEST_NAME}.log"
CSV_FILE="$LOG_DIR/${TEST_NAME}.csv"

echo "========================================="
echo "Endurance Test Monitor Started"
echo "Test: $TEST_NAME"
echo "Logging to: $LOG_FILE"
echo "========================================="

# CSV Header
echo "Timestamp,CPU_User,CPU_System,CPU_Idle,Mem_Used_MB,Mem_Free_MB,Mem_Available_MB,Swap_Used_MB,Pending_Writes,Total_Writes,Failed_Writes,Queue_Full_Count,Active_DB_Threads,Load_Avg_1m,Load_Avg_5m,Network_RX_MB,Network_TX_MB" > $CSV_FILE

# Initialize counters
ITERATION=0
START_TIME=$(date +%s)

# Monitoring loop
while true; do
    ITERATION=$((ITERATION + 1))
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    
    # ==========================================
    # CPU Metrics
    # ==========================================
    CPU_STATS=$(top -bn1 | grep "Cpu(s)" | sed "s/.*, *\([0-9.]*\)%* id.*/\1/" | awk '{print 100 - $1}')
    CPU_LINE=$(top -bn1 | grep "Cpu(s)")
    CPU_USER=$(echo $CPU_LINE | awk '{print $2}' | sed 's/%us,//')
    CPU_SYS=$(echo $CPU_LINE | awk '{print $4}' | sed 's/%sy,//')
    CPU_IDLE=$(echo $CPU_LINE | awk '{print $8}' | sed 's/%id,//')
    
    # ==========================================
    # Memory Metrics
    # ==========================================
    MEM_STATS=$(free -m | grep Mem)
    MEM_USED=$(echo $MEM_STATS | awk '{print $3}')
    MEM_FREE=$(echo $MEM_STATS | awk '{print $4}')
    MEM_AVAILABLE=$(echo $MEM_STATS | awk '{print $7}')
    
    SWAP_STATS=$(free -m | grep Swap)
    SWAP_USED=$(echo $SWAP_STATS | awk '{print $3}')
    
    # ==========================================
    # Load Average
    # ==========================================
    LOAD=$(uptime | awk -F'load average:' '{print $2}' | awk '{print $1,$2,$3}' | sed 's/,//g')
    LOAD_1M=$(echo $LOAD | awk '{print $1}')
    LOAD_5M=$(echo $LOAD | awk '{print $2}')
    LOAD_15M=$(echo $LOAD | awk '{print $3}')
    
    # ==========================================
    # Application Metrics (from API)
    # ==========================================
    METRICS=$(curl -s http://localhost:8080/consumer-server/api/metrics 2>/dev/null)
    
    if [ -n "$METRICS" ]; then
        PENDING_WRITES=$(echo $METRICS | jq -r '.databaseMetrics.pendingWrites // 0')
        TOTAL_WRITES=$(echo $METRICS | jq -r '.databaseMetrics.totalWrites // 0')
        FAILED_WRITES=$(echo $METRICS | jq -r '.databaseMetrics.failedWrites // 0')
        AVG_LATENCY=$(echo $METRICS | jq -r '.databaseMetrics.avgWriteLatencyMs // "0"')
    else
        PENDING_WRITES=0
        TOTAL_WRITES=0
        FAILED_WRITES=0
        AVG_LATENCY=0
    fi
    
    # ==========================================
    # Queue Full Count (from logs)
    # ==========================================
    QUEUE_FULL_COUNT=$(sudo grep -c "Message queue full" /opt/tomcat9/logs/catalina.out 2>/dev/null || echo 0)
    
    # ==========================================
    # Database Writer Threads
    # ==========================================
    DB_THREADS=$(ps -eLf | grep -c "DBWriter" || echo 0)
    
    # ==========================================
    # Network Stats
    # ==========================================
    NET_STATS=$(cat /proc/net/dev | grep eth0 || cat /proc/net/dev | grep ens)
    NET_RX_BYTES=$(echo $NET_STATS | awk '{print $2}')
    NET_TX_BYTES=$(echo $NET_STATS | awk '{print $10}')
    NET_RX_MB=$(echo "scale=2; $NET_RX_BYTES / 1024 / 1024" | bc)
    NET_TX_MB=$(echo "scale=2; $NET_TX_BYTES / 1024 / 1024" | bc)
    
    # ==========================================
    # Display Current Status
    # ==========================================
    clear
    echo "+-------------------------------------------------------------------+"
    echo "¦              ENDURANCE TEST MONITORING                            ¦"
    echo "+-------------------------------------------------------------------+"
    echo ""
    echo "Test: $TEST_NAME"
    echo "Elapsed Time: ${ELAPSED}s ($(($ELAPSED / 60))m $(($ELAPSED % 60))s)"
    echo "Iteration: $ITERATION"
    echo ""
    echo "+-----------------------------------------------------------------+"
    echo "¦ CPU & LOAD                                                      ¦"
    echo "+-----------------------------------------------------------------¦"
    echo "¦ CPU User:    ${CPU_USER}%                                       "
    echo "¦ CPU System:  ${CPU_SYS}%                                        "
    echo "¦ CPU Idle:    ${CPU_IDLE}%                                       "
    echo "¦ Load Avg:    ${LOAD_1M} (1m)  ${LOAD_5M} (5m)  ${LOAD_15M} (15m)"
    echo "+-----------------------------------------------------------------+"
    echo ""
    echo "+-----------------------------------------------------------------+"
    echo "¦ MEMORY                                                          ¦"
    echo "+-----------------------------------------------------------------¦"
    echo "¦ Used:        ${MEM_USED} MB                                     "
    echo "¦ Free:        ${MEM_FREE} MB                                     "
    echo "¦ Available:   ${MEM_AVAILABLE} MB                                "
    echo "¦ Swap Used:   ${SWAP_USED} MB                                    "
    echo "+-----------------------------------------------------------------+"
    echo ""
    echo "+-----------------------------------------------------------------+"
    echo "¦ APPLICATION METRICS                                             ¦"
    echo "+-----------------------------------------------------------------¦"
    echo "¦ Total Writes:     ${TOTAL_WRITES}                              "
    echo "¦ Failed Writes:    ${FAILED_WRITES}                             "
    echo "¦ Pending Writes:   ${PENDING_WRITES}                            "
    echo "¦ Avg Latency:      ${AVG_LATENCY} ms                            "
    echo "¦ Queue Full Count: ${QUEUE_FULL_COUNT}                          "
    echo "¦ DB Writer Threads: ${DB_THREADS}                               "
    echo "+-----------------------------------------------------------------+"
    echo ""
    echo "+-----------------------------------------------------------------+"
    echo "¦ NETWORK                                                         ¦"
    echo "+-----------------------------------------------------------------¦"
    echo "¦ RX Total:    ${NET_RX_MB} MB                                    "
    echo "¦ TX Total:    ${NET_TX_MB} MB                                    "
    echo "+-----------------------------------------------------------------+"
    echo ""
    
    # Calculate current throughput
    if [ $ELAPSED -gt 0 ] && [ $TOTAL_WRITES -gt 0 ]; then
        CURRENT_THROUGHPUT=$(echo "scale=2; $TOTAL_WRITES / $ELAPSED" | bc)
        echo "Current Throughput: ${CURRENT_THROUGHPUT} msg/sec"
    fi
    
    # ==========================================
    # Warning Checks
    # ==========================================
    echo ""
    echo "??  WARNINGS:"
    
    # Check memory
    if [ $MEM_AVAILABLE -lt 500 ]; then
        echo "  ? LOW MEMORY! Available: ${MEM_AVAILABLE}MB"
    fi
    
    # Check swap
    if [ $SWAP_USED -gt 100 ]; then
        echo "  ??  SWAP IN USE: ${SWAP_USED}MB (performance degradation)"
    fi
    
    # Check queue
    if [ $PENDING_WRITES -gt 40000 ]; then
        echo "  ??  HIGH PENDING WRITES: ${PENDING_WRITES} (queue filling up)"
    fi
    
    # Check failures
    if [ $FAILED_WRITES -gt 1000 ]; then
        echo "  ? HIGH FAILURE RATE: ${FAILED_WRITES} failed writes"
    fi
    
    # Check CPU
    CPU_USAGE=$(echo "100 - $CPU_IDLE" | bc)
    if (( $(echo "$CPU_USAGE > 90" | bc -l) )); then
        echo "  ??  HIGH CPU USAGE: ${CPU_USAGE}%"
    fi
    
    echo ""
    echo "Press Ctrl+C to stop monitoring"
    echo "Logs: $LOG_FILE | CSV: $CSV_FILE"
    
    # ==========================================
    # Log to File
    # ==========================================
    echo "[$TIMESTAMP] CPU: ${CPU_USER}% usr, ${CPU_SYS}% sys, ${CPU_IDLE}% idle | Mem: ${MEM_USED}MB used, ${MEM_AVAILABLE}MB avail | Writes: ${TOTAL_WRITES} (${FAILED_WRITES} failed, ${PENDING_WRITES} pending) | Latency: ${AVG_LATENCY}ms | Load: ${LOAD_1M}" >> $LOG_FILE
    
    # ==========================================
    # Log to CSV
    # ==========================================
    echo "$TIMESTAMP,$CPU_USER,$CPU_SYS,$CPU_IDLE,$MEM_USED,$MEM_FREE,$MEM_AVAILABLE,$SWAP_USED,$PENDING_WRITES,$TOTAL_WRITES,$FAILED_WRITES,$QUEUE_FULL_COUNT,$DB_THREADS,$LOAD_1M,$LOAD_5M,$NET_RX_MB,$NET_TX_MB" >> $CSV_FILE
    
    # Sleep for monitoring interval
    sleep 5
done
