"""LRC synced lyrics: parsing and lookup (Text layer Source = Lyrics)."""
import bisect
import os
import re

_TS = re.compile(r"\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?\]")
_OFFSET = re.compile(r"\[offset:\s*([+-]?\d+)\s*\]", re.I)


def parse_lrc(text):
    """Returns a time-sorted list of (seconds, line). Supports several stamps per
    line ([00:12.00][01:30.5]Chorus), [offset:+/-ms] and blank instrumental lines."""
    offset = 0.0
    m = _OFFSET.search(text or "")
    if m:
        offset = int(m.group(1)) / 1000.0
    out = []
    for raw in (text or "").splitlines():
        stamps = list(_TS.finditer(raw))
        if not stamps:
            continue
        lyric = raw[stamps[-1].end():].strip()
        for s in stamps:
            frac = s.group(3) or "0"
            t = int(s.group(1)) * 60 + int(s.group(2)) + int(frac) / (10.0 ** len(frac))
            out.append((max(0.0, t - offset), lyric))
    out.sort(key=lambda x: x[0])
    return out


def line_index(lines, t):
    """Index of the line being sung at time t (-1 before the first line)."""
    if not lines:
        return -1
    return bisect.bisect_right([x[0] for x in lines], t) - 1


def window(lines, t, context=0):
    """Current line plus `context` lines before and after (blank where missing)."""
    i = line_index(lines, t)
    rows = []
    for j in range(i - context, i + context + 1):
        rows.append(lines[j][1] if 0 <= j < len(lines) else "")
    return rows, i


def find_lrc(track_path, explicit=""):
    """Explicit path first, then <track>.lrc / <track stem>.lrc next to the audio file."""
    cands = []
    if explicit:
        cands.append(explicit)
    if track_path:
        stem, _ = os.path.splitext(track_path)
        cands += [stem + ".lrc", stem + ".LRC", track_path + ".lrc"]
    for c in cands:
        try:
            if c and os.path.isfile(c):
                return c
        except Exception:
            pass
    return ""


_cache = {}


def load(path):
    if not path:
        return []
    try:
        mtime = os.path.getmtime(path)
    except OSError:
        return []
    hit = _cache.get(path)
    if hit and hit[0] == mtime:
        return hit[1]
    for enc in ("utf-8-sig", "latin-1"):
        try:
            with open(path, encoding=enc) as f:
                lines = parse_lrc(f.read())
            break
        except UnicodeDecodeError:
            continue
    else:
        lines = []
    _cache[path] = (mtime, lines)
    return lines
