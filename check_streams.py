#!/usr/bin/env python3
"""Sample-check HTTP(S) live-TV URLs in an M3U playlist.

This is a bounded diagnostic tool: it sends a small Range request to a limited
sample of URLs and never tries to extract or refresh protected stream tokens.
Full stream URLs and query strings are never written to the report.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from refresh_catalogue import Entry, parse_m3u

USER_AGENT = "NISSKENZI-stream-check/1.0"
GROUP_RE = re.compile(r'group-title="([^"]*)"', re.IGNORECASE)
TITLE_RE = re.compile(r"#EXTINF:[^,]*,(.*)$", re.IGNORECASE)


def entry_title(entry: Entry) -> str:
    for line in entry.metadata:
        match = TITLE_RE.search(line)
        if match:
            return match.group(1).strip()[:160] or "Untitled"
    return "Untitled"


def entry_group(entry: Entry) -> str:
    for line in entry.metadata:
        match = GROUP_RE.search(line)
        if match:
            return match.group(1).strip()[:100] or "Ungrouped"
    return "Ungrouped"


def select_sample(entries: list[Entry], limit: int = 80) -> list[Entry]:
    """Spread requests across groups before filling the remaining sample."""
    if limit < 1 or limit > 500:
        raise ValueError("limit must be between 1 and 500")

    http_entries = [
        entry for entry in entries
        if urlsplit(entry.url).scheme.lower() in {"http", "https"}
    ]
    sample: list[Entry] = []
    used_urls: set[str] = set()
    used_groups: set[str] = set()

    for entry in http_entries:
        group = entry_group(entry)
        if group not in used_groups:
            sample.append(entry)
            used_urls.add(entry.url)
            used_groups.add(group)
            if len(sample) >= limit:
                return sample

    for entry in http_entries:
        if entry.url in used_urls:
            continue
        sample.append(entry)
        used_urls.add(entry.url)
        if len(sample) >= limit:
            break

    return sample


def probe_entry(entry: Entry, timeout: float = 8.0, opener=urlopen) -> dict[str, object]:
    """Check basic HTTP reachability without downloading a full stream."""
    parsed = urlsplit(entry.url)
    result: dict[str, object] = {
        "channel": entry_title(entry),
        "group": entry_group(entry),
        "host": parsed.hostname or "unknown",
        "status": "error",
        "http_status": None,
        "latency_ms": None,
        "content_type": "",
        "looks_like_m3u": False,
        "error": None,
    }

    request = Request(
        entry.url,
        headers={
            "User-Agent": USER_AGENT,
            "Range": "bytes=0-1023",
            "Accept": "application/vnd.apple.mpegurl, application/x-mpegURL, */*",
        },
    )
    started = time.monotonic()
    try:
        with opener(request, timeout=timeout) as response:
            prefix = response.read(1024)
            status = getattr(response, "status", None) or response.getcode()
            result["http_status"] = int(status)
            result["latency_ms"] = round((time.monotonic() - started) * 1000)
            result["content_type"] = response.headers.get("Content-Type", "")[:120]
            result["looks_like_m3u"] = prefix.lstrip().startswith(b"#EXTM3U")
            result["status"] = "ok" if 200 <= int(status) < 400 else "unavailable"
    except HTTPError as exc:
        result["http_status"] = int(exc.code)
        result["latency_ms"] = round((time.monotonic() - started) * 1000)
        result["status"] = "unavailable"
        result["error"] = f"HTTP {exc.code}"
    except (URLError, TimeoutError, OSError, ValueError) as exc:
        result["latency_ms"] = round((time.monotonic() - started) * 1000)
        result["error"] = type(exc).__name__
    except Exception as exc:
        result["latency_ms"] = round((time.monotonic() - started) * 1000)
        result["error"] = type(exc).__name__

    return result


def check_playlist(
    playlist_path: Path,
    limit: int = 80,
    timeout: float = 8.0,
    workers: int = 8,
) -> dict[str, object]:
    if workers < 1 or workers > 16:
        raise ValueError("workers must be between 1 and 16")
    entries = parse_m3u(playlist_path.read_text(encoding="utf-8-sig"))
    sample = select_sample(entries, limit)
    unsupported = sum(
        1 for entry in entries
        if urlsplit(entry.url).scheme.lower() not in {"http", "https"}
    )

    results: list[dict[str, object]] = []
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(probe_entry, entry, timeout) for entry in sample]
        for future in as_completed(futures):
            results.append(future.result())

    results.sort(key=lambda row: (str(row["group"]).casefold(), str(row["channel"]).casefold()))
    ok_count = sum(1 for row in results if row["status"] == "ok")
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "playlist_file": playlist_path.name,
        "total_playlist_entries": len(entries),
        "sample_requested": limit,
        "sample_checked": len(results),
        "http_ok": ok_count,
        "unavailable_or_error": len(results) - ok_count,
        "non_http_entries_not_checked": unsupported,
        "note": "Reachability only; a successful HTTP response does not guarantee playable video or rights to redistribute it.",
        "results": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Check a small sample of M3U URLs.")
    parser.add_argument("playlist", type=Path)
    parser.add_argument("--limit", type=int, default=80, help="Number of URLs to sample (1-500)")
    parser.add_argument("--timeout", type=float, default=8.0)
    parser.add_argument("--workers", type=int, default=8, help="Concurrent requests (1-16)")
    parser.add_argument("--output", type=Path, default=Path("stream-health.json"))
    args = parser.parse_args()

    try:
        report = check_playlist(args.playlist, args.limit, args.timeout, args.workers)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(
            f"Checked {report['sample_checked']} of {report['total_playlist_entries']} "
            f"entries: {report['http_ok']} HTTP reachable, "
            f"{report['unavailable_or_error']} unavailable/error."
        )
        print(f"Report saved to {args.output}")
        return 0
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
