# NISSKENZI

Personal project for automatically refreshing a media catalogue.

## What it does

- Fetches the sources listed in `sources.txt`.
- Combines M3U entries while removing duplicate stream URLs.
- Preserves entry metadata and stream options.
- Validates the generated output and runs tests.
- Refreshes `playlist.m3u` on a schedule using GitHub Actions.
- Updates the generated file only when its contents have changed.

## First run

1. Open the **Actions** tab.
2. Select **NISSKENZI catalogue refresh**.
3. Choose **Run workflow** if you want to start a run immediately.
4. Open the run and check that the test and generation steps pass.
5. After a successful run, the generated `playlist.m3u` will appear in the repository.

The schedule is approximately every six hours. GitHub can delay scheduled jobs, and a scheduled job may be disabled after extended inactivity. The computer does not need to stay on.

## Sources

Add one HTTP(S) playlist URL per line in `sources.txt`. Lines starting with `#` are comments. Use only sources and streams you are authorized to access. Do not put passwords, access tokens, or private URLs in this public repository.

The starter source is a community catalogue; individual streams may be unavailable, geo-restricted, or subject to their own distribution conditions. This project does not bypass authentication or access controls.

## Details

- GitHub Actions runs a job and exits; it is not a permanent streaming proxy.
- If every source fails or returns no entries, the job fails rather than replacing a working playlist with an empty file.
- The workflow needs repository **contents: write** permission to commit updated output.

## Run locally (optional)

Requires Python 3.12 or newer:

```bash
python refresh_catalogue.py --sources sources.txt --output playlist.m3u
python -m unittest discover -s tests -v
```
