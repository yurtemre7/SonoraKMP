# Sonora design system — "Afterglow"

Sonora's Kotlin port gets its own identity. The Flutter app used Outfit/Inter,
round controls and blurred covers. Afterglow is built around the idea of a
**late-night listening room**: warm ember light on deep ink, soft organic
shapes that breathe with the music, and confident, slightly quirky type.

It is Material 3 Expressive underneath (`MaterialExpressiveTheme`,
`MotionScheme.expressive()`, expressive components), but never stock M3: the
palette, type pairing, shape vocabulary and the signature components below are
Sonora's own.

## 1. Color

### Brand seed and scheme generation

| Token | Hex | Use |
| --- | --- | --- |
| `Ember` (seed / primary key) | `#E5573B` | play button, active track, progress |
| `Dusk` (secondary key) | `#7A6BD6` | chips, selection, secondary actions |
| `Seaglass` (tertiary key) | `#2FA593` | stats highlights, success, lyrics active line |
| `Ink` (neutral key) | `#17131A` | surfaces (dark), text (light) |
| `Paper` (neutral light) | `#FBF6F1` | background (light) |

Schemes are generated with MaterialKolor (`PaletteStyle.Expressive`,
`Contrast.Default`) from the Ember seed, with secondary/tertiary/neutral keys
pinned to the values above. This gives full tonal palettes for light, dark and
the AMOLED variant while keeping the brand hue recognisable.

| Role | Light | Dark |
| --- | --- | --- |
| background / surface | Paper `#FBF6F1` | Ink `#17131A` |
| surfaceContainerLow / High | warm `#F5EDE6` / `#ECE1D8` | `#201B23` / `#2C2630` |
| primary / onPrimary | `#A83A22` / `#FFFFFF` | `#FFB4A1` / `#5F1404` |
| primaryContainer | `#FFDAD1` | `#86240F` |
| secondary | `#5C4DB5` | `#C8BFFF` |
| tertiary | `#006B5E` | `#5ADBC4` |
| outline | `#85736E` | `#A08D88` |

AMOLED ("Pitch black" setting): dark scheme with `background`, `surface` and
all `surfaceContainer*` roles forced to `#000000`/`#0A0A0A`/`#121212`.

### Color sources (parity with Flutter "Color source" setting)

1. **Afterglow** (default): the brand scheme above.
2. **System (Material You)**: `dynamicLight/DarkColorScheme` on Android 12+;
   hidden below API 31.
3. **Album art**: seed extracted from the current cover with AndroidX Palette
   (vibrant → dominant fallback), scheme generated with MaterialKolor
   `PaletteStyle.Expressive`; transitions animate with `animateColorAsState`
   (450 ms, emphasized easing) so covers "bleed" into the UI.
4. **Custom accent**: user-picked seed from the accent grid (same swatch list as
   Flutter plus the brand seed).

### Contrast rules

* Body text on any surface: at least 4.5:1. Large titles and icons: 3:1.
* `onPrimary` on `primary` checked for all 4 sources in a unit test
  (`ContrastTest`) using WCAG relative luminance; generated album-art schemes
  that fail fall back to `Contrast.Medium`.
* Never place text directly on artwork: scrims use `surface` at 72% with a
  vertical gradient.

## 2. Typography

| Family | File | Role |
| --- | --- | --- |
| **Bricolage Grotesque** (variable: opsz, wdth, wght) | `res/font/bricolage_grotesque.ttf` | display, headline, title (the "voice") |
| **Figtree** (variable: wght) | `res/font/figtree.ttf` | body, label, UI text |

Both are OFL (licenses in `docs/licenses`). Japanese falls back to the system
CJK font (Noto Sans CJK JP), which pairs well with Figtree's geometry.

Scale (Material roles → Afterglow):

| Role | Font | Size / line | Weight | Notes |
| --- | --- | --- | --- | --- |
| displayLarge | Bricolage | 57/64 | 800, wdth 90 | Now Playing title (expanded) |
| displaySmall | Bricolage | 36/44 | 700 | Stats hero numbers |
| headlineLarge | Bricolage | 32/40 | 700 | Screen titles (large top bar) |
| headlineMedium | Bricolage | 28/36 | 700 | Detail screen titles |
| titleLarge | Bricolage | 22/28 | 650 | Collapsed top bar, sheet titles |
| titleMedium | Figtree | 16/24 | 600 | Song title in lists |
| bodyLarge / Medium | Figtree | 16/24, 14/20 | 400 | Text |
| labelLarge | Figtree | 14/20 | 600 | Buttons |
| labelSmall | Figtree | 11/16 | 600, +0.6 tracking, uppercase | Section eyebrows ("UP NEXT") |

