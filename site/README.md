# Sonora website

Static landing and download page, deployed to GitHub Pages by
`.github/workflows/pages.yml` on every push to `main` that touches `site/**`.
Plain HTML, CSS and vanilla JS: no framework, no build step.

| File | Purpose |
| --- | --- |
| `index.html` | Markup. English copy is inline so the page works without JS. |
| `styles.css` | Sleeve tokens (docs/DESIGN.md §1, §9) as flat CSS custom properties, light/dark, layout. No gradients, glows or blur. |
| `app.js` | EN/JA dictionary, theme toggle, latest-release lookup, screenshot fallback. |
| `favicon.svg` | Flat Cobalt sleeve with two spines (Fog, Label). |
| `assets/fonts/bricolage-grotesque-latin.woff2` | Bricolage Grotesque, the only family; Latin subset (see below). |
| `assets/screenshots/` | Phone screenshots (flat typographic-sleeve SVG placeholders for now). |

## Layout

Hero: a shelf of decorative (`aria-hidden`) spines carrying real feature names in
condensed type (`font-stretch: 75%`); the tallest reads "Sonora". They slide in
once on load; `prefers-reduced-motion` turns that off. The `h1` is the headline
beside them. Features are a grouped two-column list (Library / Playback / Yours);
screenshots sit in phone frames on a flat band. Copy is sentence case, with no
all-caps labels, no middle-dot meta strings and no arrows on links.

## Preview locally

```sh
cd site && python3 -m http.server 8000   # then open http://localhost:8000
```

## Screenshots

The gallery loads `assets/screenshots/{home,now-playing,library,settings}.png`.
If a PNG is missing, the `<img onerror>` handler swaps in the `.svg` placeholder
with the same name. To ship real screenshots, drop the four PNGs into
`assets/screenshots/` with exactly those names; no code change is needed. Use
portrait phone captures (roughly 9:19.5, e.g. 1080×2340, the frame crops with
`object-fit: cover`), compress them (e.g. `oxipng`/`pngquant`, ideally < 200 KB
each), and delete the placeholder SVGs once all four PNGs exist. If you change
what a screen shows, update the matching `alt*` strings in `app.js` and the
`alt` attributes in `index.html`.

## Download button

`app.js` calls `https://api.github.com/repos/yurtemre7/SonoraKMP/releases/latest`,
picks the asset matching `/^sonora-.*\.apk$/` (preferring a universal build if
several exist), shows version, size and date, and links the matching `.sha256`.
The response is cached in `sessionStorage` for 10 minutes. Without JS, or when
the API fails or is rate limited, both buttons keep their static href
`https://github.com/yurtemre7/SonoraKMP/releases/latest`.

## Languages

All copy lives in the `I18N` dictionary in `app.js` (`en`, `ja`). Elements use
`data-i18n` (text), `data-i18n-html` (trusted HTML from the dictionary only)
and `data-i18n-attr="attr:key"`. The default comes from `navigator.languages`;
a choice is stored in `localStorage` (`sonora-lang`) and sets `<html lang>`.

### Japanese phrases for the maintainer to review

- `heroTitle` 手元の音楽を、そのまま聴く。 ("Play the music you own.")
- `gYours` あなたのもの (group heading "Yours")
- `featTitle` できること / `scrTitle` アプリの画面
- `f12t` 好きな色で, `f17t` ギャップレス再生
- `heroLede` "no analytics" is rendered as 利用データの収集もありません.
- `dlReq` Android 7.0 以降に対応しています (minSdk 24).

## Fonts

The woff2 was generated from `androidApp/src/main/res/font/bricolage_grotesque.ttf`
with fontTools (`pip install fonttools brotli`), keeping `wdth` 75–100 (condensed
spines and titles) and `wght` 400–800:

```sh
python -m fontTools.varLib.instancer bricolage_grotesque.ttf opsz=48 wdth=75:100 wght=400:800 -o brico.ttf
U="U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+2000-206F,U+2074,U+20AC,U+2122,U+2190-2193,U+2212,U+2215"
pyftsubset brico.ttf --unicodes="$U" --flavor=woff2 --layout-features='kern,liga,tnum,lnum' --output-file=assets/fonts/bricolage-grotesque-latin.woff2
```

Japanese text falls back to the system CJK font (Hiragino / Noto Sans JP).
The font is OFL-licensed.

## Deploying

One-time setup: repository **Settings → Pages → Source: GitHub Actions**.
After that the workflow deploys automatically; it can also be run by hand from
the Actions tab (`workflow_dispatch`).
