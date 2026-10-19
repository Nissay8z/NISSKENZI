#!/usr/bin/env python3
"""Build a live-TV catalogue from a primary M3U and configured fallback sources."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


STREAM_SCHEMES = {"http", "https", "rtsp", "rtmp", "rtp", "udp"}
USER_AGENT = "NISSKENZI-catalogue-refresh/1.2"
GROUP_TITLE_RE = re.compile(r'group-title="([^"]*)"', re.IGNORECASE)
# Exclude clear VOD/replay group labels, but keep linear channels such as "Cinema TV".
VOD_GROUPS = {
    "vod",
    "video on demand",
    "movies on demand",
    "films on demand",
    "series on demand",
    "catchup",
    "catch-up",
    "replay",
    "replay tv",
    "time shift",
    "timeshift",
    "ppv",
    "pay per view",
    "film à la demande",
    "films à la demande",
    "vidéo à la demande",
}


@dataclass(frozen=True)
class Entry:
    metadata: tuple[str, ...]
    url: str


def read_sources(path: Path) -> list[str]:
    """Read HTTP(S) playlist URLs from a text file."""
    if not path.is_file():
        raise FileNotFoundError(f"Sources file not found: {path}")

    sources: list[str] = []
    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parsed = urlsplit(line)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            print(f"Skipping invalid source URL in {path.name}", file=sys.stderr)
            continue
        if line not in sources:
            sources.append(line)

    if not sources:
        raise ValueError(f"No valid HTTP(S) source URLs found in {path}")
    return sources


def download_source(url: str, timeout: int = 30) -> str:
    """Download a text playlist over HTTP(S)."""
    request = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(request, timeout=timeout) as response:
        data = response.read()
    return data.decode("utf-8-sig", errors="replace")


def parse_m3u(text: str) -> list[Entry]:
    """Parse an M3U playlist while retaining entry metadata/directives."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        raise ValueError("Source playlist is empty")

    header = lines[0].upper()
    if not (header == "#EXTM3U" or header.startswith("#EXTM3U ")):
        raise ValueError("Source does not start with a valid #EXTM3U header")

    entries: list[Entry] = []
    pending: list[str] = []

    for line in lines[1:]:
        if line.startswith("#"):
            if line.upper().startswith("#EXTINF:"):
                pending = [line]
            elif pending:
                pending.append(line)
            continue

        parsed = urlsplit(line)
        if parsed.scheme.lower() not in STREAM_SCHEMES or not parsed.netloc:
            continue

        if pending:
            metadata = tuple(pending)
        else:
            name = Path(parsed.path).name or parsed.netloc
            metadata = (f"#EXTINF:-1,{name}",)

        entries.append(Entry(metadata=metadata, url=line))
        pending = []

    if not entries:
        raise ValueError("No stream entries found in the source playlist")
    return entries


def is_live_entry(entry: Entry) -> bool:
    """Filter clear VOD/replay groups without rejecting linear movie channels."""
    groups: list[str] = []
    for line in entry.metadata:
        groups.extend(GROUP_TITLE_RE.findall(line))
    return not any(group.strip().casefold() in VOD_GROUPS for group in groups)


def filter_live_entries(entries: list[Entry]) -> tuple[list[Entry], int]:
    live = [entry for entry in entries if is_live_entry(entry)]
    return live, len(entries) - len(live)


def deduplicate_entries(entries: list[Entry]) -> list[Entry]:
    """Keep the first occurrence of each exact URL (primary source comes first)."""
    result: list[Entry] = []
    seen: set[str] = set()
    for entry in entries:
        if entry.url in seen:
            continue
        seen.add(entry.url)
        result.append(entry)
    return result


def render_playlist(entries: list[Entry], epg_urls: list[str] | None = None) -> str:
    """Render a playlist with optional XMLTV/EPG guide URLs in its header."""
    valid_epg_urls: list[str] = []
    for url in epg_urls or []:
        clean_url = url.strip()
        parsed = urlsplit(clean_url)
        if parsed.scheme == "https" and parsed.netloc and clean_url not in valid_epg_urls:
            valid_epg_urls.append(clean_url)

    header = "#EXTM3U"
    if valid_epg_urls:
        header += ' x-tvg-url="' + ",".join(valid_epg_urls) + '"'

    lines = [header]
    for entry in entries:
        lines.extend(entry.metadata)
        lines.append(entry.url)
    return "\n".join(lines) + "\n"