Emphasized variants (`*Emphasized` in M3 Expressive) use +100 weight and
`wdth 85` for a condensed, punchy look on active items.

## 3. Shape language

* **Corner scale**: extraSmall 6, small 10, medium 16, large 24, extraLarge 32,
  full pills for buttons and chips.
* **Artwork** uses a squircle-ish `RoundedCornerShape(22%)`, never a hard
  square or a circle. Artist avatars use `MaterialShapes.Cookie9Sided`.
* **Morphing shapes** (`androidx.graphics.shapes` via M3 `MaterialShapes`):
  * Play/pause button: `Circle` (paused) ↔ `Cookie9Sided` (playing), morphed with
    a spring; the cookie rotates slowly (1 turn / 12 s) while audio plays.
  * Empty states use a large `SoftBurst` / `Clover4Leaf` container with an icon.
  * Placeholder art: `Pentagon`/`Sunny` filled with a tonal gradient derived
    from the song title hash, so missing covers still look intentional.
* Pressed states shrink corner radius (button shape morphing from M3 Expressive
  `ButtonDefaults.shapes()`).

## 4. Motion

* `MotionScheme.expressive()` everywhere (spatial springs overshoot slightly,
  effects springs are critically damped).
* Screen transitions (Navigation 3): shared-axis X for push, fade-through for
  tab switches; predictive back drives the back animation (scale to 0.92 +
  corner radius 32 on the outgoing scene).
* Mini player → Now Playing: shared element (`SharedTransitionLayout`) on the
  artwork and title; the sheet uses predictive back to collapse.
* List items: `animateItem()` for reorders/removals.
* Reduce motion: if the system "Remove animations" is on
  (`ANIMATOR_DURATION_SCALE == 0`), the cookie rotation and visualizer stop.

## 5. Signature components

1. **Pulse button** — the morphing, slowly rotating play/pause cookie with a
   48–96 dp size range. Used in Now Playing and the mini player.
2. **Ember wave** — `LinearWavyProgressIndicator` as the seek bar track while
   playing (amplitude 0 when paused), with a draggable thumb and
   elapsed/remaining labels in tabular figures.
3. **Afterglow backdrop** — instead of a blurred cover (Flutter), the Now
   Playing background is a slow, animated radial gradient mesh built from
   the cover's 3 palette swatches. Cheap on GPU, works on API 24 without
   `RenderEffect`. A "Blurred backgrounds" setting switches to real blur on
   API 31+ for parity.
4. **Floating mini player** — a pill-shaped `surfaceContainerHigh` card that
   floats 8 dp above the navigation bar, with a progress hairline along its
   bottom edge, swipe gestures (up = open, down = close, left/right = skip).
5. **Track chips** — list rows show a tonal track-number chip; the playing row
   swaps it for animated equalizer bars and uses titleMedium emphasized.
6. **Button groups** — transport and filter rows use M3 Expressive
   `ButtonGroup` / connected toggle buttons (repeat, shuffle, lyrics/queue/related
   tabs in Now Playing).
7. **Loading** — the expressive `LoadingIndicator` (shape-morphing) replaces
   spinners for scans, update downloads and startup.

## 6. Layout and adaptivity

* Edge-to-edge with `WindowInsets` padding; transparent system bars.
* Window size classes:
  * **Compact**: bottom `ShortNavigationBar`, floating mini player.
  * **Medium**: `NavigationRail`, two-column album grid → 3–4 columns.
  * **Expanded**: `WideNavigationRail` + list-detail (library list left, detail
    or Now Playing right).
* Max content width 840 dp for settings and text-heavy screens.

## 7. Accessibility

* Every icon button has a `contentDescription` from string resources.
* Minimum touch target 48 dp (`minimumInteractiveComponentSize`).
* Song rows merge semantics: "Title, Artist, 3:42, now playing".
* Custom actions on rows for TalkBack (play next, add to queue, favorite) so
  long-press menus are reachable.
* Font scaling to 200% must not clip titles (marquee only when not scaled).
* Contrast verified by `ContrastTest` (see §1).

## 8. Website

The landing page in `/site` uses the same palette tokens as CSS custom
properties, the same two fonts (self-hosted woff2), the Pulse button motif as
the hero illustration, and light/dark via `prefers-color-scheme` plus a toggle.
