# Title style, update dialog, markdown renderer: design

Approved in conversation (small change, one PR).

## Why
- Long song titles are cut off along the Spine, so you never see the end of them. The Spine is the app's signature, so
  it stays; the user wants options, switchable in settings with previews, also for Vinyl and Minimalist.
- The update sheet kept resizing and flickering, and the changelog screen had the same flash. Both rendered markdown
  with a library that rebuilt its whole tree on recomposition.

## Goals
- `TitleStyle` setting (Marquee, Fit, Two columns, Spine + caption, Classic), default Spine + caption, applied by the
  shared `Spine` in every Now Playing style; settings picker with live scaled previews drawn by the real components.
- Update UI as an alert dialog: scrolling notes, status area with reserved height, Later / Install.
- One markdown renderer for the update notes and the changelog that cannot rebuild on recomposition.

## Non-goals
- Backup/restore of the title style (the Flutter-parity whitelist stays exact). Album/playlist header spines.
- Full CommonMark (tables, images, HTML): release notes and the changelog do not use them.

## Approaches
- Marquee / fit / wrap / caption / classic title styles: all five kept as options (user's pick); marquee runs once per
  track (loops only in the settings preview).
- Markdown: (a) keep the library and cache its parsed state, (b) a small parser to immutable blocks plus plain
  `Text`. Picked (b): (a) was tried twice and the library still re-laid out its tree on every recomposition.
- Update UI: dialog with a fixed-height status area instead of a bottom sheet that resizes with its content.

## Calls made
- Hybrid is the default for everyone (it is today's look plus a caption only when the title is cut).
- Hybrid decides "cut" with a text measurer before layout (no layout jump); Marquee falls back to Hybrid with reduce
  motion or font scale ≥ 1.3; Classic reuses the horizontal title path.
