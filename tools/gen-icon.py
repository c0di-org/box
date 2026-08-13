#!/usr/bin/env python3
"""Regenerate the Box mark from the artwork in Marketing/app-icon.jpeg.

The mark is three filled faces of a cube separated by seams that glow green. The
geometry below was traced off the artwork -- the faces are polygons in the
source image's pixel coordinates, and the glow is a stack of round-joined strokes fitted
to the artwork's measured falloff, because a VectorDrawable cannot blur.

Everything this writes is committed. Run it only when the artwork changes:

    python3 tools/gen-icon.py
"""

from __future__ import annotations

import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------------------------------------------------------------------
# Geometry, in the source artwork's 1254x1254 pixel space
# ---------------------------------------------------------------------------

MIRROR = 629.5           # the vertical axis the cube is symmetric about
CENTER = (629.5, 611.5)  # centre of the cube's bounding box
SPAN = 1163.0            # the cube's height in source pixels

TOP = [(629.5, 30.0), (1051.0, 296.0), (629.5, 561.0), (208.0, 296.0)]
LEFT = [(148.0, 377.0), (542.0, 619.0), (375.0, 720.0), (608.0, 864.0),
        (608.0, 1193.0), (148.0, 903.0)]
RIGHT = [(2 * MIRROR - x, y) for (x, y) in reversed(LEFT)]
FACES = [TOP, LEFT, RIGHT]

# Corner radii in source pixels, so they scale with the cube and not the viewport.
# The two elbows where a side face steps inward are the only concave corners, and
# the artwork rounds those off much harder than the silhouette's own points.
RADIUS = 16.0
REFLEX_RADIUS = 30.0

# ---------------------------------------------------------------------------
# Palette, sampled from the artwork
# ---------------------------------------------------------------------------

VOID = "#0A0B0C"    # the ground the cube sits on
FACE = "#F8F8F8"    # the lit faces
GREEN = "#2AB80C"   # the seam at full strength
HAZE = "#111C0E"    # the broad bloom, carried by the icon's background layer
HAZE_MID = "#0D120C"  # where that bloom has mostly fallen back to the void

# Green measured perpendicular to a seam, as (distance from the edge in viewport
# units, fraction of GREEN). Everything past ~4 units is too faint to be worth a
# stroke and is left to HAZE.
FALLOFF = [(0.00, 0.95), (0.11, 0.89), (0.21, 0.76), (0.32, 0.64), (0.43, 0.59),
           (0.53, 0.52), (0.75, 0.43), (0.96, 0.36), (1.28, 0.31), (1.60, 0.26),
           (2.13, 0.21), (2.67, 0.17), (3.20, 0.14), (4.26, 0.10)]

# Half-widths of the strokes that approximate that curve, widest first.
BANDS = [4.0, 3.1, 2.45, 1.9, 1.45, 1.1, 0.8, 0.55, 0.34, 0.15]


def _target(d: float) -> float:
    """The artwork's green fraction d units out from a seam."""
    for i in range(1, len(FALLOFF)):
        x0, y0 = FALLOFF[i - 1]
        x1, y1 = FALLOFF[i]
        if d <= x1:
            return y0 + (y1 - y0) * (d - x0) / (x1 - x0)
    return FALLOFF[-1][1]


def glow_bands() -> list[tuple[float, float]]:
    """(stroke width, layer alpha) for each band, widest first.

    Each band paints over the ones before it, so a band's own alpha is whatever
    takes the accumulated coverage from what the previous band left to what the
    artwork has at that distance.
    """
    out = []
    clear = 1.0  # what is still unpainted after the bands so far
    for half in BANDS:
        want = _target(half)
        alpha = 1.0 - (1.0 - want) / clear
        clear *= 1.0 - alpha
        out.append((round(half * 2, 3), round(alpha, 3)))
    return out


# ---------------------------------------------------------------------------
# Polygon helpers
# ---------------------------------------------------------------------------

def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1])


def _norm(v):
    n = math.hypot(*v)
    return (v[0] / n, v[1] / n)


def _signed_area(poly):
    n = len(poly)
    return 0.5 * sum(poly[i][0] * poly[(i + 1) % n][1] - poly[(i + 1) % n][0] * poly[i][1]
                     for i in range(n))


