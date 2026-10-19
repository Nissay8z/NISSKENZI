import unittest

from catalog_builder import (
    choose_mp4,
    interleave_docs,
    is_suitable_title,
    license_label,
    normalize_subjects,
)


class CatalogBuilderTests(unittest.TestCase):
    def test_accepts_only_allowed_license_families(self):
        self.assertEqual(license_label("https://creativecommons.org/publicdomain/mark/1.0/"), "Marque du domaine public")
        self.assertEqual(license_label("http://creativecommons.org/publicdomain/zero/1.0/"), "CC0 — domaine public")
        self.assertEqual(license_label("https://creativecommons.org/licenses/by/4.0/"), "Creative Commons BY")
        self.assertEqual(license_label("https://creativecommons.org/licenses/by-sa/3.0/"), "Creative Commons BY-SA")
        self.assertIsNone(license_label("https://creativecommons.org/licenses/by-nc/4.0/"))
        self.assertIsNone(license_label("https://example.org/license"))

    def test_mp4_picker_ignores_private_samples_and_too_small_files(self):
        files = [
            {"name": "private.mp4", "size": "50000000", "private": "true"},
            {"name": "film_sample.mp4", "size": "50000000"},
            {"name": "tiny.mp4", "size": "5000"},
            {"name": "movie.mp4", "size": "40000000", "format": "MPEG4"},
            {"name": "movie-large.mp4", "size": "100000000", "format": "MPEG4"},
        ]
        chosen = choose_mp4(files)
        self.assertEqual(chosen["name"], "movie-large.mp4")

    def test_round_robin_deduplicates_identifiers(self):
        groups = [
            [{"identifier": "new-a"}, {"identifier": "shared"}],
            [{"identifier": "popular-x"}, {"identifier": "shared"}],
        ]
        result = interleave_docs(groups)
        self.assertEqual([row["identifier"] for row in result], ["new-a", "popular-x", "shared"])

    def test_excludes_adult_themed_titles_and_raw_video_batches(self):
        self.assertFalse(is_suitable_title("Diary of a Nudist"))
        self.assertFalse(is_suitable_title("The Naked Witch"))
        self.assertFalse(is_suitable_title("Publicvideos batch 9"))
        self.assertFalse(is_suitable_title("Double Feature Hell"))
        self.assertTrue(is_suitable_title("Sita Sings the Blues"))

    def test_subjects_are_sanitized_and_deduplicated(self):
        values = ["Drama; Comedy", "drama", "<b>History</b>", ""]
        result = normalize_subjects(values)
        self.assertEqual(result, ["Drama", "Comedy", "History"])


if __name__ == "__main__":
    unittest.main()
