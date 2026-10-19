#!/usr/bin/env python3
"""Build a catalogue of Internet Archive movies with an explicitly stated
Creative Commons/public-domain license and a directly playable MP4 file.

No third-party streaming-site scraping, stream-link extraction, or access-control
bypass is performed. Existing catalogue data is preserved if the upstream API
fails or returns too few validated items.
"""
from __future__ import annotations

import html
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

BASE_URL = "https://archive.org"
SEARCH_URL = f"{BASE_URL}/advancedsearch.php"
METADATA_URL = f"{BASE_URL}/metadata/"
USER_AGENT = "NISSKENZI-Cine-Library/1.0 (+https://github.com/Nissay8z/NISSKENZI)"
MAX_RESULTS_PER_SEARCH = 250
MAX_METADATA_LOOKUPS = 220
TARGET_ITEMS = 72
MIN_ITEMS_TO_PUBLISH = 8
REQUEST_TIMEOUT = 25
MAX_ATTEMPTS_PER_REQUEST = 3
SEARCH_FIELDS = [
    "identifier", "title", "creator", "date", "year", "description",
    "subject", "licenseurl", "downloads", "language", "mediatype", "collection", "publicdate", "addeddate",
]
# Restrict the public site to these explicit license families. The uploader's
# license metadata is not a legal guarantee; the UI links to the item record.
LICENSE_QUERY = "licenseurl:http*creativecommons.org*"
ALLOWED_LICENSE_RE = re.compile(
    r"^https?://creativecommons\.org/(?:"
    r"publicdomain/(?:mark|zero)/1\.0|"
    r"licenses/(?:by|by-sa)/(?:1\.0|2\.0|2\.5|3\.0|4\.0)"
    r")/?$",
    re.IGNORECASE,
)
HTML_TAG_RE = re.compile(r"<[^>]+>")
SPACE_RE = re.compile(r"\s+")
SKIP_TITLE_RE = re.compile(
    r"\b(?:nudist|naked|grindhouse|publicvideos|batch|compilation|double feature)\b",
    re.IGNORECASE,
)


def request_json(url: str) -> dict:
    """GET JSON with small bounded retries."""
    last_error: Exception | None = None
    for attempt in range(MAX_ATTEMPTS_PER_REQUEST):
        request = Request(
            url,
            headers={
                "User-Agent": USER_AGENT,
                "Accept": "application/json",
            },
        )
        try:
            with urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                payload = response.read(20 * 1024 * 1024 + 1)
            if len(payload) > 20 * 1024 * 1024:
                raise ValueError("Upstream JSON response exceeded 20 MiB.")
            parsed = json.loads(payload.decode("utf-8", errors="replace"))
            if not isinstance(parsed, dict):
                raise ValueError("Upstream response was not a JSON object.")
            return parsed
        except (HTTPError, URLError, TimeoutError, OSError, json.JSONDecodeError, ValueError) as exc:
            last_error = exc
            if attempt + 1 < MAX_ATTEMPTS_PER_REQUEST:
                time.sleep(1.0 * (attempt + 1))
    raise RuntimeError(f"Internet Archive API request failed ({type(last_error).__name__}).")


def as_text(value: object) -> str:
    """Flatten API fields and remove markup before putting them into JSON."""
    if isinstance(value, list):
        value = " · ".join(str(part) for part in value if part)
    if not isinstance(value, (str, int, float)):
        return ""
    text = html.unescape(str(value))
    text = HTML_TAG_RE.sub(" ", text)
    return SPACE_RE.sub(" ", text).strip()


def as_int(value: object) -> int:
    try:
        return max(0, int(str(value).strip()))
    except (ValueError, TypeError):
        return 0


def license_label(license_url: str) -> str | None:
    normalized = license_url.strip().rstrip("/")
    if not ALLOWED_LICENSE_RE.fullmatch(normalized):
        return None
    lower = normalized.lower()
    if "/publicdomain/mark/" in lower:
        return "Marque du domaine public"
    if "/publicdomain/zero/" in lower:
        return "CC0 — domaine public"
    if "/licenses/by-sa/" in lower:
        return "Creative Commons BY-SA"
    if "/licenses/by/" in lower:
        return "Creative Commons BY"
    return None


