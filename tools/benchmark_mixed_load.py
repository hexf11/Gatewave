#!/usr/bin/env python3
"""Measure small-request latency with and without concurrent bulk SOCKS5 traffic."""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class RequestResult:
    http_code: int
    bytes_downloaded: int
    seconds: float
    exit_code: int
    error: str


def curl_command(proxy: str, url: str, timeout: int, output: str) -> list[str]:
    return [
        "curl",
        "--socks5-hostname",
        proxy,
        "--connect-timeout",
        "15",
        "--max-time",
        str(timeout),
        "--location",
        "--output",
        output,
        "--silent",
        "--show-error",
        "--write-out",
        "%{http_code} %{size_download} %{time_total}",
        url,
    ]


def one_request(proxy: str, url: str, timeout: int) -> RequestResult:
    completed = subprocess.run(
        curl_command(proxy, url, timeout, os.devnull),
        capture_output=True,
        text=True,
        check=False,
    )
    try:
        http_code, size, seconds = completed.stdout.strip().split()
        return RequestResult(
            http_code=int(http_code),
            bytes_downloaded=int(size),
            seconds=float(seconds),
            exit_code=completed.returncode,
            error=completed.stderr.strip(),
        )
    except ValueError:
        return RequestResult(0, 0, 0.0, completed.returncode or 1, completed.stderr.strip())


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(0, math.ceil(len(ordered) * fraction) - 1)
    return ordered[rank]


def run_small_requests(
    proxy: str,
    url: str,
    timeout: int,
    requests: int,
    concurrency: int,
) -> dict[str, object]:
    started = time.monotonic()
    results: list[RequestResult] = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(one_request, proxy, url, timeout) for _ in range(requests)]
        for future in as_completed(futures):
            results.append(future.result())
    elapsed = time.monotonic() - started
    successful = [result for result in results if result.exit_code == 0 and result.http_code == 200]
    latencies = [result.seconds for result in successful]
    total_bytes = sum(result.bytes_downloaded for result in successful)
    return {
        "requests": requests,
        "concurrency": concurrency,
        "successful": len(successful),
        "failed": requests - len(successful),
        "elapsed_seconds": elapsed,
        "p50_seconds": percentile(latencies, 0.50),
        "p95_seconds": percentile(latencies, 0.95),
        "maximum_seconds": max(latencies, default=0.0),
        "aggregate_mbps": total_bytes * 8 / elapsed / 1_000_000 if elapsed else 0.0,
        "results": [asdict(result) for result in results],
    }


def run_mixed_phase(args: argparse.Namespace, work: Path) -> dict[str, object]:
    bulk_processes: list[subprocess.Popen[str]] = []
    bulk_paths: list[Path] = []
    for index in range(args.bulk_flows):
        output = work / f"bulk-{index}.bin"
        bulk_paths.append(output)
        bulk_processes.append(
            subprocess.Popen(
                curl_command(args.proxy, args.bulk_url, args.timeout, str(output)),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            ),
        )

    time.sleep(args.warmup_seconds)
    alive_at_start = sum(process.poll() is None for process in bulk_processes)
    bytes_at_start = sum(path.stat().st_size if path.exists() else 0 for path in bulk_paths)
    phase_started = time.monotonic()
    small = run_small_requests(
        args.proxy,
        args.small_url,
        args.timeout,
        args.small_requests,
        args.small_concurrency,
    )
    phase_elapsed = time.monotonic() - phase_started
    bytes_at_end = sum(path.stat().st_size if path.exists() else 0 for path in bulk_paths)
    alive_at_end = sum(process.poll() is None for process in bulk_processes)

    process_results = []
    for process in bulk_processes:
        if process.poll() is None:
            process.terminate()
        try:
            output, error = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            output, error = process.communicate()
        process_results.append(
            {
                "exit_code": process.returncode,
                "stdout": output.strip(),
                "error": error.strip(),
            },
        )

    bulk_bytes = max(0, bytes_at_end - bytes_at_start)
    return {
        "small_requests": small,
        "bulk": {
            "flows": args.bulk_flows,
            "alive_at_start": alive_at_start,
            "alive_at_end": alive_at_end,
            "bytes_during_small_phase": bulk_bytes,
            "mbps_during_small_phase": (
                bulk_bytes * 8 / phase_elapsed / 1_000_000 if phase_elapsed else 0.0
            ),
            "processes": process_results,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--proxy", required=True, help="SOCKS5 endpoint, for example HOST:1080")
    parser.add_argument("--small-url", required=True)
    parser.add_argument("--bulk-url", required=True)
    parser.add_argument("--small-requests", type=int, default=30)
    parser.add_argument("--small-concurrency", type=int, default=10)
    parser.add_argument("--bulk-flows", type=int, default=2)
    parser.add_argument("--warmup-seconds", type=float, default=2.0)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--label", default="mixed-load")
    args = parser.parse_args()

    small_only = run_small_requests(
        args.proxy,
        args.small_url,
        args.timeout,
        args.small_requests,
        args.small_concurrency,
    )
    with tempfile.TemporaryDirectory(prefix="gatewave-mixed-") as directory:
        mixed = run_mixed_phase(args, Path(directory))

    baseline_p95 = float(small_only["p95_seconds"])
    mixed_p95 = float(mixed["small_requests"]["p95_seconds"])
    report = {
        "label": args.label,
        "proxy": args.proxy,
        "small_only": small_only,
        "mixed": mixed,
        "p95_slowdown_ratio": mixed_p95 / baseline_p95 if baseline_p95 else 0.0,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    all_small_succeeded = (
        small_only["failed"] == 0 and mixed["small_requests"]["failed"] == 0
    )
    bulk_was_active = mixed["bulk"]["alive_at_start"] == args.bulk_flows
    return 0 if all_small_succeeded and bulk_was_active else 1


if __name__ == "__main__":
    raise SystemExit(main())
