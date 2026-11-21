"""Converts doona's icons, duck mark and country flags into Android vector drawables.

Usage: python -I tools/icons.py <doona checkout> <res/drawable dir>
Needs fontTools and brotli (for the flag font). Sources:
- src/ui/icons/*.tsx: Adobe Spectrum 2 icons (Apache-2.0), 20 x 20 grid, geometry unchanged.
- src/logo.svg: doona's duck mark.
- src/ui/assets/TwemojiCountryFlags.woff2: Twemoji flag artwork (CC-BY 4.0), COLRv0 layers.
"""

import re
import sys
from pathlib import Path

# drawable name -> (doona icon, rotation in degrees). The two rotated ones reuse doona's own arrow and chevron.
ICONS = {
    "ic_speed_fast": ("SpeedFast", 0),
    "ic_link": ("Link", 0),
    "ic_share": ("Share", 0),
    "ic_file_text": ("FileText", 0),
    "ic_shuffle": ("Shuffle", 0),
    "ic_filter": ("Filter", 0),
    "ic_download": ("Download", 0),
    "ic_upload": ("Upload", 0),
    "ic_clock": ("Clock", 0),
    "ic_cpu": ("Cpu", 0),
    "ic_data": ("Data", 0),
    "ic_chevron_down": ("ChevronDown", 0),
    "ic_chevron_right": ("ChevronDown", -90),
    "ic_arrow_back": ("ArrowDown", 90),
    "ic_add_circle": ("AddCircle", 0),
    "ic_edit": ("Edit", 0),
    "ic_more_vertical": ("MoreVertical", 0),
    "ic_search": ("Search", 0),
}
FLAGS = ["HK", "JP", "TW", "SG", "US", "KR"]

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'
ATTR = re.compile(r'(\w+)=(?:"([^"]*)"|\{([^}]*)\})')


def tsx_paths(text):
    if re.search(r"<(circle|rect|ellipse|line|polygon|polyline|g)\b", text):
        raise SystemExit("unsupported element")
    for block in re.findall(r"<path\b(.*?)/>", text, re.S):
        yield {k: (a if a else b) for k, a, b in ATTR.findall(block)}


def icon(name, rotation, src):
    out = [HEADER, '<!-- Adobe Spectrum 2 icon from doona src/ui/icons/%s.tsx, Apache-2.0. -->\n' % name]
    out.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
               '    android:width="20dp"\n    android:height="20dp"\n'
               '    android:viewportWidth="20"\n    android:viewportHeight="20">\n')
    indent = "    "
    if rotation:
        out.append('    <group android:pivotX="10" android:pivotY="10" android:rotation="%d">\n' % rotation)
        indent = "        "
    for p in tsx_paths(src.read_text()):
        attrs = ['android:pathData="%s"' % p["d"]]
        if p.get("fill", "currentColor") == "currentColor":
            attrs.append('android:fillColor="#FF000000"')
        if p.get("fillRule") == "evenodd":
            attrs.append('android:fillType="evenOdd"')
        if p.get("stroke") == "currentColor":
            attrs.append('android:strokeColor="#FF000000"')
            attrs.append('android:strokeWidth="%s"' % p.get("strokeWidth", "1"))
            if "strokeLinecap" in p:
                attrs.append('android:strokeLineCap="%s"' % p["strokeLinecap"])
            if "strokeLinejoin" in p:
                attrs.append('android:strokeLineJoin="%s"' % p["strokeLinejoin"])
        sep = "\n" + indent + "    "
        out.append(indent + "<path" + sep + sep.join(attrs) + " />\n")
    if rotation:
        out.append("    </group>\n")
    out.append("</vector>\n")
    return "".join(out)


def logo(src):
    svg = src.read_text()
    vx, vy, vw, vh = (float(v) for v in re.search(r'viewBox="([^"]+)"', svg).group(1).split())
    out = [HEADER, "<!-- doona's duck mark, from doona src/logo.svg. -->\n"]
    out.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
               '    android:width="28dp"\n    android:height="28dp"\n'
               '    android:viewportWidth="%g"\n    android:viewportHeight="%g">\n' % (vw, vh))
    out.append('    <group android:translateX="%g" android:translateY="%g">\n' % (-vx, -vy))
    for block in re.findall(r"<path\b(.*?)/>", svg, re.S):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', block))
        attrs = ['android:pathData="%s"' % " ".join(a["d"].split()), 'android:fillColor="%s"' % a["fill"]]
        if a.get("fill-rule") == "evenodd":
            attrs.append('android:fillType="evenOdd"')
        out.append("        <path\n            " + "\n            ".join(attrs) + " />\n")
    out.append("    </group>\n</vector>\n")
    return "".join(out)


def flags(font_path):
    from fontTools.pens.boundsPen import BoundsPen
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.ttLib import TTFont

    font = TTFont(font_path)
    cmap = font.getBestCmap()
    glyphs = font.getGlyphSet()
    palette = font["CPAL"].palettes[0]
    layers = font["COLR"].ColorLayers
    ligatures = {}
    for lookup in font["GSUB"].table.LookupList.Lookup:
        for sub in lookup.SubTable:
            sub = getattr(sub, "ExtSubTable", sub)
            for first, ligs in getattr(sub, "ligatures", {}).items():
                for lig in ligs:
                    ligatures[(first, *lig.Component)] = lig.LigGlyph
    for code in FLAGS:
        key = tuple(cmap[0x1F1E6 + ord(c) - ord("A")] for c in code)
        glyph = ligatures[key]
        bounds = BoundsPen(glyphs)
        for layer in layers[glyph]:
            glyphs[layer.name].draw(bounds)
        x0, y0, x1, y1 = bounds.bounds
        w, h = x1 - x0, y1 - y0
        out = [HEADER, "<!-- Twemoji flag %s (CC-BY 4.0) from doona src/ui/assets/TwemojiCountryFlags.woff2. -->\n" % code]
        out.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                   '    android:width="%gdp"\n    android:height="12dp"\n'
                   '    android:viewportWidth="%g"\n    android:viewportHeight="%g">\n' % (round(12 * w / h, 1), w, h))
        out.append('    <group android:scaleY="-1" android:translateX="%g" android:translateY="%g">\n' % (-x0, y1))
        for layer in layers[glyph]:
            pen = SVGPathPen(glyphs)
            glyphs[layer.name].draw(pen)
            c = palette[layer.colorID]
            color = "#%02X%02X%02X%02X" % (c.alpha, c.red, c.green, c.blue)
            out.append('        <path\n            android:pathData="%s"\n            android:fillColor="%s" />\n' % (pen.getCommands(), color))
        out.append("    </group>\n</vector>\n")
        yield "flag_" + code.lower(), "".join(out)


def main():
    doona, res = Path(sys.argv[1]), Path(sys.argv[2])
    res.mkdir(parents=True, exist_ok=True)
    for name, (source, rotation) in ICONS.items():
        (res / (name + ".xml")).write_text(icon(source, rotation, doona / "src/ui/icons" / (source + ".tsx")))
    (res / "ic_duck.xml").write_text(logo(doona / "src/logo.svg"))
    for name, text in flags(doona / "src/ui/assets/TwemojiCountryFlags.woff2"):
        (res / (name + ".xml")).write_text(text)


if __name__ == "__main__":
    main()
