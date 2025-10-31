import requests
import time
import csv
import datetime
import matplotlib.pyplot as plt

# ==============================
# CONFIGURATION
# ==============================
RABBITMQ_HOST = "13.218.99.253"        # Public IP or DNS of your RabbitMQ EC2
RABBITMQ_PORT = 15672
USERNAME = "chatflow"
PASSWORD = "admin"
INTERVAL = 5                           # seconds between polls
DURATION = 60                          # total duration (seconds)
OUTPUT_FILE = "rabbitmq_metrics.csv"

# ==============================
# COLLECT METRICS
# ==============================
def get_queue_metrics():
    url = f"http://{RABBITMQ_HOST}:{RABBITMQ_PORT}/api/queues"
    response = requests.get(url, auth=(USERNAME, PASSWORD))
    response.raise_for_status()
    data = response.json()

    metrics = []
    timestamp = datetime.datetime.now().isoformat()

    for q in data:
        metrics.append({
            "timestamp": timestamp,
            "queue": q["name"],
            "messages_ready": q.get("messages_ready", 0),
            "messages_unacknowledged": q.get("messages_unacknowledged", 0),
            "publish_rate": q.get("message_stats", {}).get("publish_details", {}).get("rate", 0),
            "deliver_rate": q.get("message_stats", {}).get("deliver_get_details", {}).get("rate", 0),
        })
    return metrics


def save_to_csv(all_data):
    fieldnames = ["timestamp", "queue", "messages_ready", "messages_unacknowledged", "publish_rate", "deliver_rate"]
    with open(OUTPUT_FILE, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in all_data:
            writer.writerow(row)
    print(f"✅ Metrics saved to {OUTPUT_FILE}")


def plot_queue_metrics(data, queue_name):
    # Filter for one queue
    q_data = [d for d in data if d["queue"] == queue_name]
    if not q_data:
        print(f"No data for queue {queue_name}")
        return

    times = [datetime.datetime.fromisoformat(d["timestamp"]) for d in q_data]
    depths = [int(d["messages_ready"]) for d in q_data]
    pub_rate = [float(d["publish_rate"]) for d in q_data]
    del_rate = [float(d["deliver_rate"]) for d in q_data]

    plt.figure(figsize=(10, 6))
    plt.subplot(2, 1, 1)
    plt.plot(times, depths, label="Queue Depth", linewidth=2)
    plt.title(f"Queue Depth Over Time ({queue_name})")
    plt.xlabel("Time")
    plt.ylabel("Messages Ready")
    plt.grid(True)

    plt.subplot(2, 1, 2)
    plt.plot(times, pub_rate, label="Producer Rate", color="green")
    plt.plot(times, del_rate, label="Consumer Rate", color="red")
    plt.title(f"Message Rates ({queue_name})")
    plt.xlabel("Time")
    plt.ylabel("Messages/sec")
    plt.legend()
    plt.grid(True)

    plt.tight_layout()
    plt.show()


def main():
    print(f"📊 Collecting RabbitMQ metrics for {DURATION}s ...")
    start = time.time()
    all_data = []

    while (time.time() - start) < DURATION:
        batch = get_queue_metrics()
        all_data.extend(batch)
        print(f"Collected metrics for {len(batch)} queues at {datetime.datetime.now().strftime('%H:%M:%S')}")
        time.sleep(INTERVAL)

    save_to_csv(all_data)

    # Optional: plot one of the queues (e.g., room.1)
    plot_queue_metrics(all_data, queue_name="room.1")


if __name__ == "__main__":
    main()