# CS6650 Lab 1 - AWS EC2 Setup with Tomcat

## Overview
This lab guides you through creating an EC2 instance on AWS running Amazon Linux 2023 and installing Apache Tomcat 9.

## Prerequisites
- AWS Academy Learner Lab access
- Chrome or Edge browser (Firefox may block pop-ups)
- Terminal/Command Prompt access
- Basic understanding of SSH and Linux commands

## Part 1: AWS Account Setup

### Step 1: Access AWS Academy
1. Check your email for AWS Academy invitation
2. If missing, email your section instructor at their Northeastern email
3. Sign into AWS Academy Learner Lab
4. Click "Modules" → "Launch AWS Academy Learner Lab"

### Step 2: Start Lab Environment
1. Click "Start Lab" button in upper menu
2. Wait for AWS dot to change from red to green (several minutes)
3. Click the green AWS dot to open AWS Console

**Verification:** You should see the AWS Management Console dashboard.

## Part 2: EC2 Instance Creation

### Step 3: Launch EC2 Instance
1. Navigate to EC2 service in AWS Console
2. Click "Launch Instance"
3. Configure instance:
    - **Name:** `cs6650-lab1-[your-name]`
    - **AMI:** Amazon Linux 2023 AMI (64-bit x86)
    - **Instance Type:** t2.micro (free tier)
    - **Region:** us-east-1

### Step 4: Create Key Pair
1. Under "Key pair (login)":
    - Click "Create new key pair"
    - **Name:** `cs6650-lab1-key`
    - **Type:** RSA
    - **Format:** .pem
2. Download the .pem file
3. Move to secure directory and set permissions:
   ```bash
   chmod 400 cs6650-lab1-key.pem
   ```

**Verification:** Key file should have 400 permissions (`-r--------`).

## Part 3: Security Group Configuration

### Step 5: Configure Security Group
1. Create new security group or edit default
2. **Name:** `cs6650-lab1-sg`
3. Add the following inbound rules:

| Type | Protocol | Port | Source | Description |
|------|----------|------|---------|-------------|
| SSH | TCP | 22 | My IP | SSH access from your location |
| HTTP | TCP | 80 | My IP | HTTP access |
| Custom TCP | TCP | 8080 | My IP | Tomcat access |

**Campus Network Rules (if on NEU campus):**
- Add these CIDR blocks for ports 80 and 8080:
    - `63.208.141.34/29`
    - `63.208.141.234/29`

**⚠️ Security Warning:** Never use `0.0.0.0/0` (anywhere) for any port.

### Step 6: Launch Instance
1. Review configuration
2. Click "Launch Instance"
3. Wait for instance state to show "Running"

**Verification:** Instance should show "Running" status with a public IP address.

## Part 4: SSH Connection

### Step 7: Connect to Instance
1. Get your instance's public IP from EC2 dashboard
2. Connect via SSH:
   ```bash
   ssh -i cs6650-lab1-key.pem ec2-user@[PUBLIC-IP]
   ```
3. Type "yes" when prompted about host authenticity

**Expected Output:**
```
[ec2-user@ip-xxx-xxx-xxx-xxx ~]$
```

**Troubleshooting:**
- Connection timeout: Check security group rules
- Permission denied: Verify key file permissions (400)
- Host key verification failed: Remove old entries from `~/.ssh/known_hosts`

## Part 5: System Preparation

### Step 8: Update System
```bash
sudo yum update -y
```

### Step 9: Install Java 11
```bash
sudo yum install -y java-11-amazon-corretto-headless
```

**Verification:**
```bash
java -version
# Should show: openjdk version "11.x.x"
```

## Part 6: Tomcat Installation

### Step 10: Download and Install Tomcat 9
```bash
# Navigate to /opt directory
cd /opt

# Download Tomcat 9.0.65
sudo wget https://archive.apache.org/dist/tomcat/tomcat-9/v9.0.65/bin/apache-tomcat-9.0.65.tar.gz

# Extract archive
sudo tar -xzf apache-tomcat-9.0.65.tar.gz

# Rename directory
sudo mv apache-tomcat-9.0.65 tomcat9

# Set ownership
sudo chown -R ec2-user:ec2-user /opt/tomcat9

# Make scripts executable
sudo chmod +x /opt/tomcat9/bin/*.sh
```

