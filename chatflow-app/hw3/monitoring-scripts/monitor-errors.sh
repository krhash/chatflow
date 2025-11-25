#!/bin/bash

# ==========================================
# Error Monitor
# Watches for errors in real-time
# ==========================================

echo "Monitoring errors in Tomcat logs..."
echo "Press Ctrl+C to stop"
echo ""

# Track error counts
LAST_ERROR_COUNT=0
LAST_REJECT_COUNT=0
LAST_THROTTLE_COUNT=0

while true; do
    sleep 10

    # Count different error types
    ERROR_COUNT=$(sudo grep -c "ERROR" /opt/tomcat9/logs/catalina.out)
    REJECT_COUNT=$(sudo grep -c "DATABASE WRITE QUEUE FULL" /opt/tomcat9/logs/catalina.out)
    THROTTLE_COUNT=$(sudo grep -c "ThrottlingException\|ProvisionedThroughputExceeded" /opt/tomcat9/logs/catalina.out)
    QUEUE_FULL=$(sudo grep -c "Message queue full" /opt/tomcat9/logs/catalina.out)

    # Calculate deltas
    NEW_ERRORS=$((ERROR_COUNT - LAST_ERROR_COUNT))
    NEW_REJECTS=$((REJECT_COUNT - LAST_REJECT_COUNT))
    NEW_THROTTLES=$((THROTTLE_COUNT - LAST_THROTTLE_COUNT))

    # Display
    echo "[$(date '+%H:%M:%S')] Errors: $ERROR_COUNT (+$NEW_ERRORS) | Rejections: $REJECT_COUNT (+$NEW_REJECTS) | Throttles: $THROTTLE_COUNT (+$NEW_THROTTLES) | Queue Full: $QUEUE_FULL"

    # Alert on new errors
    if [ $NEW_ERRORS -gt 10 ]; then
        echo "  ⚠️  HIGH ERROR RATE! $NEW_ERRORS new errors in last 10s"
    fi

    if [ $NEW_THROTTLES -gt 0 ]; then
        echo "  ❌ DYNAMODB THROTTLING! $NEW_THROTTLES throttle events"
    fi

    # Update counters
    LAST_ERROR_COUNT=$ERROR_COUNT
    LAST_REJECT_COUNT=$REJECT_COUNT
    LAST_THROTTLE_COUNT=$THROTTLE_COUNT
done
