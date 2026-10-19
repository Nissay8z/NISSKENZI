import unittest

from refresh_catalogue import (
    Entry,
    build_playlist,
    deduplicate_entries,
    parse_m3u,
    render_playlist,
)


SAMPLE = """#EXTM3U
#EXTINF:-1 tvg-id="example" group-title="News",Example News
#EXTVLCOPT:http-user-agent=ExampleAgent
https://example.org/live.m3u8
#EXTINF:-1 group-title="Movies",Example Movies
https://example.org/movie.m3u8
"""


class PlaylistParserTests(unittest.TestCase):
    def test_preserves_metadata_and_stream_options(self):
        entries = parse_m3u(SAMPLE)
        self.assertEqual(len(entries), 2)
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


if __name__ == "__main__":
    unittest.main()
