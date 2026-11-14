import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from vooc import lyrics  # noqa: E402

LRC = """[ti:Song]
[offset:+500]
[00:01.00]First line
[00:05.50][00:20.25]Chorus
[00:10.123]Third
[00:12.00]
"""


class LyricsTests(unittest.TestCase):
    def test_parse_and_lookup(self):
        lines = lyrics.parse_lrc(LRC)
        self.assertEqual([round(t, 3) for t, _ in lines], [0.5, 5.0, 9.623, 11.5, 19.75])
        self.assertEqual(lines[1][1], "Chorus")
        self.assertEqual(lines[-1][1], "Chorus")
        self.assertEqual(lyrics.line_index(lines, 0.1), -1)
        self.assertEqual(lyrics.line_index(lines, 6.0), 1)
        rows, i = lyrics.window(lines, 10.0, 1)
        self.assertEqual(rows, ["Chorus", "Third", ""])
        self.assertEqual(i, 2)

    def test_find_next_to_track(self):
        d = tempfile.mkdtemp()
        track = os.path.join(d, "song.mp3")
        open(track, "w").close()
        self.assertEqual(lyrics.find_lrc(track), "")
        with open(os.path.join(d, "song.lrc"), "w") as f:
            f.write(LRC)
        found = lyrics.find_lrc(track)
        self.assertTrue(found.endswith("song.lrc"))
        self.assertEqual(len(lyrics.load(found)), 5)


if __name__ == "__main__":
    unittest.main()
