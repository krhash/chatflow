#!/bin/bash

# ==========================================
# Detailed Resource Monitor
# Logs every metric for analysis
# ==========================================

LOG_DIR="/home/ec2-user/endurance-logs"
mkdir -p $LOG_DIR

RESOURCE_LOG="$LOG_DIR/resources-$(date +%Y%m%d-%H%M%S).log"

echo "Monitoring system resources..."
echo "Log file: $RESOURCE_LOG"
echo "Press Ctrl+C to stop"

while true; do
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    
    {
        echo "========================================="
        echo "[$TIMESTAMP]"
        echo "========================================="
        
        # CPU and Load
        echo "--- CPU ---"
        mpstat 1 1 2>/dev/null || top -bn1 | grep "Cpu(s)"
        
        echo ""
        echo "--- Load Average ---"
        uptime
        
        # Memory
        echo ""
        echo "--- Memory ---"
        free -h
        
        # Disk I/O
        echo ""
        echo "--- Disk I/O ---"
        iostat -x 1 1 2>/dev/null || df -h /
        
        # Network
        echo ""
        echo "--- Network ---"
        netstat -i | grep -E "eth0|ens"
        
        # Java Process
        echo ""
        echo "--- Java Process ---"
        ps aux | grep java | grep tomcat | head -1
        
        # Thread Count
        echo ""
        echo "--- Thread Count ---"
        ps -eLf | grep java | wc -l
        
        # DB Writer Threads
        echo ""
        echo "--- DB Writer Threads ---"
        ps -eLf | grep DBWriter | wc -l
        
        # Open Files
        echo ""
        echo "--- Open File Descriptors ---"
        TOMCAT_PID=$(pgrep -f catalina)
        if [ -n "$TOMCAT_PID" ]; then
            ls /proc/$TOMCAT_PID/fd 2>/dev/null | wc -l
        fi
        
        # TCP Connections
        echo ""
        echo "--- TCP Connections ---"
        netstat -ant | grep -c ESTABLISHED
        
        echo ""
        
    } >> $RESOURCE_LOG
    
    sleep 10
done
