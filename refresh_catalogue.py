#!/usr/bin/env python3
"""Merge configured M3U sources into a deduplicated playlist."""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


STREAM_SCHEMES = {"http", "https", "rtsp", "rtmp", "rtp", "udp"}
USER_AGENT = "NISSKENZI-catalogue-refresh/1.0"


@dataclass(frozen=True)
class Entry:
    metadata: tuple[str, ...]
    url: str


def read_sources(path: Path) -> list[str]:
    """Read HTTP(S) source playlist URLs from a text file."""
    if not path.is_file():
        raise FileNotFoundError(f"Sources file not found: {path}")

    sources: list[str] = []
    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parsed = urlsplit(line)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            print(f"Skipping invalid source URL: {line}", file=sys.stderr)
            continue
        if line not in sources:
            sources.append(line)

    if not sources:
        raise ValueError(f"No valid HTTP(S) source URLs found in {path}")
    return sources


def download_source(url: str, timeout: int = 30) -> str:
    """Download a text playlist from an HTTP(S) source."""
    request = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(request, timeout=timeout) as response:
        data = response.read()
    return data.decode("utf-8-sig", errors="replace")


def parse_m3u(text: str) -> list[Entry]:
    """Parse an M3U playlist, preserving metadata/directives for each URL."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or lines[0].upper() != "#EXTM3U":
        raise ValueError("Source does not start with the #EXTM3U header")

    entries: list[Entry] = []
    pending: list[str] = []

    for line in lines[1:]:
        if line.startswith("#"):
            if line.upper().startswith("#EXTINF:"):
                # Discard an incomplete previous entry if a new one starts.
                pending = [line]
            elif pending:
                # Preserve entry-specific directives such as EXTVLCOPT/KODIPROP.
                pending.append(line)
            continue

        parsed = urlsplit(line)
        if parsed.scheme.lower() not in STREAM_SCHEMES or not parsed.netloc:
            continue

        if pending:
            metadata = tuple(pending)
        else:
            # Some valid lists contain bare stream URLs without EXTINF metadata.
            name = Path(parsed.path).name or parsed.netloc
            metadata = (f"#EXTINF:-1,{name}",)

        entries.append(Entry(metadata=metadata, url=line))
        pending = []

    if not entries:
        raise ValueError("No stream entries found in the source playlist")
    return entries


def deduplicate_entries(entries: list[Entry]) -> list[Entry]:
    """Keep the first occurrence of each exact stream URL."""
    result: list[Entry] = []
    seen: set[str] = set()
    for entry in entries:
        if entry.url in seen:
            continue
        seen.add(entry.url)
        result.append(entry)
    return result


def render_playlist(entries: list[Entry]) -> str:
    lines = ["#EXTM3U"]
    for entry in entries:
        lines.extend(entry.metadata)
        lines.append(entry.url)
    return "\n".join(lines) + "\n"


def build_playlist(
    sources: list[str],
    timeout: int = 30,
    fetcher=download_source,
) -> tuple[str, int, list[str]]:
    """Fetch, parse, combine and deduplicate sources."""
    all_entries: list[Entry] = []
    errors: list[str] = []

    for source in sources:
        try:
            text = fetcher(source, timeout)
            all_entries.extend(parse_m3u(text))
        except Exception as exc:  # Report one failed source without losing other results.
            errors.append(f"{source}: {type(exc).__name__}: {exc}")

    unique_entries = deduplicate_entries(all_entries)
    if not unique_entries:
        detail = "; ".join(errors) if errors else "all sources were empty"
        raise RuntimeError(f"No playlist entries could be generated ({detail})")

    return render_playlist(unique_entries), len(unique_entries), errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Refresh the NISSKENZI media catalogue.")
    parser.add_argument("--sources", type=Path, default=Path("sources.txt"))
    parser.add_argument("--output", type=Path, default=Path("playlist.m3u"))
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args()

    try:
        sources = read_sources(args.sources)
        content, count, errors = build_playlist(sources, timeout=args.timeout)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary_path.write_text(content, encoding="utf-8", newline="\n")
        temporary_path.replace(args.output)

        print(f"Generated {count} unique entries from {len(sources)} source(s): {args.output}")
        for error in errors:
            print(f"Warning: {error}", file=sys.stderr)
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
