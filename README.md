# NISSKENZI

Personal live-TV catalogue builder and diagnostics toolkit.

## Current outputs

- Worldwide M3U: [playlist.m3u](https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/playlist.m3u)
- France M3U: [playlist-fr.m3u](https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/playlist-fr.m3u)
- Worldwide source report: [catalogue-status.json](https://github.com/Nissay8z/NISSKENZI/blob/main/catalogue-status.json)
- France source report: [catalogue-status-fr.json](https://github.com/Nissay8z/NISSKENZI/blob/main/catalogue-status-fr.json)

## Architecture

The generator loads a primary HTTP(S) M3U URL from the `NISSKENZI_PRIMARY_M3U_URL` GitHub Actions secret, if configured, then adds the fallback sources in `sources.txt`. The primary entries come first, and exact URL duplicates are removed. Obvious VOD/replay categories are filtered, and EPG metadata is added to playlist headers.

A matching France-only playlist is generated separately.

## Primary source configuration

To add an authorized M3U export URL:

1. Open **Settings → Secrets and variables → Actions**.
2. Choose **New repository secret**.
3. Name it `NISSKENZI_PRIMARY_M3U_URL`.
4. Enter the authorized export URL as its value, then save it.
5. Run **Actions → NISSKENZI catalogue refresh → Run workflow**.

The secret is not written to source files or health reports. The browser page at vavoo.net is an interactive web UI, not by itself an export playlist URL. The project does not implement hidden endpoint scraping, token/signature recreation, stream URL extraction, or bypasses for access controls. When no authorized primary export is configured, community catalogues remain the fallback.

## Stream health sample

A separate manual workflow checks a small sample of HTTP(S) URLs using bounded byte-range requests. It is deliberately not scheduled, to limit requests to upstream providers.

1. Open **Actions → NISSKENZI stream health sample**.
2. Click **Run workflow**.
3. Select the worldwide or France playlist and the sample size.
4. Download the `nisskenzi-stream-health` artifact after the workflow completes.

The JSON report records channel name, category, host, HTTP status, latency and whether the response resembles an M3U manifest. It does not contain full stream URLs or query tokens. A successful HTTP response is not a guarantee that video playback works.

## Scheduled updates

The catalogue workflow runs roughly every six hours and can be triggered manually. The computer does not need to stay on. GitHub can delay scheduled runs. Generation tests run before writing output, and a failed/empty source set does not overwrite a good playlist with an empty file.

## Public repository warning

This repository is public; generated playlist entries and direct stream URLs in the M3U files can be read by other people. Keep private URLs and tokens in repository secrets, and use a private repository if you need the generated output to remain private.

## Local tests

Requires Python 3.12+:

```bash
python -m unittest discover -s tests -v
python refresh_catalogue.py --sources sources.txt --output playlist.m3u
python check_streams.py playlist.m3u --limit 80 --output stream-health.json
```
