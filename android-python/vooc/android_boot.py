"""Create the Kivy window safely on Android.

If the activity is paused while the app is still starting (user presses Home
during the first-run "Loading..." screen, the screen turns off, a system popup
or a configuration change), SDL has no native surface and Kivy fails with
``RuntimeError: Could not fetch native window``. Kivy then leaves
``Window = None`` and the app closes immediately.

``get_window()`` waits until SDL reports the activity as resumed before the
window is created, and retries the creation after a failure instead of letting
the app die. This module must be imported before ``kivy.core.window``.
"""
import os
import time

ON_ANDROID = "ANDROID_ARGUMENT" in os.environ or "ANDROID_PRIVATE" in os.environ


def _sdl_activity():
    try:
        from jnius import autoclass
        return autoclass("org.libsdl.app.SDLActivity")
    except Exception as exc:  # pragma: no cover - Android only
        print("Vooc boot: SDLActivity not reachable: %s" % exc)
        return None


def _is_resumed(sdl):
    try:
        state = sdl.mCurrentNativeState
        if state is not None and str(state.toString()) != "RESUMED":
            return False
    except Exception:
        pass
    try:
        if not bool(sdl.mIsResumedCalled):
            return False
    except Exception:
        pass
    return True


def _activity_going_away(sdl):
    """True while Android is destroying the activity (finish or relaunch).

    SDLActivity.onDestroy() blocks the UI thread until the Python thread ends,
    so waiting for "resumed" here would freeze the app on "Loading..." forever.
    Returns "restart" for a configuration relaunch, "finish" for a real close.
    """
    try:
        act = sdl.mSingleton
        if act is None:
            return None
        if bool(act.isChangingConfigurations()):
            return "restart"
        if bool(act.isFinishing()) or bool(act.isDestroyed()):
            return "finish"
    except Exception:
        pass
    return None


def _leave(reason):
    print("Vooc boot: activity is being destroyed during startup (%s)" % reason)
    if reason == "restart":
        try:
            from jnius import autoclass
            act = autoclass("org.kivy.android.PythonActivity").mActivity
            ctx = act.getApplicationContext()
            Intent = autoclass("android.content.Intent")
            intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(intent)
            time.sleep(0.2)
        except Exception as exc:
            print("Vooc boot: restart failed: %s" % exc)
    os._exit(0)


def wait_until_resumed(timeout=None):
    """Block until the Android activity is in the foreground again."""
    if not ON_ANDROID:
        return True
    sdl = _sdl_activity()
    if sdl is None:
        time.sleep(0.5)
        return True
    started = time.time()
    while not _is_resumed(sdl):
        going = _activity_going_away(sdl)
        if going:
            _leave(going)
        if timeout is not None and time.time() - started > timeout:
            return False
        time.sleep(0.1)
    # give SDL a moment to attach the new surface after onResume()
    time.sleep(0.15)
    return True


def get_window(attempts=40):
    """Return a created Kivy Window, retrying while the app is in background."""
    wait_until_resumed()
    import kivy.core.window as kcw
    if kcw.Window is not None or not ON_ANDROID:
        return kcw.Window
    from kivy.core import core_select_lib
    for attempt in range(attempts):
        print("Vooc boot: native window not ready, retry %d" % (attempt + 1))
        wait_until_resumed()
        time.sleep(0.3)
        try:
            kcw.Window = core_select_lib("window", kcw.window_impl, True)
        except Exception as exc:
            print("Vooc boot: window retry failed: %s" % exc)
            kcw.Window = None
        if kcw.Window is not None:
            return kcw.Window
    return None
