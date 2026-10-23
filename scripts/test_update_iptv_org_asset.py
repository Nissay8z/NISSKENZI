#!/usr/bin/env python3
"""Offline regression checks for the embedded IPTV-org M3U transformer."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("update-iptv-org-asset.py")
SPEC = importlib.util.spec_from_file_location("iptv_org_asset_generator", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Impossible de charger le générateur IPTV-org.")
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)
GENERATOR.MIN_CHANNELS = 2


class EmbeddedPlaylistTests(unittest.TestCase):
    def test_preserves_country_metadata_and_quoted_commas(self) -> None:
        sample = "\n".join([
            "#EXTM3U",
            '#EXTINF:-1 tvg-logo="https://logos.example/logo?a=1,b=2" group-title="France",Journal, France',
            "https://stream.example/fr.m3u8",
            '#EXTINF:-1 tvg-name="ARD" tvg-country="DE",ARD',
            "https://stream.example/de.m3u8",
            '#EXTINF:-1 group-title="Germany",Duplicate URL',
            "https://stream.example/de.m3u8",
            "",
        ])
        playlist, count = GENERATOR.build_embedded_playlist(sample)
        self.assertEqual(count, 2)
        self.assertEqual(playlist.count("https://stream.example/de.m3u8"), 1)
        self.assertIn('group-title="France"', playlist)
        self.assertIn('tvg-country="France"', playlist)
        self.assertIn('tvg-country="DE"', playlist)
        self.assertIn('group-title="DE"', playlist)
        self.assertIn("Journal, France", playlist)
        self.assertIn('tvg-logo="https://logos.example/logo?a=1,b=2"', playlist)


if __name__ == "__main__":
    unittest.main()