def inset(poly, d):
    """Push every edge inward by d and re-intersect, widening the seams by 2d."""
    if not d:
        return list(poly)
    n = len(poly)
    inward = -1.0 if _signed_area(poly) > 0 else 1.0
    lines = []
    for i in range(n):
        a, b = poly[i], poly[(i + 1) % n]
        ux, uy = _norm(_sub(b, a))
        nx, ny = inward * -uy, inward * ux
        lines.append(((a[0] + nx * d, a[1] + ny * d), (ux, uy)))
    out = []
    for i in range(n):
        p1, u1 = lines[i - 1]
        p2, u2 = lines[i]
        det = u1[1] * u2[0] - u1[0] * u2[1]
        if abs(det) < 1e-9:
            out.append(p2)
            continue
        rx, ry = p2[0] - p1[0], p2[1] - p1[1]
        t = (ry * u2[0] - rx * u2[1]) / det
        out.append((p1[0] + u1[0] * t, p1[1] + u1[1] * t))
    return out


def _rounded(poly, radius, reflex_radius, xform):
    """Path data for poly with arc-filleted corners, mapped through xform."""
    pts = [xform(p) for p in poly]
    n = len(pts)
    clockwise = _signed_area(pts) > 0  # y grows downward here
    segs = []
    for i in range(n):
        v, p, q = pts[i], pts[i - 1], pts[(i + 1) % n]
        u1, u2 = _norm(_sub(p, v)), _norm(_sub(q, v))
        half = math.acos(max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1]))) / 2.0
        cross = (v[0] - p[0]) * (q[1] - v[1]) - (v[1] - p[1]) * (q[0] - v[0])
        turn_cw = cross > 0
        r = radius if turn_cw == clockwise else reflex_radius
        t = min(r / math.tan(half),
                math.hypot(*_sub(p, v)) / 2.0,
                math.hypot(*_sub(q, v)) / 2.0)
        segs.append(((v[0] + u1[0] * t, v[1] + u1[1] * t),
                     (v[0] + u2[0] * t, v[1] + u2[1] * t),
                     t * math.tan(half),
                     1 if turn_cw else 0))
    f = lambda x: f"{round(x, 2):g}"
    d = [f"M{f(segs[0][0][0])},{f(segs[0][0][1])}"]
    for i, (a, b, r, sweep) in enumerate(segs):
        if i:
            d.append(f"L{f(a[0])},{f(a[1])}")
        d.append(f"A{f(r)},{f(r)} 0 0 {sweep} {f(b[0])},{f(b[1])}")
    d.append("Z")
    return "".join(d)


def cube_path(size=108.0, height=60.0, gap=0.0):
    """Path data for all three faces. gap widens every seam, in viewport units."""
    k = height / SPAN
    c = size / 2.0
    xform = lambda p: (c + (p[0] - CENTER[0]) * k, c + (p[1] - CENTER[1]) * k)
    return " ".join(_rounded(inset(face, gap / 2.0 / k), RADIUS * k, REFLEX_RADIUS * k, xform)
                    for face in FACES)


# ---------------------------------------------------------------------------
# Android drawables
# ---------------------------------------------------------------------------

BANNER = "<!-- Generated by tools/gen-icon.py from Marketing/app-icon.jpeg. Do not hand-edit. -->"


def _vector(body, size=108, dp=108):
    return (f'{BANNER}\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{dp}dp"\n'
            f'    android:height="{dp}dp"\n'
            f'    android:viewportWidth="{size}"\n'
            f'    android:viewportHeight="{size}">\n'
            f'{body}</vector>\n')


def _glow(d, color=GREEN):
    out = []
    for width, alpha in glow_bands():
        out.append(f'    <path\n'
                   f'        android:pathData="{d}"\n'
                   f'        android:strokeColor="{color}"\n'
                   f'        android:strokeAlpha="{alpha}"\n'
                   f'        android:strokeWidth="{width}"\n'
                   f'        android:strokeLineJoin="round" />\n')
    return "".join(out)


def _fill(d, color=FACE):
    return f'    <path\n        android:fillColor="{color}"\n        android:pathData="{d}" />\n'


def foreground():
    d = cube_path()
    return _vector(_glow(d) + _fill(d))


