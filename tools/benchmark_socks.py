#!/usr/bin/env python3
"""Repeatable single-flow and parallel SOCKS5 download benchmark."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from dataclasses import asdict, dataclass


@dataclass
class FlowResult:
    http_code: int
    bytes_downloaded: int
    seconds: float
    bytes_per_second: float
    exit_code: int
    error: str


def curl_command(proxy: str, url: str, timeout: int) -> list[str]:
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
        "/dev/null",
        "--silent",
        "--show-error",
        "--write-out",
        "%{http_code} %{size_download} %{time_total} %{speed_download}",
        url,
    ]


def parse_result(process: subprocess.Popen[str], output: str, error: str) -> FlowResult:
    try:
        http_code, size, seconds, speed = output.strip().split()
        return FlowResult(
            http_code=int(http_code),
            bytes_downloaded=int(size),
            seconds=float(seconds),
            bytes_per_second=float(speed),
            exit_code=process.returncode,
            error=error.strip(),
        )
    except (TypeError, ValueError):
        return FlowResult(0, 0, 0.0, 0.0, process.returncode or 1, error.strip())


def run_flow(proxy: str, url: str, timeout: int) -> FlowResult:
    process = subprocess.Popen(
        curl_command(proxy, url, timeout),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    output, error = process.communicate()
    return parse_result(process, output, error)


def run_parallel(proxy: str, url: str, timeout: int, flows: int) -> tuple[list[FlowResult], float]:
    processes = [
        subprocess.Popen(
            curl_command(proxy, url, timeout),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        for _ in range(flows)
    ]
    started = time.monotonic()
    results = []
    for process in processes:
        output, error = process.communicate()
        results.append(parse_result(process, output, error))
    return results, time.monotonic() - started


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--proxy", required=True, help="SOCKS5 endpoint, for example HOST:1080")
    parser.add_argument("--url", required=True, help="Fixed-size HTTPS download URL")
    parser.add_argument("--parallel", type=int, default=4)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--label", default="benchmark")
    args = parser.parse_args()

    single = run_flow(args.proxy, args.url, args.timeout)
    parallel, elapsed = run_parallel(
        args.proxy,
        args.url,
        args.timeout,
        args.parallel,
    )
    total_bytes = sum(flow.bytes_downloaded for flow in parallel)
    report = {
        "label": args.label,
        "proxy": args.proxy,
        "single": asdict(single),
        "parallel": {
            "flows": [asdict(flow) for flow in parallel],
            "elapsed_seconds": elapsed,
            "aggregate_mbps": (total_bytes * 8 / elapsed / 1_000_000) if elapsed > 0 else 0,
        },
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if single.exit_code == 0 and all(flow.exit_code == 0 for flow in parallel) else 1


if __name__ == "__main__":
    raise SystemExit(main())