### Step 11: Configure Tomcat Users
```bash
# Backup original file
cp /opt/tomcat9/conf/tomcat-users.xml /opt/tomcat9/conf/tomcat-users.xml.backup

# Create new tomcat-users.xml
cat > /opt/tomcat9/conf/tomcat-users.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<tomcat-users xmlns="http://tomcat.apache.org/xml"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:schemaLocation="http://tomcat.apache.org/xml tomcat-users.xsd"
              version="1.0">
  <role rolename="manager-gui"/>
  <role rolename="manager-script"/>
  <role rolename="admin-gui"/>
  <user username="admin" password="admin123" roles="manager-gui,manager-script,admin-gui"/>
</tomcat-users>
EOF
```

### Step 12: Configure Manager App Access
```bash
# Allow remote access to manager app
sed -i 's/allow="127\\.\\d+\\.\\d+\\.\\d+|::1|0:0:0:0:0:0:0:1"/allow=".*"/g' /opt/tomcat9/webapps/manager/META-INF/context.xml
```

### Step 13: Start Tomcat
```bash
# Start Tomcat
/opt/tomcat9/bin/startup.sh

# Verify it's running
netstat -tlnp | grep 8080
```

**Expected Output:**
```
tcp6  0  0  :::8080  :::*  LISTEN  [PID]/java
```

## Part 7: Verification and Testing

### Step 14: Test Tomcat Access
1. Open web browser
2. Navigate to: `http://[YOUR-PUBLIC-IP]:8080`
3. You should see the Tomcat homepage

**Expected Result:** Apache Tomcat/9.0.65 welcome page.

### Step 15: Test Manager App
1. Click "Manager App" button on Tomcat homepage
2. Login with credentials:
    - **Username:** admin
    - **Password:** admin123
3. Verify you can access the deployment interface

**Expected Result:** Tomcat Web Application Manager page with deploy options.

## Troubleshooting

### Common Issues

**Cannot connect to Tomcat (timeout):**
- Check security group allows port 8080 from your IP
- Verify Tomcat is running: `netstat -tlnp | grep 8080`
- Check if your IP changed: `curl ifconfig.me`

**403 Forbidden on Manager App:**
- Fixed Manager App Remote Access

#### Step 1:
By default, Tomcat manager app blocks all remote connections for security.

Modify file: /opt/tomcat9/webapps/manager/META-INF/context.xml

Run Command
```
bash
sudo sed -i 's/allow="127\\.\\d+\\.\\d+\\.\\d+|::1|0:0:0:0:0:0:0:1"/allow=".*"/g' /opt/tomcat9/webapps/manager/META-INF/context.xml
```

What This Changed:
• **BEFORE**: allow="127\.1\.1\.1|::1|0:0:0:0:0:0:0:1" (localhost only)
• **AFTER**: allow=".*" (any IP address)

#### Step 2: Create Admin User

File: /opt/tomcat9/conf/tomcat-users.xml

Add:

```
<user username="admin" password="admin123" roles="manager-gui,manager-script,admin-gui"/>
```

#### Step 3: Restart Tomcat

Commands:
bash
```
/opt/tomcat9/bin/shutdown.sh
```
```
sleep 3
```
```
/opt/tomcat9/bin/startup.sh
```

**SSH Connection Issues:**
- Verify key file permissions: `ls -la *.pem`
- Check security group allows SSH from your IP
- Ensure using correct username: `ec2-user`

### Useful Commands
```bash
# Start Tomcat
/opt/tomcat9/bin/startup.sh

# Stop Tomcat
/opt/tomcat9/bin/shutdown.sh

# Check Tomcat logs
tail -f /opt/tomcat9/logs/catalina.out

# Check your public IP
curl ifconfig.me
```

