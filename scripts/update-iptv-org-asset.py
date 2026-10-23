#!/usr/bin/env python3
"""Refresh the IPTV-org M3U that is packaged inside the native Android APK.

VAVOO is deliberately excluded. Android uses VavooProvider for its live
catalogue and resolves a selected VAVOO stream only when the user plays it.
"""
from __future__ import annotations

import re
import urllib.error
import urllib.request
from pathlib import Path

SOURCE_URL = "https://iptv-org.github.io/iptv/index.country.m3u"
OUTPUT_PATH = Path("morocco-tv/app/src/main/assets/iptv_org_catalog.m3u")
MAX_BYTES = 40 * 1024 * 1024
TIMEOUT_SECONDS = 60
MIN_CHANNELS = 1_000
USER_AGENT = "NISSKENZI-Android-embedded-IPTV-org/1.0"


def attribute(extinf: str, key: str) -> str:
    pattern = re.compile(
        r"(?<![\w-])" + re.escape(key)
        + r"\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s,]+))",
        re.IGNORECASE,
    )
    match = pattern.search(extinf)
    if not match:
        return ""
    return next((v for v in match.groups() if v is not None), "").strip()


def title_separator(extinf: str) -> int:
    """Find the EXTINF title comma, not commas inside quoted attributes."""
    quote = None
    escaped = False
    for index, char in enumerate(extinf):
        if quote is not None:
            if escaped:
                escaped = False
            elif char == chr(92):
                escaped = True
            elif char == quote:
                quote = None
        elif char in (chr(34), chr(39)):
            quote = char
        elif char == ",":
            return index
    return -1


def set_attribute(extinf: str, key: str, value: str) -> str:
    if attribute(extinf, key):
        return extinf
    comma = title_separator(extinf)
    metadata = extinf if comma < 0 else extinf[:comma]
    title = "" if comma < 0 else extinf[comma:]
    safe_value = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'{metadata} {key}="{safe_value}"{title}'


def normalize_extinf(extinf: str) -> str:
    """Keep existing metadata and ensure at least one country field is present."""
    country = attribute(extinf, "tvg-country") or attribute(extinf, "country")
    group = attribute(extinf, "group-title")
    if not country and group:
        extinf = set_attribute(extinf, "tvg-country", group)
    elif country and not group:
        extinf = set_attribute(extinf, "group-title", country)
    return extinf


def download_source() -> str:
    request = urllib.request.Request(
        SOURCE_URL,
        headers={"User-Agent": USER_AGENT, "Accept": "application/vnd.apple.mpegurl,*/*"},
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        data = response.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise RuntimeError("La playlist IPTV-org dépasse 40 Mo.")
    text = data.decode("utf-8-sig", errors="replace")
    if "#EXTM3U" not in text[:2048]:
        raise RuntimeError("La réponse IPTV-org n'est pas une playlist M3U valide.")
    return text


def build_embedded_playlist(text: str) -> tuple[str, int]:
    lines = text.splitlines()
    header = next(
        (line.strip() for line in lines if line.strip().startswith("#EXTM3U")),
        "#EXTM3U",
    )
    output = [header]
    seen_urls: set[str] = set()
    block: list[str] = []
    count = 0

    def flush() -> None:
        nonlocal count, block
        if not block:
            return
        extinf_index = next(
            (i for i, line in enumerate(block) if line.lstrip().startswith("#EXTINF:")),
            -1,
        )
        url_index = next(
            (i for i, line in enumerate(block)
             if line.strip().lower().startswith(("http://", "https://"))),
            -1,
        )
        if extinf_index < 0 or url_index < 0:
            block = []
            return
        url = block[url_index].strip()
        if url in seen_urls:
            block = []
            return
        block[extinf_index] = normalize_extinf(block[extinf_index])
        # Retain stream options/metadata between EXTINF and the stream URL.
        output.extend(block[:url_index])
        output.append(url)
        seen_urls.add(url)
        count += 1
        block = []

    for raw in lines:
        line = raw.strip().lstrip("\ufeff")
        if not line or line.startswith("#EXTM3U"):
            continue
        if line.startswith("#EXTINF:"):
            flush()
            block = [line]
        elif block:
            if line.startswith(("http://", "https://")):
                block.append(line)
                flush()
            elif line.startswith("#"):
                block.append(line)
            else:
                block = []

    flush()
    if count < MIN_CHANNELS:
        raise RuntimeError(
            f"Playlist refusée : seulement {count} chaînes valides (minimum {MIN_CHANNELS})."
        )
    return "\n".join(output) + "\n", count


def main() -> None:
    print(f"Téléchargement du catalogue mondial IPTV-org : {SOURCE_URL}")
    playlist, count = build_embedded_playlist(download_source())
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    temp = OUTPUT_PATH.with_suffix(".m3u.tmp")
    temp.write_text(playlist, encoding="utf-8", newline="\n")
    temp.replace(OUTPUT_PATH)
    print(f"{count} chaînes validées dans {OUTPUT_PATH}; pays et groupe-title conservés.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, urllib.error.URLError, RuntimeError) as error:
        raise SystemExit(f"Échec de génération : {error}") from error
