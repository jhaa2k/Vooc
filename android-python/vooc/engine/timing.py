"""Pure timing helpers (GIF playback modes / beat sync). No Kivy import."""


def gif_index(delays, t, mode="Loop", start=0):
    """Frame index of an animation with per-frame `delays` (s) at time t.

    mode: Loop, Ping-Pong, Once, Reverse. `start` shifts the first frame."""
    n = len(delays)
    if n <= 1:
        return 0
    start = int(start) % n
    order = list(range(start, n)) + list(range(0, start))
    d = [delays[i] for i in order]
    if mode == "Reverse":
        order, d = order[::-1], d[::-1]
    elif mode == "Ping-Pong":
        order = order + order[-2:0:-1]
        d = d + d[-2:0:-1]
    total = sum(d)
    if total <= 0:
        return order[0]
    if mode == "Once":
        if t >= total:
            return order[-1]
        t = max(0.0, t)
    else:
        t = t % total
    for i, di in zip(order, d):
        if t < di:
            return i
        t -= di
    return order[-1]


def synced_time(mode, now, speed, total, snap, state):
    """Playback clock for an animation of length `total` seconds.

    Free = time x speed; Beat = one frame step per detected beat (handled by
    the caller through state['beats']); BPM = one loop per bar at the current
    tempo; Bar = like BPM but restarts on every downbeat."""
    if mode == "Free" or not snap:
        return now * speed
    bpm = float(snap.get("bpm") or 120.0) or 120.0
    bar_len = 60.0 / bpm * max(1, int(snap.get("beats_per_bar") or 4))
    if mode == "BPM":
        return (now / bar_len) * total * speed
    if mode == "Bar":
        if snap.get("downbeat"):
            state["bar_start"] = now
        start = state.get("bar_start", 0.0)
        return min(total * 0.999, ((now - start) / bar_len) * total * speed)
    if mode == "Beat":
        if snap.get("beat"):
            state["beats"] = state.get("beats", 0) + 1
        return state.get("beats", 0)
    return now * speed
