#!/usr/bin/env python3
"""Generate Android string resources from docs/audit/strings.tsv (+ ja_overrides.tsv).

Usage (from repo root): python3 -I scripts/strings/gen_strings.py
Output: androidApp/src/main/res/values{,-ja}/strings.xml  (see SPEC §8.2)
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
TSV = os.path.join(ROOT, "docs", "audit", "strings.tsv")
OVERRIDES = os.path.join(HERE, "ja_overrides.tsv")
RES = os.path.join(ROOT, "androidApp", "src", "main", "res")

# Extra keys not in the Flutter ARB: (name, en, ja, translatable)
EXTRA = [
    ("app_name", "Sonora", "Sonora", False),
    ("language_system", "System default", "システムのデフォルト", True),
    ("language_english", "English", None, False),
    ("language_japanese", "日本語", None, False),
    ("listens_never", "Never played", "未再生", True),
    ("widget_label", "Now playing", "再生中", True),
    ("widget_description", "Shows the current song with playback controls", "再生中の曲を表示し、再生を操作できます", True),
    ("widget_nothing_playing", "Nothing playing", "再生中の曲はありません", True),
    ("widget_tap_to_open", "Tap to open Sonora", "タップしてSonoraを開く", True),
    ("widget_album_art", "Album art", "アルバムアート", True),
    ("widget_play", "Play", "再生", True),
    ("widget_pause", "Pause", "一時停止", True),
    ("widget_previous", "Previous", "前の曲", True),
    ("widget_next", "Next", "次の曲", True),
]


def snake(key):
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", key)
    s = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", "_", s)
    return s.lower()


def parse_placeholders(col):
    out = []
    for part in filter(None, (p.strip() for p in col.split(","))):
        name, typ = part.split(":")
        out.append((name, "d" if typ == "int" else "s"))
    return out


def escape(text, formatted):
    t = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = t.replace("'", "\\'").replace('"', '\\"')
    if formatted:
        t = t.replace("%", "%%")
    if t[:1] in ("@", "?"):
        t = "\\" + t
    if t.startswith(" "):
        t = "\\u0020" + t[1:]
    if t.endswith(" "):
        t = t[:-1] + "\\u0020"
    return t


def render(text, phs):
    """Escape and turn {name} into positional args."""
    formatted = bool(phs)
    t = escape(text, formatted)
    for i, (name, typ) in enumerate(phs, 1):
        t = t.replace("{%s}" % name, "%%%d$%s" % (i, typ))
    if re.search(r"\{\w+\}", t):
        sys.exit("unresolved placeholder: " + text)
    return t


PLURAL_RE = re.compile(r"^\{(\w+), plural, (.*)\}$", re.S)


def parse_plural(text):
    m = PLURAL_RE.match(text)
    if not m:
        return None
    body, cases, i = m.group(2), {}, 0
    while i < len(body):
        j = body.index("{", i)
        sel = body[i:j].strip()
        depth, k = 0, j
        while True:
            if body[k] == "{":
                depth += 1
            elif body[k] == "}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        cases[sel] = body[j + 1:k]
        i = k + 1
    return cases


# The native port's product name is "SonoraKMP" (the Flutter app stays "Sonora").
PRODUCT_RE = re.compile(r"(?<![A-Za-z])Sonora(?![A-Za-z])")


def brand(text):
    return PRODUCT_RE.sub("SonoraKMP", text)


def main():
    rows = []
    with open(TSV, encoding="utf-8") as f:
        header = f.readline()
        assert header.startswith("key\ten\tja")
        for line in f:
            line = line.rstrip("\n")
            if line:
                k, en, ja, ph = (line.split("\t") + [""])[:4]
                rows.append((k, en, ja, ph))
    overrides = {}
    with open(OVERRIDES, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line and not line.startswith("#"):
                k, v = line.split("\t", 1)
                overrides[k] = v
    known = {r[0] for r in rows}
    bad = set(overrides) - known
    if bad:
        sys.exit("overrides for unknown keys: %s" % sorted(bad))

    en_out, ja_out, names = [], [], set()
    n_plurals = 0
    for k, en, ja, ph in rows:
        name = snake(k)
        assert name not in names, name
        names.add(name)
        ja = overrides.get(k, ja)
        phs = parse_placeholders(ph)
        cases = parse_plural(en)
        if cases:
            n_plurals += 1
            one = cases.get("=1", cases.get("one"))
            other = cases["other"]
            one_r = render(one.replace("1", "{%s}" % phs[0][0], 1) if "{" not in one else one, phs)
            en_out.append('    <plurals name="%s">\n        <item quantity="one">%s</item>\n'
                          '        <item quantity="other">%s</item>\n    </plurals>'
                          % (name, one_r, render(other, phs)))
            ja_out.append('    <plurals name="%s">\n        <item quantity="other">%s</item>\n    </plurals>'
                          % (name, render(ja, phs)))
        else:
            # SPEC §8.2 keeps these as plain strings (only 9 ICU plurals exist upstream).
            ign = ' tools:ignore="PluralsCandidate"' if any(t == "d" for _, t in phs) else ""
            en_out.append('    <string name="%s"%s>%s</string>' % (name, ign, render(en, phs)))
            ja_out.append('    <string name="%s">%s</string>' % (name, render(ja, phs)))

    for name, en, ja, tr in EXTRA:
        assert name not in names, name
        names.add(name)
        attr = "" if tr else ' translatable="false"'
        en_out.append('    <string name="%s"%s>%s</string>' % (name, attr, render(en, [])))
        if tr:
            ja_out.append('    <string name="%s">%s</string>' % (name, render(ja, [])))

    head = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by scripts/strings/gen_strings.py. Do not edit by hand. -->\n<resources xmlns:tools="http://schemas.android.com/tools">\n'
    for sub, body in (("values", en_out), ("values-ja", ja_out)):
        d = os.path.join(RES, sub)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "strings.xml"), "w", encoding="utf-8") as f:
            f.write(brand(head + "\n".join(body) + "\n</resources>\n"))
    print("keys from tsv: %d, plurals: %d, extra: %d, ja overrides: %d"
          % (len(rows), n_plurals, len(EXTRA), len(overrides)))


if __name__ == "__main__":
    main()
