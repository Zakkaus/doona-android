"""Writes the country shapes behind the prototype/rv sample's one world map (RvWorldMap: 節點 and 統計).

Usage: python -I tools/rv_world.py <ne_110m_admin_0_countries.geojson> \
    > app/src/debug/kotlin/dev/doona/android/prototype/rv/RvWorld.kt
The input is Natural Earth's 1:110m admin-0 countries (public domain), v5.1.2:
https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_110m_admin_0_countries.geojson
Each country is projected with the Miller cylindrical projection onto a WIDTH-unit-wide plane cut at NORTH and SOUTH
(Antarctica left out), simplified with Douglas-Peucker at TOLERANCE units, rounded to whole units and written as one
string per country: its rings in Google's encoded polyline format, separated by spaces.
Two kinds of ring are left out, so a filled country shows only where it is: a country's overseas parts outside its
METROPOLE box (French Guiana read as a node in South America), and the parts of a country across the antimeridian that
Natural Earth puts at the far west (Russia's Chukotka, Fiji): the map stops at 180 degrees.
"""

import json
import math
import sys

WIDTH = 1000
NORTH = 84.0
SOUTH = -56.0
TOLERANCE = 0.9
MIN_AREA = 2.0

# West, south, east and north in degrees: only the rings whose points all fall inside are kept.
METROPOLE = {"FR": (-10.0, 35.0, 20.0, 60.0)}

# A ring wholly west of WRAP_WEST belongs to a country that also has land east of WRAP_EAST: it crossed the antimeridian.
WRAP_WEST = -160.0
WRAP_EAST = 160.0


def miller(lat):
    return 1.25 * math.log(math.tan(math.pi / 4 + 0.4 * math.radians(lat)))


TOP = miller(NORTH)
SCALE = WIDTH / (2 * math.pi)
HEIGHT = round((TOP - miller(SOUTH)) * SCALE)


def project(lon, lat):
    lat = max(min(lat, NORTH), SOUTH)
    return (lon + 180) / 360 * WIDTH, (TOP - miller(lat)) * SCALE


def simplify(points):
    """Douglas-Peucker: keeps the points that stray more than TOLERANCE from the line between their kept neighbours."""
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        (x1, y1), (x2, y2) = points[first], points[last]
        dx, dy = x2 - x1, y2 - y1
        length = math.hypot(dx, dy)
        far, index = 0.0, None
        for i in range(first + 1, last):
            px, py = points[i]
            d = abs(dy * (px - x1) - dx * (py - y1)) / length if length else math.hypot(px - x1, py - y1)
            if d > far:
                far, index = d, i
        if index is not None and far > TOLERANCE:
            keep[index] = True
            stack += [(first, index), (index, last)]
    return [p for p, k in zip(points, keep) if k]


def area(ring):
    return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]))) / 2


def encode(ring):
    """Google's encoded polyline: each coordinate as a zigzag delta from the one before, in 5-bit groups from '?'."""
    out, last = [], (0, 0)
    for point in ring:
        for value, before in zip(point, last):
            delta = value - before
            v = ~(delta << 1) if delta < 0 else delta << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        last = point
    return "".join(out)


def rings(geometry):
    polygons = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    for polygon in polygons:
        yield from polygon


def kept(code, all_rings):
    """The rings the map draws: none outside the country's METROPOLE box, none wrapped across the antimeridian."""
    east = any(lon > WRAP_EAST for ring in all_rings for lon, _ in ring)
    box = METROPOLE.get(code)
    for ring in all_rings:
        if east and all(lon < WRAP_WEST for lon, _ in ring):
            continue
        if box and not all(box[0] <= lon <= box[2] and box[1] <= lat <= box[3] for lon, lat in ring):
            continue
        yield ring


def country(feature):
    """The country's code and its encoded rings; points before and after simplifying, for the report."""
    props = feature["properties"]
    code = props["ISO_A2_EH"] if props["ISO_A2_EH"] != "-99" else props["ADM0_A3"]
    encoded, before, after = [], 0, 0
    for ring in kept(code, list(rings(feature["geometry"]))):
        points = [project(lon, lat) for lon, lat in ring]
        before += len(points)
        rounded = []
        for x, y in simplify(points)[:-1]:
            p = (round(x), round(y))
            if not rounded or rounded[-1] != p:
                rounded.append(p)
        if len(rounded) >= 3 and area(rounded) >= MIN_AREA:
            after += len(rounded)
            encoded.append(encode(rounded))
    return code, " ".join(encoded), before, after


def main():
    with open(sys.argv[1]) as f:
        features = json.load(f)["features"]
    shapes, before, after = [], 0, 0
    for feature in features:
        code, text, b, a = country(feature)
        before += b
        if code == "AQ" or not text:
            continue
        after += a
        shapes.append((code, text.replace("\\", "\\\\")))
    shapes.sort()
    body = "\n".join(f'        RvCountry("{code}", "{text}"),' for code, text in shapes)
    print(f"{len(shapes)} countries, {before} points, {after} kept", file=sys.stderr)
    print(f"""package dev.doona.android.prototype.rv

// Generated by tools/rv_world.py from Natural Earth 1:110m admin-0 countries
// (ne_110m_admin_0_countries.geojson, v5.1.2), which is in the public domain:
// https://www.naturalearthdata.com/about/terms-of-use/. Regenerate rather than edit.

/** A country of the world map: its ISO 3166-1 alpha-2 code and its rings, encoded polylines separated by spaces. */
internal class RvCountry(val code: String, val rings: String)

/**
 * The world without Antarctica in the Miller projection, on a plane [WIDTH] units wide and [HEIGHT] high that spans
 * 180 degrees west to east and [NORTH] to [SOUTH] degrees of latitude. Coordinates are whole units. France is only
 * metropolitan France, and no land crosses the antimeridian (tools/rv_world.py).
 */
internal object RvWorld {{
    const val WIDTH = {WIDTH}f
    const val HEIGHT = {HEIGHT}f
    const val NORTH = {NORTH:g}f
    const val SOUTH = {SOUTH:g}f

    val countries = listOf(
{body}
    )
}}""")


if __name__ == "__main__":
    main()
