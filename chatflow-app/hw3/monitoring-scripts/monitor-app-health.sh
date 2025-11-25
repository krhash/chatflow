#!/bin/bash

# ==========================================
# Application Health Monitor
# Tracks database and consumer metrics
# ==========================================

LOG_DIR="/home/ec2-user/endurance-logs"
mkdir -p $LOG_DIR

APP_LOG="$LOG_DIR/app-health-$(date +%Y%m%d-%H%M%S).log"
ALERT_LOG="$LOG_DIR/alerts-$(date +%Y%m%d-%H%M%S).log"

echo "Monitoring application health..."
echo "App log: $APP_LOG"
echo "Alert log: $ALERT_LOG"

# Alert thresholds
PENDING_THRESHOLD=40000
FAILED_THRESHOLD=1000
LATENCY_THRESHOLD=500

while true; do
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')

    # Get metrics from API
    METRICS=$(curl -s http://localhost:8080/consumer-server/api/metrics 2>/dev/null)
    HEALTH=$(curl -s http://localhost:8080/consumer-server/api/health 2>/dev/null)

    if [ -n "$METRICS" ]; then
        # Extract metrics
        PENDING=$(echo $METRICS | jq -r '.databaseMetrics.pendingWrites // 0')
        TOTAL=$(echo $METRICS | jq -r '.databaseMetrics.totalWrites // 0')
        FAILED=$(echo $METRICS | jq -r '.databaseMetrics.failedWrites // 0')
        AVG_LATENCY=$(echo $METRICS | jq -r '.databaseMetrics.avgWriteLatencyMs // "0"')
        P99_LATENCY=$(echo $METRICS | jq -r '.databaseMetrics.p99WriteLatencyMs // "0"')
        DB_STATUS=$(echo $HEALTH | jq -r '.database // "UNKNOWN"')
        CONSUMER_STATUS=$(echo $HEALTH | jq -r '.consumers // "UNKNOWN"')

        # Calculate success rate
        if [ $TOTAL -gt 0 ]; then
            SUCCESS_RATE=$(echo "scale=4; ($TOTAL - $FAILED) * 100 / $TOTAL" | bc)
        else
            SUCCESS_RATE=0
        fi

        # Log to file
        echo "[$TIMESTAMP] Total: $TOTAL | Failed: $FAILED | Pending: $PENDING | AvgLat: ${AVG_LATENCY}ms | P99: ${P99_LATENCY}ms | Success: ${SUCCESS_RATE}% | DB: $DB_STATUS | Consumers: $CONSUMER_STATUS" >> $APP_LOG

        # Check for alerts
        ALERT=false

        if [ $PENDING -gt $PENDING_THRESHOLD ]; then
            echo "[$TIMESTAMP] ALERT: High pending writes: $PENDING (threshold: $PENDING_THRESHOLD)" | tee -a $ALERT_LOG
            ALERT=true
        fi

        if [ $FAILED -gt $FAILED_THRESHOLD ]; then
            echo "[$TIMESTAMP] ALERT: High failure count: $FAILED (threshold: $FAILED_THRESHOLD)" | tee -a $ALERT_LOG
            ALERT=true
        fi

        AVG_LAT_INT=$(echo $AVG_LATENCY | awk '{print int($1)}')
        if [ $AVG_LAT_INT -gt $LATENCY_THRESHOLD ]; then
            echo "[$TIMESTAMP] ALERT: High latency: ${AVG_LATENCY}ms (threshold: ${LATENCY_THRESHOLD}ms)" | tee -a $ALERT_LOG
            ALERT=true
        fi

        if [ "$DB_STATUS" != "UP" ]; then
            echo "[$TIMESTAMP] CRITICAL: Database service down!" | tee -a $ALERT_LOG
            ALERT=true
        fi

        # Display current status
        if [ $ALERT = true ]; then
            echo -e "\n⚠️  ALERT TRIGGERED - Check $ALERT_LOG\n"
        fi

    else
        echo "[$TIMESTAMP] ERROR: Failed to fetch metrics from API" >> $APP_LOG
    fi

    sleep 5
done