def background():
    """The void plus the bloom the stroke stack is too coarse to carry."""
    return (f'{BANNER}\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="108dp"\n'
            f'    android:height="108dp"\n'
            f'    android:viewportWidth="108"\n'
            f'    android:viewportHeight="108">\n'
            f'    <path android:pathData="M0,0h108v108h-108z">\n'
            f'        <aapt:attr name="android:fillColor">\n'
            f'            <gradient\n'
            f'                android:type="radial"\n'
            f'                android:centerX="54"\n'
            f'                android:centerY="54"\n'
            f'                android:gradientRadius="63">\n'
            f'                <item android:offset="0" android:color="{HAZE}" />\n'
            f'                <item android:offset="0.42" android:color="{HAZE_MID}" />\n'
            f'                <item android:offset="0.78" android:color="{VOID}" />\n'
            f'                <item android:offset="1" android:color="{VOID}" />\n'
            f'            </gradient>\n'
            f'        </aapt:attr>\n'
            f'    </path>\n'
            f'</vector>\n')


def monochrome():
    """Themed icons get tinted flat, so the seams have to carry the shape alone.

    Same size as the foreground, so the two register when a launcher swaps them.
    """
    return _vector(_fill(cube_path(gap=1.2), "#FFFFFFFF"))


def splash():
    """The splash slot masks off the outer third, so the cube runs a little larger."""
    d = cube_path(height=64.0)
    return _vector(_glow(d) + _fill(d), dp=48)


def notification():
    """A 24dp silhouette: bigger, with seams wide enough to survive the status bar."""
    return _vector(_fill(cube_path(height=92.0, gap=2.0), "#FFFFFFFF"), dp=24)


def legacy(round_icon):
    """Pre-O launchers get one flat vector, plate and all."""
    plate = ('M54,4 A50,50 0,1 1,53.9 4 Z' if round_icon else
             'M22,4 H86 A18,18 0,0 1,104 22 V86 A18,18 0,0 1,86 104 H22 '
             'A18,18 0,0 1,4 86 V22 A18,18 0,0 1,22 4 Z')
    d = cube_path(height=54.0)
    body = f'    <path\n        android:fillColor="{VOID}"\n        android:pathData="{plate}" />\n'
    return _vector(body + _glow(d) + _fill(d), dp=48)


def compose():
    """The same artwork for the mark the app draws on its own screens."""
    bands = "\n".join(f"        {w}f to {a}f," for w, a in glow_bands())
    face, haze, haze_mid = FACE.lstrip("#"), HAZE.lstrip("#"), HAZE_MID.lstrip("#")
    return f'''// Generated by tools/gen-icon.py from Marketing/app-icon.jpeg. Do not hand-edit.
package dev.localagent.workstation.ui

/**
 * The app icon, as geometry the app can draw for itself.
 *
 * Everything is in the 108-unit grid Android hands an adaptive icon, so a mark drawn
 * on a Box screen and the icon on the home screen are the same drawing at different
 * scales rather than two things that merely resemble each other.
 */
internal object BoxMarkArt {{
    /** The three faces of the cube, as one path with three subpaths. */
    const val PATH = "{cube_path()}"

    /** The 72-unit window a launcher mask actually shows of that grid. */
    const val WINDOW = 72f
    const val INSET = 18f

    /** A lit face, and the plate behind it, sampled from the artwork. */
    const val FACE = 0xFF{face}
    const val HAZE = 0xFF{haze}
    const val HAZE_MID = 0xFF{haze_mid}

    /**
     * The seam's glow, as (stroke width, alpha) laid down widest first. A vector
     * cannot blur, so the bloom is a stack of round-joined strokes fitted to the
     * artwork's measured falloff.
     */
    val GLOW = listOf(
{bands}
    )
}}
'''


TARGETS = {
    "app/src/main/kotlin/dev/localagent/workstation/ui/BoxMarkArt.kt": compose,
    "app/src/main/res/drawable/ic_box_launcher_foreground.xml": foreground,
    "app/src/main/res/drawable/ic_box_launcher_background.xml": background,
    "app/src/main/res/drawable/ic_box_launcher_monochrome.xml": monochrome,
    "app/src/main/res/drawable/ic_box_splash.xml": splash,
    "app/src/main/res/mipmap-anydpi/ic_launcher.xml": lambda: legacy(False),
    "app/src/main/res/mipmap-anydpi/ic_launcher_round.xml": lambda: legacy(True),
    "runtime-qemu/src/main/res/drawable/ic_box_notification.xml": notification,
}


def main():
    for path, render in TARGETS.items():
        full = os.path.join(ROOT, path)
        with open(full, "w") as f:
            f.write(render())
        print(path)


if __name__ == "__main__":
    main()