def normalize_subjects(value: object) -> list[str]:
    if isinstance(value, list):
        raw = value
    elif isinstance(value, str):
        raw = value.split(";")
    else:
        raw = []
    result: list[str] = []
    seen: set[str] = set()
    for item in raw:
        cleaned = as_text(item)
        if not cleaned:
            continue
        for part in re.split(r"\s*;\s*", cleaned):
            part = part.strip()
            key = part.casefold()
            if part and key not in seen:
                seen.add(key)
                result.append(part[:50])
            if len(result) >= 8:
                return result
    return result


def is_suitable_title(title: str) -> bool:
    """Exclude obvious adult-themed titles and generic video batches."""
    return not SKIP_TITLE_RE.search(title)


def choose_mp4(files: object) -> dict | None:
    if not isinstance(files, list):
        return None
    candidates = []
    for file_info in files:
        if not isinstance(file_info, dict):
            continue
        name = str(file_info.get("name") or "")
        low_name = name.lower()
        fmt = as_text(file_info.get("format")).casefold()
        private = str(file_info.get("private", "")).casefold()
        if private in {"1", "true", "yes"}:
            continue
        if not low_name.endswith(".mp4") and fmt not in {
            "mpeg4", "mpeg-4", "mpeg4 (h.264)", "mpeg4 (h264)",
        }:
            continue
        if any(mark in low_name for mark in ("_sample.", "_preview.", "_thumb.", "thumbnail")):
            continue
        size = as_int(file_info.get("size"))
        if size < 2_000_000:
            continue
        if size > 8_000_000_000:
            continue
        candidates.append((size, name, file_info))
    if not candidates:
        return None
    # Prefer a large/original MP4 to avoid selecting tiny previews.
    return max(candidates, key=lambda entry: entry[0])[2]


def get_search_docs(collection_query: str, sort_field: str) -> list[dict]:
    # Search only actual feature-film/curated film collections, not generic video
    # batches. License is checked both here and against the item metadata later.
    params: list[tuple[str, str]] = [
        ("q", f"{collection_query} AND mediatype:movies AND {LICENSE_QUERY}"),
        ("rows", str(MAX_RESULTS_PER_SEARCH)),
        ("page", "1"),
        ("output", "json"),
        ("sort[]", sort_field),
    ]
    for field in SEARCH_FIELDS:
        params.append(("fl[]", field))
    payload = request_json(f"{SEARCH_URL}?{urlencode(params)}")
    response = payload.get("response") or {}
    docs = response.get("docs") or []
    if not isinstance(docs, list):
        return []
    filtered = [
        doc for doc in docs
        if isinstance(doc, dict) and license_label(as_text(doc.get("licenseurl"))) is not None
    ]
    print(
        f"Archive.org search ({collection_query}, {sort_field}): "
        f"{len(docs)} results, {len(filtered)} with an accepted license."
    )
    return filtered


def interleave_docs(groups: list[list[dict]]) -> list[dict]:
    """Round-robin lists so recent items and popular items both appear."""
    output: list[dict] = []
    seen: set[str] = set()
    max_length = max((len(group) for group in groups), default=0)
    for index in range(max_length):
        for group in groups:
            if index >= len(group):
                continue
            doc = group[index]
            identifier = str(doc.get("identifier") or "").strip()
            if not identifier or identifier in seen:
                continue
            if not re.fullmatch(r"[A-Za-z0-9._-]{1,200}", identifier):
                continue
            seen.add(identifier)
            output.append(doc)
    return output


