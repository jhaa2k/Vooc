"""Library helpers: search, sort and filter chips for the playlist (pure Python, no Kivy)."""

import os

SORTS = ["Newest", "Title A-Z", "Title Z-A", "Artist", "Format"]
BASE_CHIPS = ["All", "Favorites"]


def ext_of(path):
    return os.path.splitext(str(path))[1].lstrip(".").upper() or "?"


def title_of(path, meta):
    m = (meta or {}).get(path) or {}
    return m.get("title") or os.path.splitext(os.path.basename(str(path)))[0]


def artist_of(path, meta):
    return ((meta or {}).get(path) or {}).get("artist") or ""


def chips(playlist):
    """All, Favorites, then one chip per audio format present."""
    fmts = sorted({ext_of(p) for p in playlist})
    return BASE_CHIPS + fmts


def view(playlist, query="", sort="Newest", chip="All", favorites=(), meta=None):
    """Return the visible paths after search (title/artist/file name), chip filter and sort."""
    favs = set(favorites or ())
    q = (query or "").strip().lower()
    items = list(enumerate(playlist))
    if chip == "Favorites":
        items = [(i, p) for i, p in items if p in favs]
    elif chip not in ("All", "", None):
        items = [(i, p) for i, p in items if ext_of(p) == chip]
    if q:
        words = q.split()

        def hay(p):
            return " ".join((title_of(p, meta), artist_of(p, meta), os.path.basename(str(p)))).lower()
        items = [(i, p) for i, p in items if all(w in hay(p) for w in words)]
    if sort == "Title A-Z":
        items.sort(key=lambda ip: title_of(ip[1], meta).lower())
    elif sort == "Title Z-A":
        items.sort(key=lambda ip: title_of(ip[1], meta).lower(), reverse=True)
    elif sort == "Artist":
        items.sort(key=lambda ip: (artist_of(ip[1], meta).lower() or "\uffff", title_of(ip[1], meta).lower()))
    elif sort == "Format":
        items.sort(key=lambda ip: (ext_of(ip[1]), title_of(ip[1], meta).lower()))
    else:                                         # newest first
        items.sort(key=lambda ip: -ip[0])
    return [p for _, p in items]
