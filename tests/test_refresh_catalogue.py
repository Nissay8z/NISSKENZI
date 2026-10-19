import unittest

from refresh_catalogue import (
    Entry,
    build_catalogue,
    build_playlist,
    deduplicate_entries,
    filter_live_entries,
    parse_m3u,
    render_playlist,
)


SAMPLE = """#EXTM3U
#EXTINF:-1 tvg-id="example" group-title="News",Example News
#EXTVLCOPT:http-user-agent=ExampleAgent
https://example.org/live.m3u8
#EXTINF:-1 group-title="Movies on Demand",Old film
https://example.org/vod.m3u8
#EXTINF:-1 group-title="Cinema",Cinema Live
https://example.org/cinema-live.m3u8
"""


class PlaylistParserTests(unittest.TestCase):
    def test_preserves_metadata_and_stream_options(self):
        entries = parse_m3u(SAMPLE)
        self.assertEqual(len(entries), 3)
        self.assertEqual(entries[0].metadata[0], '#EXTINF:-1 tvg-id="example" group-title="News",Example News')
        self.assertIn("#EXTVLCOPT:http-user-agent=ExampleAgent", entries[0].metadata)
        self.assertEqual(entries[0].url, "https://example.org/live.m3u8")

    def test_accepts_header_attributes(self):
        entries = parse_m3u(
            '#EXTM3U x-tvg-url="https://guide.example/fr.xml"\n'
            "#EXTINF:-1,Example\nhttps://example.org/live.m3u8\n"
        )
        self.assertEqual(len(entries), 1)

    def test_render_adds_epg_urls_without_duplicates(self):
        entries = [Entry(("#EXTINF:-1,Example",), "https://example.org/live.m3u8")]
        output = render_playlist(
            entries,
            [
                "https://guide.example/fr.xml",
                "https://guide.example/us.xml",
                "https://guide.example/fr.xml",
                "http://invalid.example/guide.xml",
            ],
        )
        self.assertTrue(
            output.startswith(
                '#EXTM3U x-tvg-url="https://guide.example/fr.xml,https://guide.example/us.xml"\n'
            )
        )

    def test_live_filter_removes_vod_but_keeps_linear_cinema_channel(self):
        entries = parse_m3u(SAMPLE)
        filtered, removed = filter_live_entries(entries)
        self.assertEqual(removed, 1)
        self.assertEqual(len(filtered), 2)
        self.assertEqual(filtered[1].url, "https://example.org/cinema-live.m3u8")

    def test_bare_stream_url_gets_fallback_metadata(self):
        entries = parse_m3u("#EXTM3U\nhttps://example.org/channel.m3u8\n")
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0].metadata, ("#EXTINF:-1,channel.m3u8",))

    def test_rejects_non_m3u_input(self):
        with self.assertRaises(ValueError):
            parse_m3u("not a playlist")

    def test_deduplicates_exact_urls(self):
        entries = [
            Entry(("#EXTINF:-1,First",), "https://example.org/live.m3u8"),
            Entry(("#EXTINF:-1,Duplicate",), "https://example.org/live.m3u8"),
            Entry(("#EXTINF:-1,Other",), "https://example.org/other.m3u8"),
        ]
        result = deduplicate_entries(entries)
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0].metadata, ("#EXTINF:-1,First",))

    def test_combines_sources_and_reports_failures(self):
        def fake_fetcher(url, timeout):
            if url == "https://bad.example/list.m3u":
                raise OSError("unavailable")
            return SAMPLE

        content, count, errors = build_playlist(
            ["https://good.example/list.m3u", "https://bad.example/list.m3u"],
            fetcher=fake_fetcher,
        )
        self.assertEqual(count, 2)
        self.assertEqual(len(errors), 1)
        self.assertTrue(content.startswith("#EXTM3U\n"))
        self.assertEqual(content.count("https://example.org/live.m3u8"), 1)

    def test_primary_catalogue_is_first_and_fallback_supplements_it(self):
        primary = "#EXTM3U\n#EXTINF:-1,Primary Channel\nhttps://primary.example/live.m3u8\n"
        fallback = (
            "#EXTM3U\n"
            "#EXTINF:-1,Primary Channel Duplicate\nhttps://primary.example/live.m3u8\n"
            "#EXTINF:-1,Backup Only\nhttps://backup.example/live.m3u8\n"
        )
        def fake_fetcher(url, timeout):
            return {"https://primary.example/list.m3u": primary,
                    "https://fallback.example/list.m3u": fallback}[url]

        content, count, errors, report, primary_content = build_catalogue(
            ["https://fallback.example/list.m3u"],
            fetcher=fake_fetcher,
            primary_url="https://primary.example/list.m3u",
        )
        self.assertEqual(count, 2)
        self.assertEqual(errors, [])
        self.assertTrue(report["primary_loaded"])
        self.assertEqual(len(parse_m3u(primary_content)), 1)
        self.assertLess(content.index("https://primary.example/live.m3u8"),
                        content.index("https://backup.example/live.m3u8"))

    def test_fallback_works_when_primary_fails(self):
        fallback = "#EXTM3U\n#EXTINF:-1,Backup Channel\nhttps://backup.example/live.m3u8\n"
        def fake_fetcher(url, timeout):
            if url.startswith("https://primary.example"):
                raise OSError("should not be echoed")
            return fallback

        content, count, errors, report, primary_content = build_catalogue(
            ["https://fallback.example/list.m3u"],
            fetcher=fake_fetcher,
            primary_url="https://primary.example/list.m3u?token=SECRET",
        )
        self.assertEqual(count, 1)
        self.assertTrue(errors)
        self.assertFalse(report["primary_loaded"])
        self.assertIsNone(primary_content)
        self.assertNotIn("SECRET", str(report))
        self.assertIn("https://backup.example/live.m3u8", content)


if __name__ == "__main__":
    unittest.main()
