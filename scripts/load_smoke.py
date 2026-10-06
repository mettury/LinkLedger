#!/usr/bin/env python3
"""Small local diagnostic, not a capacity benchmark. Never follows destinations."""
import concurrent.futures
import json
import math
import statistics
import time
from demo import request

COUNT = 200
WORKERS = 10
status, _, link = request("POST", "/api/v1/urls", {"url": "https://example.com/load-smoke"})
assert status == 201, (status, link)
code = link["code"]


def hit(_):
    started = time.perf_counter()
    result = request("GET", "/" + code, authenticated=False)[0]
    return result, (time.perf_counter() - started) * 1000


start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
    results = list(pool.map(hit, range(COUNT)))
elapsed = time.perf_counter() - start
latencies = sorted(value for _, value in results)
analytics = request("GET", f"/api/v1/urls/{code}/analytics")[2]
report = {"requests": COUNT, "concurrency": WORKERS, "http302": sum(code == 302 for code, _ in results),
          "recordedRedirects": analytics["totalRedirects"], "elapsedSeconds": round(elapsed, 3),
          "requestsPerSecond": round(COUNT / elapsed, 1), "medianMs": round(statistics.median(latencies), 2),
          "p95Ms": round(latencies[math.ceil(COUNT * .95) - 1], 2), "maxMs": round(max(latencies), 2),
          "scope": "Single-machine local smoke only; not a production SLO or capacity claim."}
print(json.dumps(report, indent=2))
assert report["http302"] == COUNT and report["recordedRedirects"] == COUNT
