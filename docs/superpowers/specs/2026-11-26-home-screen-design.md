# Home screen — design

## Intent
The Songs tab header is cramped (the greeting truncates). The user wants a proper **Home** screen: tiles for
Songs / Albums / Artists / Playlists, "continue where you left off", and recommendations based on listening
history. The existing library (four tabs, search, sort) stays as the second view.

## Goals
- Two views in the library root: **Home** and **Library** (today's tabs). Vertical relationship: Home on top.
- Home content (top to bottom): greeting + settings/stats actions, **Continue** card, four **tiles**,
  **Recommended for you** row, **Weekly Mix** row, **Recently played** row. Rows scroll horizontally.
- Reach Library by tapping a tile (opens that tab), the Library toggle in the Home header, a bottom-bar tab,
  or swiping up past the end of Home. Vertical slide animation; instant with reduce motion.
- Return to Home via a Home icon in the Library header or system Back (Back from Library goes to Home first when
  Home is the start view or was visited; exits the app only from Home).
- Start-view setting: **Home / Songs / Albums / Artists / Playlists**, default **Songs** (unchanged behaviour).
- Fix the Library header: greeting moves to Home; Library header keeps title + count + actions without truncating.

## Non-goals / anti-goals
- No horizontal pager, no fifth bottom-bar item, no new DB tables, no network recommendations.
- Do not break pull-to-search, sort sheet, tab state saving, or old backups (Flutter `default_start_page` 0..3).

## Constraints
- CI is the only verifier (no local Gradle). Strings need en, ja, tr (`docs/TRANSLATING.md`); never run
  `scripts/strings/gen_strings.py`.
- Bottom bar keeps four items; on Home none is highlighted, tapping one opens Library on that tab.
- Back-stack model: `Library` root key; tab is state in `SonoraAppShell` (`rootTab`).

## Approaches considered
1. **Vertical swap + button/gestures (chosen).** Home and Library are two states of the root; animated vertical
   transition; swipe up past Home's end, buttons, tiles. No gesture conflicts with list scroll / pull-to-search.
2. Real vertical pager with swipe both ways — conflicts with pull-down-to-search; rejected.
3. Horizontal 2-page pager — user preferred vertical; rejected.
4. Home as root with Library pushed — extra back step, no swipe; rejected.

## Design
**State.** `rootView: LibraryRoot { Home, Library }` saved in `SonoraAppShell` beside `rootTab`. Initial value from
settings: `startOnHome` → Home, else Library with `defaultStartPage` tab.

**Setting (my call).** Keep `defaultStartPage` (0..3, backup-compatible) and add Boolean `startOnHome`
(DataStore key `start_on_home`, default false, KMP-only backup key like `smartShuffle`). The settings row becomes a
five-option chooser: Home + four tabs. Selecting Home sets `startOnHome=true`; selecting a tab sets it false and
writes the index.

**Recommendations (pure, in shared, unit-tested).** `Recommendations` object in `shared/library`:
- `continueListening(...)` uses the existing saved playback state (`PlaybackStateStore.load`) for the Continue
  card (song + position progress); tap resumes via the same path the player restore uses; hidden when none.
- `recommended(songs, stats, nowMs, limit)`: score = `WeeklyMix.likedScore` of the user's top artists/albums
  applied to **unplayed or lightly-played songs by those artists/albums**, plus favourite-artist boost; excludes
  songs played in the last N hours; deterministic tie-break (title/id). Falls back to recently added when there is
  no history.
- Weekly Mix and Recently played reuse `WeeklyMixRepository` and the history source of the `recent` screen.

**UI.** New `ui/screens/home/HomeDashboard.kt` (LazyColumn with LazyRows), `HomeTiles.kt`, `ContinueCard.kt`.
`HomeRoute` hosts `AnimatedContent` (vertical slide, `LocalReduceMotion`) between dashboard and the existing
`HomeScreen`. Swipe-up: nested-scroll connection at the end of the dashboard triggers the switch once per gesture
(haptic like pull-to-search). Library header gets a Home icon button; dashboard header gets a Library button.
`HomeViewModel` exposes the dashboard state (continue, recommended, weekly, recent) as a StateFlow.

**Back.** `BackHandler`: Library view with Home available and (start view is Home or user came from Home) →
go Home; existing rule 6 (tab ≠ default → default tab) stays for the library.

**Tests.** `RecommendationsTest` (scoring, fallback, exclusions, determinism), settings round-trip for
`startOnHome` in `SettingsTest`, backup whitelist test line. UI is verified by CI compile/lint.

**Left to builder.** Exact spacing/typography, tile icons/colours, empty-state copy (keep short), animation
tuning, and the greeting copy reuse (`greetingRes`).
