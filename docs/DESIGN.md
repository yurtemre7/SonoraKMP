# Sonora design system — "Sleeve"

Sonora plays music files people own and keep. The visual language comes from
that world: the printed record sleeve, the CD spine on a shelf, the label
sticker. It borrows their **flatness, type and colour** — not their textures.
No skeuomorphic grain, no fake chrome, and **no gradients anywhere**.

It is Material 3 Expressive underneath (`MaterialExpressiveTheme`,
`MotionScheme.expressive()`, expressive components) but never stock M3.

Revision 2 (2026-11-10) replaced "Afterglow" (cream + terracotta, animated
gradient backdrop, gradient placeholders, all-caps eyebrows) after review: those
read as generic generated design.

## 1. Color

Flat colours only. A surface is one colour; tone changes come from stepping
between palette values, never from blending them.

| Token | Hex | Role |
| --- | --- | --- |
| **Fog** | `#E9EBEF` | light background (cool grey, not cream) |
| **Ink** | `#141A2E` | light text; dark-mode deep surfaces |
| **Cobalt** | `#2A44D4` | primary: play, progress, current track, links |
| **Label** | `#F2C230` | sticker yellow: fills only (badges, selected chip, "new version"), always with Ink text |
| **Moss** | `#4F7A5A` | tertiary: success, stats highlights |
| **Night** | `#10142A` | dark background |

| Role | Light | Dark |
| --- | --- | --- |
| background / surface | `#E9EBEF` | `#10142A` |
| surfaceContainerLow / Container / High | `#F3F4F7` / `#E1E4EA` / `#D6DAE2` | `#171C36` / `#1D2341` / `#252C4D` |
| onSurface / onSurfaceVariant | `#141A2E` / `#454C63` | `#E4E7F2` / `#A9AFC6` |
| primary / onPrimary | `#2A44D4` / `#FFFFFF` | `#9DB0FF` / `#0B1A6B` |
| primaryContainer / on | `#DDE2FF` / `#0B1A6B` | `#2234A8` / `#DDE2FF` |
| secondary (Label) / on | `#F2C230` / `#141A2E` | `#F2C230` / `#141A2E` |
| tertiary (Moss) / on | `#4F7A5A` / `#FFFFFF` | `#9CC7A6` / `#12301B` |
| outline / outlineVariant | `#767D93` / `#C3C8D4` | `#7C839C` / `#363D5C` |
| error | `#B3261E` | `#FFB4AB` |

AMOLED ("Pitch black"): dark scheme with background/surface `#000000` and
containers `#0B0D18` / `#11142A` / `#181C36` (navy-tinted, not neutral grey).

### Color sources (parity with the Flutter "Color source" setting)

1. **Sleeve** (default): the hand-tuned roles above. Not generated.
2. **System (Material You)** on Android 12+; hidden below API 31.
3. **Album art**: seed from the current cover (AndroidX Palette, vibrant →
   dominant), scheme via MaterialKolor `PaletteStyle.TonalSpot`. Colour changes
   animate as a solid crossfade (450 ms, emphasized), never a gradient.
4. **Custom accent**: user-picked seed from the swatch picker.

### Contrast

* Text ≥ 4.5:1, large text and icons ≥ 3:1. Label yellow never carries text in
  light mode except Ink on Label (10:1).
* `ContrastTest` checks onPrimary/primary and onSurface/surface for every source
  in light, dark and AMOLED; generated album-art schemes that fail fall back to
  `Contrast.Medium`.
* Text never sits on artwork. Overlays use a flat `surface` at 88% opacity.

## 2. Typography

**One family: Bricolage Grotesque** (variable `opsz`, `wdth`, `wght`;
`res/font/bricolage_grotesque.ttf`, OFL). Its width axis does the work a second
family would: condensed for titles and spines, normal width for reading.
Japanese falls back to the system CJK font (Noto Sans CJK JP).

| Role | Size / line | Weight | Width | Use |
| --- | --- | --- | --- | --- |
| displayLarge | 64/64 | 800 | 75 | **Spine** title (rotated) |
| displaySmall | 40/44 | 750 | 75 | stats figures |
| headlineLarge | 32/38 | 700 | 80 | screen titles |
| headlineMedium | 26/32 | 700 | 80 | album/artist/playlist titles |
| titleLarge | 20/26 | 650 | 90 | sheet and dialog titles |
| titleMedium | 16/22 | 600 | 100 | song title in rows |
| bodyLarge / bodyMedium | 16/24 · 14/20 | 400 | 100 | text, artist lines |
| labelLarge | 14/20 | 600 | 100 | buttons |
| labelMedium | 12/16 | 600 | 100 | chips, counts |

Rules:
* Sentence case everywhere. **No all-caps labels or eyebrows.**
* Times, durations and counts use tabular figures (`fontFeatureSettings = "tnum"`).
* No single highlighted word in a title (no colour/weight accent on one word).
* `opsz` follows the font size; line length ≤ 70 characters on tablets.

## 3. Shape

* Corner scale: 4 / 8 / 12 / 20 / 28; pills only for buttons and chips.
* **Artwork is a sleeve**: square with 8 dp corners (rows: 6 dp). Never round.
* **Typographic sleeves** (missing artwork): a flat block in Cobalt, Ink or Moss
  (picked by title hash), album title set left-aligned at the bottom in
  condensed 800 weight, artist above it in body weight, 12% inset. Like a
  white-label promo pressing. No gradients, no generated patterns.
* Artist avatars: `MaterialShapes.Cookie9Sided` (the only organic shape outside
  the play button), filled flat when no image exists, with the initial.
