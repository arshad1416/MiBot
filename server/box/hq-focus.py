#!/usr/bin/env python3
"""Give one window the keyboard on a screen that has no window manager.

Usage: hq-focus.py WINDOW_ID        (the display comes from $DISPLAY)

With no window manager nothing hands the keyboard to a new window. The browser took it when it
started and keeps it, so a terminal opened for a sign-in never sees a key. Until that window closes
this keeps it above the other windows and gives it the keyboard when it opens and whenever the pointer
is over it; the browser takes the keyboard back when it is clicked.

Where a window manager runs this does nothing and exits: focus is the window manager's job there.
Standard library only: it talks to the X server through libX11.
"""
import ctypes
import ctypes.util
import sys
import time

TICK = 0.2
LONGEST = 6 * 3600          # a sign-in left open for hours is abandoned
BAD_WINDOW = 3

c_int, c_uint, c_ulong, c_void_p = ctypes.c_int, ctypes.c_uint, ctypes.c_ulong, ctypes.c_void_p
ref = ctypes.byref


class XErrorEvent(ctypes.Structure):
    _fields_ = [('type', c_int), ('display', c_void_p), ('resourceid', c_ulong), ('serial', c_ulong),
                ('error_code', ctypes.c_ubyte), ('request_code', ctypes.c_ubyte), ('minor_code', ctypes.c_ubyte)]


class XWindowAttributes(ctypes.Structure):
    _fields_ = [('x', c_int), ('y', c_int), ('width', c_int), ('height', c_int), ('border_width', c_int), ('depth', c_int),
                ('visual', c_void_p), ('root', c_ulong), ('c_class', c_int), ('bit_gravity', c_int), ('win_gravity', c_int),
                ('backing_store', c_int), ('backing_planes', c_ulong), ('backing_pixel', c_ulong), ('save_under', c_int),
                ('colormap', c_ulong), ('map_installed', c_int), ('map_state', c_int), ('all_event_masks', ctypes.c_long),
                ('your_event_mask', ctypes.c_long), ('do_not_propagate_mask', ctypes.c_long), ('override_redirect', c_int),
                ('screen', c_void_p)]


def load():
    x = ctypes.CDLL(ctypes.util.find_library('X11') or 'libX11.so.6')
    P, W = c_void_p, c_ulong
    ptr = ctypes.POINTER
    for name, res, args in [
        ('XOpenDisplay', P, [ctypes.c_char_p]),
        ('XDefaultRootWindow', W, [P]),
        ('XInternAtom', c_ulong, [P, ctypes.c_char_p, c_int]),
        ('XGetWindowProperty', c_int, [P, W, c_ulong, ctypes.c_long, ctypes.c_long, c_int, c_ulong, ptr(c_ulong), ptr(c_int), ptr(c_ulong), ptr(c_ulong), ptr(P)]),
        ('XGetWindowAttributes', c_int, [P, W, ptr(XWindowAttributes)]),
        ('XQueryTree', c_int, [P, W, ptr(W), ptr(W), ptr(ptr(W)), ptr(c_uint)]),
        ('XQueryPointer', c_int, [P, W, ptr(W), ptr(W), ptr(c_int), ptr(c_int), ptr(c_int), ptr(c_int), ptr(c_uint)]),
        ('XGetInputFocus', c_int, [P, ptr(W), ptr(c_int)]),
        ('XSetInputFocus', c_int, [P, W, c_int, c_ulong]),
        ('XRaiseWindow', c_int, [P, W]),
        ('XSync', c_int, [P, c_int]),
        ('XFree', c_int, [P]),
    ]:
        fn = getattr(x, name)
        fn.restype, fn.argtypes = res, args
    return x


def main(argv):
    try:
        wid = int(argv[0], 0)
    except (IndexError, ValueError):
        sys.exit('usage: hq-focus.py WINDOW_ID')
    x = load()
    d = x.XOpenDisplay(None)
    if not d:
        sys.exit('no display')
    root = x.XDefaultRootWindow(d)

    errors = []
    handler = ctypes.CFUNCTYPE(c_int, c_void_p, ctypes.POINTER(XErrorEvent))(lambda _d, ev: errors.append(ev.contents.error_code) or 0)
    x.XSetErrorHandler(handler)

    def window_manager():
        atom = x.XInternAtom(d, b'_NET_SUPPORTING_WM_CHECK', 1)
        if not atom:
            return False
        kind, fmt, n, left, data = c_ulong(), c_int(), c_ulong(), c_ulong(), c_void_p()
        x.XGetWindowProperty(d, root, atom, 0, 1, 0, 0, ref(kind), ref(fmt), ref(n), ref(left), ref(data))
        if data:
            x.XFree(data)
        return bool(kind.value)

    def attributes(w):
        a = XWindowAttributes()
        return a if x.XGetWindowAttributes(d, w, ref(a)) else None

    def top_window():
        """The highest window a person would call a window (menus and tooltips do not count)."""
        r, parent, kids, n = c_ulong(), c_ulong(), ctypes.POINTER(c_ulong)(), c_uint()
        if not x.XQueryTree(d, root, ref(r), ref(parent), ref(kids), ref(n)):
            return None
        try:
            for i in range(n.value - 1, -1, -1):
                a = attributes(kids[i])
                if a and a.map_state == 2 and not a.override_redirect:
                    return kids[i]
        finally:
            if kids:
                x.XFree(kids)
        return None

    def under_pointer():
        r, child = c_ulong(), c_ulong()
        rx, ry, wx, wy, mask = c_int(), c_int(), c_int(), c_int(), c_uint()
        x.XQueryPointer(d, root, ref(r), ref(child), ref(rx), ref(ry), ref(wx), ref(wy), ref(mask))
        return child.value

    def focused():
        w, revert = c_ulong(), c_int()
        x.XGetInputFocus(d, ref(w), ref(revert))
        return w.value

    greeted = False
    checked_wm = 0.0
    end = time.time() + LONGEST
    while time.time() < end:
        if time.time() - checked_wm > 5:
            checked_wm = time.time()
            if window_manager():
                return
        mine = attributes(wid)
        if BAD_WINDOW in errors:
            return                              # the window closed
        del errors[:]
        if mine and mine.map_state == 2:
            if top_window() != wid:
                x.XRaiseWindow(d, wid)
            if (not greeted or under_pointer() == wid) and focused() != wid:
                x.XSetInputFocus(d, wid, 1, 0)
            greeted = True
        x.XSync(d, 0)
        time.sleep(TICK)


if __name__ == '__main__':
    main(sys.argv[1:])
