import unittest

from vooc.history import History


class HistoryTest(unittest.TestCase):
    def test_undo_redo_and_limit(self):
        h = History(limit=3)
        h.reset({"v": 0})
        self.assertFalse(h.record({"v": 0}))
        for i in range(1, 6):
            self.assertTrue(h.record({"v": i}))
        self.assertEqual(h.undo(), {"v": 4})
        self.assertEqual(h.undo(), {"v": 3})
        self.assertEqual(h.undo(), {"v": 2})
        self.assertIsNone(h.undo())           # limited to 3 steps
        self.assertEqual(h.redo(), {"v": 3})
        h.record({"v": 9})                    # new edit clears redo
        self.assertFalse(h.can_redo)
        self.assertEqual(h.undo(), {"v": 3})


if __name__ == "__main__":
    unittest.main()
