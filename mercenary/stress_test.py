import concurrent.futures
import requests
import time
import sys

URL = "http://localhost:8080/api/ask"

def send_request(i):
    try:
        start = time.time()
        # "ping" avoids the expensive AI calls
        resp = requests.get(URL, params={"q": "ping", "dept": "general"}, timeout=5)
        duration = time.time() - start
        return resp.status_code, duration
    except Exception as e:
        return str(e), 0

def stress_test(total_requests, concurrency):
    print(f"Starting stress test: {total_requests} requests with concurrency {concurrency}")
    
    start_time = time.time()
    success = 0
    errors = 0
    times = []
    
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(send_request, i) for i in range(total_requests)]
        for future in concurrent.futures.as_completed(futures):
            status, duration = future.result()
            if status == 200:
                success += 1
                times.append(duration)
            else:
                errors += 1
                if errors < 5:
                    print(f"Error: {status}")
                
    total_time = time.time() - start_time
    print(f"\nCompleted in {total_time:.2f} seconds")
    if total_time > 0:
        print(f"Requests per second: {total_requests/total_time:.2f}")
    if times:
        print(f"Avg Latency: {sum(times)/len(times)*1000:.2f} ms")
        print(f"Max Latency: {max(times)*1000:.2f} ms")
    print(f"Success: {success}")
    print(f"Errors: {errors}")

if __name__ == "__main__":
    count = 100
    if len(sys.argv) > 1:
        count = int(sys.argv[1])
    
    concurrency = 10
    if len(sys.argv) > 2:
        concurrency = int(sys.argv[2])
        
    stress_test(count, concurrency)
