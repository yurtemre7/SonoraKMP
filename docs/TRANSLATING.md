# Translating SonoraKMP

Notes for people and agents who add or edit a language. Read this before touching
`androidApp/src/main/res/values-*/`.

Languages today: English (`values/`, the source), Japanese (`values-ja/`), Turkish (`values-tr/`).

## Voice

- **Native first.** Write what a person of that language would say, not a word-for-word copy of the
  English. Reorder, merge or rephrase when it sounds better. Never ship machine-translation phrasing.
- **Friendly and a little playful**, never silly. The app talks to one person ("you"), warmly:
  Turkish uses informal *sen*, Japanese uses です/ます with a light, kind tone, English stays plain
  and human. Jokes and puns belong in greetings and empty states, not in error or permission text.
- **Clear beats clever.** Errors say what happened and what to do next. Permission and privacy
  text must stay exact: do not soften a guarantee into a joke.
- **Greetings are the most important strings** (`good_morning`, `good_afternoon`, `good_evening`,
  `good_night`, shown as the home title once the user gave a name). Give them character, one distinct
  line per time of day, each containing `%1$s` (the name). Hours: 05-11 morning, 12-16 afternoon,
  17-21 evening, 22-04 night. Keep them at most 32 characters including the placeholder; the
  language test enforces this. Current lines: en "Rise and shine, %1$s" / ja "%1$sさん、おはよう！" /
  tr "Günaydın %1$s, çalalım mı?".
- **Ask when unsure.** If a term has two plausible renderings, a joke may not land, or the English is
  ambiguous, ask the maintainer instead of guessing. Put open questions in the PR description.

## Length: shorter wins

The UI is built for English widths. Buttons, tabs, chips, list subtitles and dialog actions overflow
or ellipsize when a translation runs long.

- Prefer the shorter common word (tr: "Sıra" not "Çalma sırası"; ja: "同期" not "同期する").
- Aim for the English length or less, and never more than about 130% for labels, buttons, tab names,
  chips and toggle titles. Sentences in dialogs and subtitles may be longer but stay tight.
- Abbreviate units the way the language does (tr: dk, sn, sa, g; ja: 分, 秒, 時間).
- Drop filler ("please", "kindly", "be sure to"). Imperatives are fine.

## Terminology

Use one word per concept everywhere. Add new entries here when you decide one.

| Concept | Japanese | Turkish |
|---|---|---|
| song / track | 曲 / トラック | şarkı / parça |
| album, artist | アルバム, アーティスト | albüm, sanatçı |
| playlist | プレイリスト | çalma listesi (short: liste) |
| queue / up next | キュー / 再生待ち | sıra / sıradaki |
| library | ライブラリ | kitaplık |
| favorites | お気に入り | favoriler |
| shuffle / repeat | シャッフル / リピート | karıştır / tekrarla |
| sync (library, device) | 同期 | eşitle |
| sleep timer | スリープタイマー | uyku sayacı |
| lyrics | 歌詞 | sözler |
| backup / restore | バックアップ / 復元 | yedek / geri yükle |
| changelog | 更新履歴 | yenilikler |
| folder | フォルダ (not ディレクトリ) | klasör |
| equalizer | イコライザー | ekolayzır |
| guest (Jam) | ゲスト | misafir |

Keep as-is in every language: the app name **SonoraKMP**, **Jam**, **Material You**, **MFX**,
**Nightcore**, **Lo-Fi**, **ReplayGain**, **M3U**, **Bluetooth**, **Wi-Fi**, **GitHub**,
**Telegram**, file names and extensions (`artist.jpg`, `.lrc`), and keyboard-style tokens.
English is sentence case ("Recently played", not "Recently Played"); do the same elsewhere.

## Mechanics

- Strings live in per-feature files: `res/values/strings_<feature>.xml` is the source and every
  language has the same file names and the same keys. `scripts/strings/gen_strings.py` is the
  legacy generator for the very first file and is out of date: **do not run it**, edit the XML.
- Translate **every** key not marked `translatable="false"`. `StringsParityTest` checks that keys and
  format arguments (`%1$s`, `%2$d`) match English; `LanguageSupportTest` checks the picker wiring, the
  greetings and that nothing was left in English.
- **Keep placeholders intact** and numbered. You may reorder them (`%2$s … %1$s`) when the grammar
  needs it. Never put a suffix directly after a placeholder if the language inflects names (Turkish:
  rephrase so the name stands alone, e.g. `Sil: "%1$s"` rather than `%1$s'ı sil`).
- `%%` is a literal percent sign in strings that take arguments.
- Escape in XML: `\'` for apostrophes and `\"` for quotes (Turkish uses many `'` suffixes), `&amp;`,
  `&lt;`. A string beginning with `@` or `?` needs a leading backslash.
- **Plurals.** Languages without grammatical number after a numeral (Japanese, Turkish) still need the
  Android categories the language defines: Japanese only `other`, Turkish `one` and `other` with the
  same text. English needs `one` and `other`.
- Typography: Turkish uses `…` and curly or straight quotes consistently, `%` before the number
  (`%80`), decimal comma in prose ("1,5x"). Japanese uses full-width `？！` and `…`, no spaces
  around Latin words except where readability needs them.
- Do not translate keys, tool names inside code (`tools:ignore`) or `translatable="false"` entries.

## Adding a language

1. Add `values-<tag>/` with a translation of every `strings*.xml` file in `values/`.
2. Add the language to `AppLocale` in `shared/.../model/Enums.kt` (storage name = BCP-47 tag), to
   `AppLocales.apply/current`, and to `languageLabel` in `LanguageDialog.kt`.
3. Add `settings_language_<name>` (`translatable="false"`, the language's own name) to
   `values/strings_settings.xml`.
4. Add the tag to `localeFilters` in `androidApp/build.gradle.kts`.
5. Run `./gradlew :androidApp:testDebugUnitTest --tests '*l10n*'` and check the longest strings on a
   small phone at 130% font size (tabs, chips, settings rows, the greeting title).
6. Mention the language in `README.md` and `CHANGELOG.md`.

## Reviewing a translation

Read it out loud. If it sounds like a manual, rewrite it. Check the greetings, the onboarding and the
empty states first, then permissions and errors for accuracy, then the rest for consistency with the
terminology table.