def build_catalogue(
    fallback_sources: list[str],
    timeout: int = 30,
    fetcher=download_source,
    epg_urls: list[str] | None = None,
    primary_url: str | None = None,
    live_only: bool = True,
) -> tuple[str, int, list[str], dict[str, object], str | None]:
    """Load a primary playlist first, then supplement it with configured fallbacks.

    The primary URL should be an authorized M3U export URL, provided out of band
    (for example as a GitHub Actions secret), never committed into this repository.
    """
    ordered_sources: list[tuple[str, str]] = []
    primary = (primary_url or "").strip()
    if primary:
        parsed_primary = urlsplit(primary)
        if parsed_primary.scheme not in {"http", "https"} or not parsed_primary.netloc:
            raise ValueError("Primary playlist URL must be a valid HTTP(S) URL")
        ordered_sources.append(("primary", primary))

    seen_sources = {primary} if primary else set()
    for index, source in enumerate(fallback_sources, start=1):
        if source in seen_sources:
            continue
        seen_sources.add(source)
        ordered_sources.append((f"fallback-{index}", source))

    all_entries: list[Entry] = []
    primary_entries: list[Entry] = []
    errors: list[str] = []
    source_report: list[dict[str, object]] = []
    vod_filtered_total = 0
    primary_loaded = False

    for label, url in ordered_sources:
        try:
            downloaded = fetcher(url, timeout)
            parsed_entries = parse_m3u(downloaded)
            filtered_count = 0
            if live_only:
                parsed_entries, filtered_count = filter_live_entries(parsed_entries)
            vod_filtered_total += filtered_count

            if label == "primary":
                primary_entries = parsed_entries
                primary_loaded = bool(parsed_entries)

            all_entries.extend(parsed_entries)
            source_report.append({
                "source": label,
                "role": "primary" if label == "primary" else "fallback",
                "status": "ok" if parsed_entries else "empty_after_filter",
                "entries": len(parsed_entries),
                "vod_entries_filtered": filtered_count,
                # Do not put source URLs or query tokens into the report.
            })
            if not parsed_entries:
                errors.append(f"{label}: no live-TV entries after filtering")
        except Exception as exc:
            error_kind = type(exc).__name__
            errors.append(f"{label}: {error_kind}")
            source_report.append({
                "source": label,
                "role": "primary" if label == "primary" else "fallback",
                "status": "error",
                "entries": 0,
                "error": error_kind,
            })

    unique_entries = deduplicate_entries(all_entries)
    if not unique_entries:
        detail = ", ".join(errors) if errors else "no configured sources"
        raise RuntimeError(f"No live-TV entries could be generated ({detail})")

    report: dict[str, object] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "primary_configured": bool(primary),
        "primary_loaded": primary_loaded,
        "fallback_sources_configured": len(fallback_sources),
        "live_tv_only": live_only,
        "vod_entries_filtered": vod_filtered_total,
        "total_entries": len(unique_entries),
        "sources": source_report,
        "warnings": errors,
    }
    primary_content = (
        render_playlist(deduplicate_entries(primary_entries), epg_urls)
        if primary_loaded
        else None
    )
    return render_playlist(unique_entries, epg_urls), len(unique_entries), errors, report, primary_content


def build_playlist(
    sources: list[str],
    timeout: int = 30,
    fetcher=download_source,
    epg_urls: list[str] | None = None,
    primary_url: str | None = None,
) -> tuple[str, int, list[str]]:
    """Compatibility wrapper used by the unit tests."""
    content, count, errors, _, _ = build_catalogue(
        sources,
        timeout=timeout,
        fetcher=fetcher,
        epg_urls=epg_urls,
        primary_url=primary_url,
    )
    return content, count, errors


def write_text_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(content, encoding="utf-8", newline="\n")
    temporary_path.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser(description="Refresh the NISSKENZI live-TV catalogue.")
    parser.add_argument("--sources", type=Path, default=Path("sources.txt"),
                        help="Fallback playlist URLs, one per line")
    parser.add_argument("--output", type=Path, default=Path("playlist.m3u"))
    parser.add_argument("--report", type=Path, default=None,
                        help="Optional JSON health/count report")
    parser.add_argument("--primary-output", type=Path, default=None,
                        help="Optional output containing only primary-source channels")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--epg-url", action="append", default=[],
                        help="HTTPS XMLTV/EPG guide URL; may be repeated")
    parser.add_argument("--primary-url", default=os.environ.get("NISSKENZI_PRIMARY_M3U_URL", ""),
                        help="Authorized primary M3U URL; preferably set NISSKENZI_PRIMARY_M3U_URL as a secret")
    parser.add_argument("--include-vod", action="store_true",
                        help="Disable filtering of obvious VOD/replay categories")
    args = parser.parse_args()

    try:
        fallback_sources = read_sources(args.sources)
        primary_url = (args.primary_url or "").strip() or None
        content, count, errors, report, primary_content = build_catalogue(
            fallback_sources,
            timeout=args.timeout,
            epg_urls=args.epg_url,
            primary_url=primary_url,
            live_only=not args.include_vod,
        )

        write_text_atomic(args.output, content)
        if args.primary_output and primary_content is not None:
            write_text_atomic(args.primary_output, primary_content)
        if args.report:
            write_text_atomic(
                args.report,
                json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            )

        if not primary_url:
            print("Primary source not configured; using fallback catalogues.")
        elif not report["primary_loaded"]:
            print("Primary source unavailable; fallback catalogues filled the output.")

        print(f"Generated {count} live-TV entries from {len(report['sources'])} source attempt(s): {args.output}")
        print(f"Primary loaded: {report['primary_loaded']}; VOD/replay entries filtered: {report['vod_entries_filtered']}")
        for error in errors:
            print(f"Warning: {error}", file=sys.stderr)
        return 0
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
