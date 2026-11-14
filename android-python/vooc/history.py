"""Undo / redo history of project snapshots (pure Python, no kivy).

The app calls ``record(state)`` after every (debounced) edit with the project as a
JSON-able dict. Identical consecutive states are ignored, so autosave can call it freely.
"""
import json


class History:
    def __init__(self, limit=20):
        self.limit = int(limit)
        self._undo = []          # past states (oldest first), each a JSON string
        self._redo = []
        self._current = None

    @staticmethod
    def _key(state):
        return json.dumps(state, sort_keys=True, separators=(",", ":"))

    def reset(self, state):
        self._undo, self._redo = [], []
        self._current = self._key(state)

    def record(self, state):
        """Returns True when ``state`` differs from the last one (a new undo step)."""
        key = self._key(state)
        if self._current is None:
            self._current = key
            return False
        if key == self._current:
            return False
        self._undo.append(self._current)
        del self._undo[:-self.limit]
        self._redo = []
        self._current = key
        return True

    @property
    def can_undo(self):
        return bool(self._undo)

    @property
    def can_redo(self):
        return bool(self._redo)

    def undo(self):
        if not self._undo:
            return None
        self._redo.append(self._current)
        self._current = self._undo.pop()
        return json.loads(self._current)

    def redo(self):
        if not self._redo:
            return None
        self._undo.append(self._current)
        self._current = self._redo.pop()
        return json.loads(self._current)
