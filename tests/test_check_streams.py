import unittest
from unittest.mock import Mock

from refresh_catalogue import Entry
from check_streams import entry_group, entry_title, probe_entry, select_sample


class StreamCheckTests(unittest.TestCase):
    def test_extracts_title_and_group(self):
        entry = Entry(
            ('#EXTINF:-1 group-title="News",Example France',),
            'https://example.org/live.m3u8',
        )
        self.assertEqual(entry_title(entry), "Example France")
        self.assertEqual(entry_group(entry), "News")

    def test_sample_covers_groups_before_duplicates(self):
        entries = [
            Entry(('#EXTINF:-1 group-title="News",N1',), 'https://x.example/1'),
            Entry(('#EXTINF:-1 group-title="News",N2',), 'https://x.example/2'),
            Entry(('#EXTINF:-1 group-title="Sport",S1',), 'https://x.example/3'),
            Entry(('#EXTINF:-1 group-title="Movies",M1',), 'https://x.example/4'),
        ]
        result = select_sample(entries, 3)
        self.assertEqual([entry.url for entry in result], [
            'https://x.example/1', 'https://x.example/3', 'https://x.example/4'
        ])

    def test_sample_limit_is_bounded(self):
        with self.assertRaises(ValueError):
            select_sample([], 501)

    def test_probe_reports_basic_http_status_without_url(self):
        class FakeResponse:
            status = 206
            headers = {"Content-Type": "application/vnd.apple.mpegurl"}
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self, size): return b"#EXTM3U\n#EXTINF:-1,Example\n"

        def fake_opener(request, timeout):
            self.assertEqual(request.get_header("Range"), "bytes=0-1023")
            return FakeResponse()

        entry = Entry(
            ('#EXTINF:-1 group-title="Test",Example',),
            'https://example.org/live.m3u8?token=SECRET',
        )
        result = probe_entry(entry, opener=fake_opener)
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["http_status"], 206)
        self.assertTrue(result["looks_like_m3u"])
        self.assertNotIn("SECRET", str(result))
        self.assertNotIn("url", result)


if __name__ == "__main__":
    unittest.main()
