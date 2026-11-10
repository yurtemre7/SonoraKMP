package de.yurtemre.sonora.shared.backup

/**
 * A backup as written by Flutter Sonora 1.28.1 (`JsonEncoder.withIndent('  ')` of `buildBackup`):
 * Dart microsecond `createdAt`, doubles as `1.0`, int ARGB colors, StringList gains, legacy theme/MFX keys,
 * null descriptions and dates, stats in StatsService key order.
 */
internal const val FLUTTER_1_28_1_BACKUP = """{
  "format": "sonora-backup",
  "version": 1,
  "createdAt": "2026-11-08T19:42:07.123456Z",
  "appVersion": "1.28.1+128",
  "songs": [
    {
      "id": 1000012,
      "path": "/storage/emulated/0/Music/Daft Punk/Discovery/01 One More Time.mp3",
      "title": "One More Time",
      "artist": "Daft Punk",
      "album": "Discovery",
      "durationMs": 320357
    },
    {
      "id": 1000013,
      "path": "/storage/emulated/0/Music/Daft Punk/Discovery/02 Aerodynamic.mp3",
      "title": "Aerodynamic",
      "artist": "Daft Punk",
      "album": "Discovery",
      "durationMs": 212004
    },
    {
      "id": 1000420,
      "path": "/storage/emulated/0/Music/宇多田ヒカル/First Love/03 First Love.flac",
      "title": "First Love",
      "artist": "宇多田ヒカル",
      "album": "First Love",
      "durationMs": 259733
    },
    {
      "id": 1000777,
      "path": "/storage/emulated/0/Music/Unknown/track07.mp3",
      "title": "track07",
      "artist": "<unknown>",
      "album": "Music",
      "durationMs": 184000
    }
  ],
  "settings": {
    "theme_mode": "dark",
    "use_dynamic_theme": false,
    "use_material_you": true,
    "amoled_dark": true,
    "blurred_backgrounds": false,
    "dynamic_theme_color": 4286336511,
    "default_theme_color": 4294198070,
    "now_playing_style": "vinyl",
    "show_visualizer": false,
    "immersive_mode": false,
    "prefer_local_artist_images": true,
    "app_locale": "ja",
    "default_start_page": 1,
    "user_name": "Emre",
    "use_greeting_title": true,
    "sleep_timer_default_minutes": 30,
    "sleep_timer_fade_out_seconds": 10,
    "sleep_timer_finish_song": true,
    "song_sort_by": "plays",
    "song_sort_ascending": false,
    "album_sort_by": "artist",
    "album_sort_ascending": true,
    "artist_sort_by": "name",
    "artist_sort_ascending": true,
    "playlist_sort_by": "name",
    "playlist_sort_ascending": true,
    "favorites_sort_by": "date",
    "favorites_sort_ascending": false,
    "song_activity_view": "mostPlayed",
    "sort_by": "title",
    "sort_ascending": true,
    "keep_playing_on_close": false,
    "restore_last_played_song": true,
    "pause_on_duck": false,
    "replay_gain_mode": "track",
    "pause_on_disconnect": true,
    "resume_on_connect": false,
    "filter_title_features": true,
    "filter_title_artist": false,
    "auto_check_updates": true,
    "volume": 0.85,
    "speed": 1.0,
    "mfx_preset": 0.85,
    "mfx_isSlowed": true,
    "mfx_isSuperSlowed": false,
    "mfx_isSpedUp": false,
    "mfx_isSuperSpedUp": false,
    "mfx_isNightcore": false,
    "mfx_isReverbEnabled": true,
    "mfx_isLofiEnabled": false,
    "mfx_isBassBoostEnabled": false,
    "mfx_isCustomEqEnabled": true,
    "mfx_bluetoothMfxPatch": false,
    "mfx_customEqGains": [
      "0.0",
      "1.5",
      "-2.0",
      "0.5",
      "3.0"
    ],
    "eq_custom_gains": [
      "0.0",
      "0.0",
      "0.0",
      "0.0",
      "0.0"
    ],
    "eq_enabled": false
  },
  "playlists": [
    {
      "id": "1730912345678",
      "name": "Late Night",
      "description": null,
      "songIds": [
        1000012,
        1000420,
        1000013
      ]
    },
    {
      "id": "1731000000001",
      "name": "Empty",
      "description": "nothing here yet",
      "songIds": []
    }
  ],
  "favorites": {
    "songs": [
      {
        "id": 1000420,
        "favoriteDateMs": 1730999999000
      },
      {
        "id": 1000777,
        "favoriteDateMs": null
      }
    ],
    "albums": {
      "discovery|||daft punk": 1730000000000
    },
    "artists": {
      "宇多田ヒカル": 1730500000000
    }
  },
  "stats": {
    "complete_song_listens": 57,
    "first_played_song_id": 1000012,
    "first_played_date": "2026-01-03",
    "song_play_counts": {
      "1000012": 31,
      "1000420": 12,
      "1000013": 4
    },
    "playlist_play_counts": {
      "1730912345678": 9
    },
    "weekly_play_counts": {
      "0": 5,
      "1": 8,
      "5": 20
    },
    "shuffle_session_starts": 6,
    "song_skip_counts": {
      "1000013": 3
    },
    "song_restart_counts": {},
    "song_last_played_at_ms": {
      "1000012": 1731080000000,
      "1000420": 1731070000000
    }
  }
}"""
