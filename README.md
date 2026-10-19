# NISSKENZI

Personal project for automatically refreshing a live-TV catalogue.

## Outputs

- Worldwide catalogue: [playlist.m3u](https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/playlist.m3u)
- France catalogue: [playlist-fr.m3u](https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/playlist-fr.m3u)
- Latest source report: [catalogue-status.json](https://github.com/Nissay8z/NISSKENZI/blob/main/catalogue-status.json)

The generated lists keep M3U channel metadata, remove exact URL duplicates, and filter entries whose category explicitly identifies a VOD/replay service. Linear channels whose names mention cinema or films remain included.

## Preferred source and fallback

NISSKENZI supports a primary, authorized M3U export URL and then supplements its entries with the public fallback catalogues listed in `sources.txt`. The primary source is read first, so its entries appear before fallback entries in the merged output.

To configure a primary feed, use a playlist URL that you are authorized to access and that is intended for M3U export:

1. Open the repository's **Settings → Secrets and variables → Actions**.
2. Select **New repository secret**.
3. Name it `NISSKENZI_PRIMARY_M3U_URL`.
4. Set its value to the authorized M3U export URL and save it.
5. Run **Actions → NISSKENZI catalogue refresh → Run workflow**.

The URL is read from a GitHub Actions secret and is not written into the source files or health report. If no primary URL is configured or the primary fails, the job continues using the configured fallback lists. Do not put passwords or private access tokens directly into committed files.

**Important:** NISSKENZI does not extract hidden stream URLs, refresh private Vavoo tokens, or bypass access controls. The provided Vavoo web page is a browser interface, not a documented M3U export URL. Until an authorized export URL is available and configured, the primary feed is unconfigured and the community catalogues supply the output. A community list is not proof that each stream is authorized or available.

## Scheduled updates

GitHub Actions refreshes the lists approximately every six hours and may also be launched manually. The computer does not need to stay on. Scheduled jobs can be delayed by GitHub.

Each run executes the unit tests, generates the worldwide and French lists, writes JSON source reports and commits only changes. If all sources fail or return no usable live-TV entries, the job fails rather than replacing an existing playlist with an empty file.

## Public repository warning

This repository is currently public. Generated playlists and their stream URLs are public too. Keep private URLs out of committed files; use a private repository if any generated output must remain private.

## Local tests

Requires Python 3.12 or newer:

```bash
python -m unittest discover -s tests -v
python refresh_catalogue.py --sources sources.txt --output playlist.m3u
```