def enrich_item(doc: dict) -> dict | None:
    identifier = str(doc.get("identifier") or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,200}", identifier):
        return None

    info = request_json(f"{METADATA_URL}{quote(identifier, safe='')}")
    metadata = info.get("metadata")
    if not isinstance(metadata, dict):
        return None
    if as_text(metadata.get("mediatype") or doc.get("mediatype")).casefold() != "movies":
        return None

    lic_raw = metadata.get("licenseurl") or doc.get("licenseurl") or ""
    license_url = as_text(lic_raw)
    label = license_label(license_url)
    if not label:
        return None

    video = choose_mp4(info.get("files"))
    if not video:
        return None
    file_name = str(video.get("name") or "")
    # The archive.org download link addresses the original file directly.
    stream_url = f"{BASE_URL}/download/{quote(identifier, safe='')}/{quote(file_name, safe='/')}"
    title = as_text(metadata.get("title") or doc.get("title"))
    if not title or not is_suitable_title(title):
        return None

    description = as_text(metadata.get("description") or doc.get("description"))
    if len(description) > 550:
        description = description[:547].rsplit(" ", 1)[0] + "…"
    year_raw = as_text(metadata.get("year") or metadata.get("date") or doc.get("year") or doc.get("date"))
    match = re.search(r"\b(18\d{2}|19\d{2}|20\d{2})\b", year_raw)
    year = int(match.group(1)) if match else None
    creator = as_text(metadata.get("creator") or doc.get("creator"))
    subjects = normalize_subjects(metadata.get("subject") or doc.get("subject"))
    downloads = as_int(metadata.get("downloads") or doc.get("downloads"))
    added_date = as_text(metadata.get("publicdate") or metadata.get("addeddate") or doc.get("publicdate") or doc.get("addeddate"))
    collections = metadata.get("collection") or doc.get("collection") or []
    if isinstance(collections, str):
        collections = [collections]
    collections = [as_text(value) for value in collections if as_text(value)]
    # Guard against broad/incorrect index matches.
    if not any(value in {"feature_films", "prelinger"} for value in collections):
        return None

    return {
        "id": identifier,
        "title": title,
        "creator": creator,
        "year": year,
        "added_date": added_date,
        "collection": collections,
        "description": description,
        "subjects": subjects,
        "downloads": downloads,
        "license_label": label,
        "license_url": license_url,
        "source_url": f"{BASE_URL}/details/{quote(identifier, safe='')}",
        "poster_url": f"{BASE_URL}/services/img/{quote(identifier, safe='')}",
        "stream_url": stream_url,
        "file_size": as_int(video.get("size")),
    }


def build_catalog() -> dict:
    # The user-facing library should feel like a film catalogue, not a random
    # video archive. Prefer the dedicated feature-film collection; use Prelinger
    # as a second curated source for licensed historical films.
    result_sets = []
    for collection_query in ("collection:feature_films", "collection:prelinger"):
        result_sets.append(get_search_docs(collection_query, "publicdate desc"))
        result_sets.append(get_search_docs(collection_query, "downloads desc"))
    candidates = interleave_docs(result_sets)
    items: list[dict] = []
    processed = 0
    seen = set()
    for doc in candidates:
        if processed >= MAX_METADATA_LOOKUPS or len(items) >= TARGET_ITEMS:
            break
        identifier = str(doc.get("identifier") or "").strip()
        if identifier in seen:
            continue
        seen.add(identifier)
        processed += 1
        try:
            item = enrich_item(doc)
        except Exception as exc:
            print(f"Skipping one item ({type(exc).__name__}).", file=sys.stderr)
            continue
        if item:
            items.append(item)
        time.sleep(0.08)

    if len(items) < MIN_ITEMS_TO_PUBLISH:
        raise RuntimeError(
            f"Only {len(items)} playable items passed licence and MP4 checks; "
            "keeping the previous catalogue unchanged."
        )

    items.sort(key=lambda item: (item.get("downloads", 0), item.get("year") or 0), reverse=True)
    generated_at = datetime.now(timezone.utc).isoformat()
    return {
        "generated_at": generated_at,
        "source": "Internet Archive public search and metadata APIs",
        "total": len(items),
        "license_policy": [
            "Creative Commons Public Domain Mark",
            "CC0",
            "CC BY",
            "CC BY-SA",
        ],
        "notice": (
            "License information is supplied by the Internet Archive item uploader. "
            "Check the linked source and license for each work, and confirm rights in your jurisdiction."
        ),
        "items": items,
    }


def write_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(content, encoding="utf-8", newline="\n")
    temp.replace(path)


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(description="Refresh the NISSKENZI licensed film catalogue.")
    parser.add_argument("--output", type=Path, default=Path("catalog.json"))
    args = parser.parse_args()
    try:
        catalog = build_catalog()
        write_atomic(args.output, json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
        print(f"Catalogue refreshed: {catalog['total']} licensed films.")
        return 0
    except Exception as exc:
        print(f"ERROR: catalogue refresh failed ({type(exc).__name__}): {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