* Play/pause button: `Circle` (paused) ↔ `Cookie9Sided` (playing) morph on tap,
  spring. It does **not** rotate.

## 4. Motion

* `MotionScheme.expressive()`; motion answers the user (open, expand, reorder,
  confirm) and shows what changed.
* Only two ambient motions, both meaning "this is playing": the wavy seek bar
  (amplitude 0 when paused) and the equalizer bars on the current row.
* Navigation: shared-axis X for push; predictive back scales the outgoing scene
  to 0.94 with 28 dp corners.
* Mini player → Now Playing: shared element on the sleeve and title.
* Reduce motion (animator scale 0): wave and bars freeze, transitions become fades.

## 5. Signature: the Spine

The one bold element. Everything around it stays quiet.

* **Now Playing (portrait)**: the song title runs bottom-to-top along the left
  edge in displayLarge condensed, Ink on Fog (or onSurface), clipped by the
  screen edge if long rather than shrunk; the sleeve sits to its right, the
  controls below. Artist and album sit horizontally under the sleeve.
* **Album / playlist header**: the title as a spine beside the sleeve, track
  count and total time below in tabular figures.
* **Tablet / landscape**: the spine is the divider between the library pane and
  Now Playing.
* Accessibility: the rotated text is decorative for TalkBack; the same title is
  exposed once as a heading.

## 6. Other components

1. **Play button** — the morphing cookie (§3), 56–88 dp.
2. **Seek bar** — `LinearWavyProgressIndicator` track with a draggable thumb;
   elapsed/remaining in tabular figures (h:mm:ss when ≥ 1 h).
3. **Now Playing background** — flat `surfaceContainerLow` tinted 12% toward the
   cover's dominant colour. One colour, no blur. The "Blurred backgrounds"
   setting keeps parity: API 31+ only, blur of the sleeve at 40% behind a flat
   88% surface overlay.
4. **Mini player** — a flat `surfaceContainerHigh` bar docked above the
   navigation bar (not floating), 2 dp Cobalt progress line on its top edge;
   swipe up = open, down = close (with undo), left/right = skip.
5. **Song row** — sleeve thumbnail or track number in tabular figures; the
   playing row swaps the number for equalizer bars and colours the title Cobalt.
   No card per row; rows are separated by space, not dividers.
6. **Section headers** — titleMedium sentence case with a right-aligned count
   ("Albums  42"), only where the count is useful.
7. **Button groups / connected toggles** — repeat, shuffle and the Now Playing
   view switcher (Lyrics / Up next / Related).
8. **Loading** — expressive `LoadingIndicator` for scans and downloads.
9. **Badges** — Label yellow fill with Ink text, used sparingly (new version,
   selection count).

## 6b. Home (revised after the P5-27 identity check)

Side by side with the Flutter app, the first Home kept Flutter's structure (greeting title,
search pill with round buttons, "N songs • duration" line, rows ending in duration + ⋮). Home now
follows the record-collection idea instead:

1. **No section dividers** (tried record-bin divider tabs; removed at the maintainer's request —
   they added noise). Lists run uninterrupted; fast scrolling is a quiet thumb on the right edge
   with a small neutral bubble (surfaceContainerHighest, onSurface text) showing the current
   letter, minute or play band for the active sort.
2. **Rows without ⋮.** Swipe right = play next, swipe left = add to queue (coloured reveal with
   icon, haptic at threshold, snackbar with undo); long-press opens the song action sheet.
   Duration stays at the end in small tabular figures. TalkBack exposes the same actions.
3. **Header.** Condensed screen title (greeting only when a name is set) with the count and
   total time as tabular figures beside it; below it one connected button group:
   Search · Sort · Shuffle. Search expands in place into a field (shared-axis motion); pulling the
   list down also opens it.

## 7. Layout

* Edge-to-edge with inset padding; transparent system bars.
* Left-aligned content; titles align to the same 16 dp (compact) / 24 dp
  (medium+) margin as list text.
* Compact: bottom `ShortNavigationBar`, docked mini player.
* Medium: `NavigationRail`, album grid 3–4 columns.
* Expanded: `WideNavigationRail` + list-detail; Now Playing in the right pane
  with the Spine as the divider.
* Settings and text screens max 720 dp wide.

## 8. Accessibility

* Every icon button has a `contentDescription` from resources.
* 48 dp minimum touch targets.
* Song rows merge semantics ("Title, Artist, 3:42, now playing") and expose
  custom actions (play next, add to queue, favourite).
* At font scale ≥ 1.5 the Spine falls back to a horizontal title and marquee is off.
* Contrast verified by `ContrastTest` (§1).

## 9. Website (`/site`)

Same tokens as CSS custom properties, Bricolage Grotesque only (self-hosted
woff2), light/dark via `prefers-color-scheme` plus a toggle.

* **Hero**: a shelf of spines. A row of vertical spines in flat Cobalt, Ink,
  Moss, Fog and one Label-yellow spine, each carrying a real feature name in
  condensed type ("Gapless", "ReplayGain", "Synced lyrics", …); the tallest
  spine reads "Sonora". Headline beside it: "Play the music you own." Then a
  short line and the Download button. No gradient, no glow; one load moment:
  the spines slide into the shelf once (skipped under reduced motion).
* **Features**: a plain two-column list (name + one sentence), grouped by
  Library / Playback / Yours (backup, privacy, languages). No cards, no icons
  grid, no numbers.
* **Screenshots**: phone frames on a flat Fog/Night band.
* Copy: plain verbs, sentence case, no arrows appended to links, no middle-dot
  meta strings.
