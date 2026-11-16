# Feature ideas

Suggestions collected for what users expect from a local music player. Only the
ones marked **Planned** are being built and **Done** ones are merged; the rest is a backlog, each
to be picked up on its own branch (`feat/<name>`) with its own pull request.

Status: **Planned** · **Done** · Idea (not requested yet)

## Library and browsing

| Idea | Status | Notes |
| --- | --- | --- |
| Embedded lyrics (ID3 `USLT`, Vorbis `LYRICS`) as a fallback to `.lrc`/`.txt` sidecars | Done | #20 |
| Smart lists: recently added, never played, most played | Done | #22 |
| Folder browsing (file-tree view next to Songs/Albums/Artists) | Idea | |
| Genre and year browsing | Idea | `genre` and `year` are already on `Song` |
| Exclude folders and a minimum track length | Idea | keeps voice memos and ringtones out |
| Per-song ignore/hide | Idea | |
| Search history / recent searches | Idea | |

## Playback and queue

| Idea | Status | Notes |
| --- | --- | --- |
| Save queue as playlist | Done | #21 |
| Crossfade | Idea | gapless is already supported |
| Skip silence | Idea | Media3 supports it |
| Remember position for long files (audiobooks, podcasts) | Idea | |
| Sleep timer by number of songs (2, 3, 5, 10) | Done | #23 |
| Sleep timer until the end of the album | Idea | "finish this song" and song counts exist |
| Android Auto / media-browser service | Idea | |

## Quality of life

| Idea | Status | Notes |
| --- | --- | --- |
| Swipe actions on song rows (play next, add to queue) | Idea | |
| Queue undo covering clear-queue | Idea | `insertAt` already undoes single removals |
| Share a song's file | Idea | |
| Edit tags and lyrics in the app | Idea | |
| Notification / lock-screen custom actions (favourite, repeat) | Idea | |

## Stats and privacy

| Idea | Status | Notes |
| --- | --- | --- |
| Listening streak and year in review | Idea | needs per-day counts, so a Room migration |
| Export listening history to JSON/CSV (local only) | Idea | |
| Backup reminder | Idea | |

## Bugs to audit

Reviewed so far: queue, lyrics, M3U, search, stats tracker. Not yet audited:
playback service, device sync, updater.
