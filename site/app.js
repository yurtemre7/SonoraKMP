/* Sonora landing page: language, theme and latest-release download link. No dependencies. */
(function () {
  'use strict';

  var REPO = 'yurtemre7/SonoraKMP';
  var API = 'https://api.github.com/repos/' + REPO + '/releases/latest';
  var FALLBACK = 'https://github.com/' + REPO + '/releases/latest';
  var APK_RE = /^sonora-.*\.apk$/i;
  var CACHE_KEY = 'sonora-release-v1';
  var CACHE_TTL = 10 * 60 * 1000;

  var I18N = {
    en: {
      title: "Sonora: play the music you own",
      metaDescription: "Sonora is a music player for Android that plays the files on your phone. No account, no analytics, no uploads.",
      skip: "Skip to content",
      navLabel: "Main",
      navFeatures: "Features",
      navScreens: "Screens",
      navDownload: "Download",
      langLabel: "Language",
      themeLabel: "Dark theme",
      heroTitle: "Play the music you own.",
      heroLede: "Sonora is a music player for Android that plays the files on your phone. No account, no analytics, no uploads.",
      download: "Download for Android",
      github: "Source on GitHub",
      releaseFallback: "Free and open source under the MIT License.",
      featTitle: "What it does",
      gLib: "Library",
      gPlay: "Playback",
      gYours: "Yours",
      f1t: "Local library and folder sync",
      f1d: "Point Sonora at your music folders. Files are never copied, and re-scans skip anything unchanged.",
      f8t: "Playlists and M3U",
      f8d: "Drag to reorder, swipe to remove, and import or export standard M3U files.",
      f9t: "Favourites and history",
      f9d: "Heart what you love and find what you played last night in one tap.",
      f10t: "Listening stats",
      f10d: "Total time, top songs, artists and albums and your busiest day, all worked out on your phone.",
      f2t: "Background playback",
      f2d: "Keeps playing with the screen off, with full controls in the notification and on the lock screen.",
      f17t: "Gapless",
      f17d: "Live albums and DJ mixes play straight through, with no gap between tracks.",
      f5t: "ReplayGain",
      f5d: "Track or album levelling, so loud and quiet records sit at the same volume.",
      f6t: "Synced lyrics",
      f6d: "Reads .lrc and .txt files next to your songs. Lines follow along, and a tap jumps to that moment.",
      f4t: "Equalizer, speed and pitch",
      f4d: "A multi-band EQ with presets, plus one-tap moods from Super Slowed to Nightcore.",
      f7t: "Sleep timer",
      f7d: "Pick a preset or your own time, or stop after this song, with a gentle fade at the end.",
      f13t: "Home screen widget",
      f13d: "See what’s playing and control it without opening the app.",
      f16t: "Private by design",
      f16d: "No account, no analytics, no uploads. Sonora only talks to GitHub, for the changelog and updates.",
      f11t: "Backup and restore",
      f11d: "Move playlists, favourites, stats and settings to a new phone with one JSON file.",
      f14t: "English and Japanese",
      f14d: "Follows your system language, or pick one in Settings.",
      f12t: "Your colours",
      f12d: "Sleeve by default, or Material You, colours from the album art, your own accent and pitch black.",
      f15t: "Updates from GitHub",
      f15d: "Sonora checks GitHub releases and installs the right build for your device. No store needed.",
      scrTitle: "How it looks",
      altHome: "Sonora home screen with recently added albums and the mini player",
      altNow: "Now Playing screen with the song title running up the left edge beside the album sleeve",
      altLib: "Library screen showing an album grid with filter chips",
      altSet: "Settings screen with colour, theme and playback options",
      capHome: "Home",
      capNow: "Now Playing",
      capLib: "Library",
      capSet: "Settings",
      dlTitle: "Download",
      dlBody: "Download the APK from GitHub and open it on your phone. Android may ask you once to allow installs from your browser. After that, Sonora updates itself from inside the app.",
      downloadApk: "Download APK",
      allReleases: "All releases",
      dlReq: "Needs Android 7.0 or newer.",
      footLicense: "Sonora is free and open-source software, released under the <a href=\"https://github.com/yurtemre7/SonoraKMP/blob/main/LICENSE\">MIT License</a>.",
      footSource: "Source code",
      footReleases: "Releases",
      footIssues: "Report an issue",
      footPrivacy: "This site uses no cookies and no tracking. It only asks GitHub for the latest release.",
      relVersion: "Version {v}",
      relSize: "{size}",
      relDate: "released {date}",
      relJoin: ", ",
      relEnd: ".",
      relDetail: "{file}, {size}.",
      relSha: "SHA-256 checksum",
      relError: "Couldn’t reach GitHub just now. The button still opens the latest release page."
    },
    ja: {
      title: "Sonora：手元の音楽を、そのまま聴く",
      metaDescription: "Sonora は、スマートフォンの中の音楽ファイルを再生する Android 向けの音楽プレーヤーです。アカウント不要、トラッキングなし、アップロードなし。",
      skip: "本文へスキップ",
      navLabel: "メイン",
      navFeatures: "機能",
      navScreens: "画面",
      navDownload: "ダウンロード",
      langLabel: "言語",
      themeLabel: "ダークテーマ",
      heroTitle: "手元の音楽を、そのまま聴く。",
      heroLede: "Sonora は、スマートフォンの中の音楽ファイルを再生する Android 向けプレーヤーです。アカウント登録も、利用データの収集も、アップロードもありません。",
      download: "Android 版をダウンロード",
      github: "GitHub でソースを見る",
      releaseFallback: "MIT ライセンスの無料オープンソースです。",
      featTitle: "できること",
      gLib: "ライブラリ",
      gPlay: "再生",
      gYours: "あなたのデータ",
      f1t: "ローカルライブラリとフォルダ同期",
      f1d: "音楽フォルダを指定するだけ。ファイルがコピーされることはなく、再スキャンでは変更のないファイルを飛ばします。",
      f8t: "プレイリストと M3U",
      f8d: "ドラッグで並べ替え、スワイプで削除。一般的な M3U ファイルの読み込みと書き出しにも対応しています。",
      f9t: "お気に入りと再生履歴",
      f9d: "好きな曲にはハートを。昨夜聴いたあの曲も、履歴からすぐに見つかります。",
      f10t: "再生統計",
      f10d: "合計再生時間、よく聴く曲・アーティスト・アルバム、いちばん聴いた日まで。集計はすべて端末の中で行います。",
      f2t: "バックグラウンド再生",
      f2d: "画面を消しても再生はそのまま。通知やロック画面からすべて操作できます。",
      f17t: "ギャップレス再生",
      f17d: "ライブ盤や DJ ミックスも、曲間に途切れなくそのまま流れます。",
      f5t: "ReplayGain",
      f5d: "トラック単位・アルバム単位で音量をそろえるので、曲ごとの音量差を気にせず聴けます。",
      f6t: "歌詞の同期表示",
      f6d: "曲と同じフォルダにある .lrc / .txt ファイルを読み込みます。再生に合わせて歌詞が進み、行をタップするとその位置へ移動します。",
      f4t: "イコライザー・速度・ピッチ",
      f4d: "プリセット付きのマルチバンドイコライザーに加えて、Super Slowed から Nightcore までワンタップで切り替えられます。",
      f7t: "スリープタイマー",
      f7d: "プリセットや好きな時間で設定でき、「この曲が終わったら停止」も選べます。最後はゆっくりフェードアウトします。",
      f13t: "ホーム画面ウィジェット",
      f13d: "アプリを開かなくても、再生中の曲の確認や操作ができます。",
      f16t: "プライバシーを最優先に",
      f16d: "アカウント不要、トラッキングなし、アップロードなし。通信するのは、更新履歴とアップデートの確認で GitHub にアクセスするときだけです。",
      f11t: "バックアップと復元",
      f11d: "プレイリスト・お気に入り・統計・設定を、JSON ファイル 1 つで新しい端末へ引き継げます。",
      f14t: "英語・日本語に対応",
      f14d: "システムの言語に合わせて表示します。設定から切り替えることもできます。",
      f12t: "好きな色で",
      f12d: "標準の Sleeve のほか、Material You、アルバムアートの色、好きなアクセントカラー、真っ黒な画面から選べます。",
      f15t: "GitHub からアップデート",
      f15d: "GitHub のリリースを確認し、お使いの端末に合ったビルドをインストールします。ストアは必要ありません。",
      scrTitle: "アプリの画面",
      altHome: "最近追加したアルバムとミニプレーヤーが表示された Sonora のホーム画面",
      altNow: "アルバムジャケットの左側に曲名が縦に並ぶ再生画面",
      altLib: "アルバムのグリッドと絞り込みチップが表示されたライブラリ画面",
      altSet: "カラー、テーマ、再生のオプションが並ぶ設定画面",
      capHome: "ホーム",
      capNow: "再生中",
      capLib: "ライブラリ",
      capSet: "設定",
      dlTitle: "ダウンロード",
      dlBody: "GitHub から APK をダウンロードして、スマートフォンで開いてください。初回のみ、ブラウザからのインストールを許可するよう求められることがあります。以降のアップデートはアプリ内で行えます。",
      downloadApk: "APK をダウンロード",
      allReleases: "すべてのリリース",
      dlReq: "Android 7.0 以降に対応しています。",
      footLicense: "Sonora は <a href=\"https://github.com/yurtemre7/SonoraKMP/blob/main/LICENSE\">MIT ライセンス</a>で公開されている、無料のオープンソースソフトウェアです。",
      footSource: "ソースコード",
      footReleases: "リリース一覧",
      footIssues: "不具合を報告",
      footPrivacy: "このサイトは Cookie もトラッキングも使用していません。通信するのは、GitHub から最新リリースの情報を取得するときだけです。",
      relVersion: "バージョン {v}",
      relSize: "{size}",
      relDate: "{date} 公開",
      relJoin: "、",
      relEnd: "",
      relDetail: "{file}（{size}）",
      relSha: "SHA-256 チェックサム",
      relError: "GitHub に接続できませんでした。ボタンからは最新リリースのページを開けます。"
    }
  };

  var root = document.documentElement;
  var lang = 'en';
  var release = null;      // parsed release info, or null
  var releaseFailed = false;

  function store(kind, key, value) {
    try {
      var s = kind === 'session' ? window.sessionStorage : window.localStorage;
      if (value === undefined) return s.getItem(key);
      s.setItem(key, value);
    } catch (e) { /* storage blocked: ignore */ }
    return null;
  }

  function t(key, vars) {
    var str = (I18N[lang] && I18N[lang][key]) || I18N.en[key] || '';
    if (vars) str = str.replace(/\{(\w+)\}/g, function (_, k) { return vars[k] != null ? vars[k] : ''; });
    return str;
  }

  /* ---------- Language ---------- */
  function detectLang() {
    var saved = store('local', 'sonora-lang');
    if (saved === 'en' || saved === 'ja') return saved;
    var langs = navigator.languages || [navigator.language || 'en'];
    for (var i = 0; i < langs.length; i++) {
      var l = String(langs[i]).toLowerCase();
      if (l.indexOf('ja') === 0) return 'ja';
      if (l.indexOf('en') === 0) return 'en';
    }
    return 'en';
  }

  function applyLang(next) {
    lang = I18N[next] ? next : 'en';
    root.setAttribute('lang', lang);
    document.title = t('title');
    var meta = document.querySelector('meta[name="description"]');
    if (meta) meta.setAttribute('content', t('metaDescription'));

    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      el.textContent = t(el.getAttribute('data-i18n'));
    });
    document.querySelectorAll('[data-i18n-html]').forEach(function (el) {
      el.innerHTML = t(el.getAttribute('data-i18n-html')); // trusted strings from I18N only
    });
    document.querySelectorAll('[data-i18n-attr]').forEach(function (el) {
      el.getAttribute('data-i18n-attr').split(';').forEach(function (pair) {
        var p = pair.split(':');
        if (p.length === 2) el.setAttribute(p[0].trim(), t(p[1].trim()));
      });
    });
    document.querySelectorAll('[data-lang]').forEach(function (btn) {
      btn.setAttribute('aria-pressed', String(btn.getAttribute('data-lang') === lang));
    });
    renderRelease();
  }

  /* ---------- Theme ---------- */
  var darkQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function currentTheme() {
    var forced = root.getAttribute('data-theme');
    if (forced === 'light' || forced === 'dark') return forced;
    return darkQuery && darkQuery.matches ? 'dark' : 'light';
  }

  function syncThemeButton() {
    var btn = document.getElementById('theme-toggle');
    if (btn) btn.setAttribute('aria-pressed', String(currentTheme() === 'dark'));
  }

  function toggleTheme() {
    var next = currentTheme() === 'dark' ? 'light' : 'dark';
    // Matching the system again means "follow the system" from now on.
    var system = darkQuery && darkQuery.matches ? 'dark' : 'light';
    if (next === system) {
      root.removeAttribute('data-theme');
      store('local', 'sonora-theme', 'system');
    } else {
      root.setAttribute('data-theme', next);
      store('local', 'sonora-theme', next);
    }
    syncThemeButton();
  }

  /* ---------- Latest release ---------- */
  function formatSize(bytes) {
    if (!bytes) return '';
    var mb = bytes / (1024 * 1024);
    return (mb >= 10 ? mb.toFixed(0) : mb.toFixed(1)) + ' MB';
  }

  function formatDate(iso) {
    if (!iso) return '';
    try {
      return new Intl.DateTimeFormat(lang === 'ja' ? 'ja-JP' : 'en', { year: 'numeric', month: 'short', day: 'numeric' })
        .format(new Date(iso));
    } catch (e) { return iso.slice(0, 10); }
  }

  function pickApk(assets) {
    var apks = assets.filter(function (a) { return APK_RE.test(a.name); });
    if (apks.length < 2) return apks[0] || null;
    var abi = /(armeabi|arm64|x86)/i;
    return apks.filter(function (a) { return /universal/i.test(a.name); })[0] ||
      apks.filter(function (a) { return !abi.test(a.name); })[0] ||
      apks.filter(function (a) { return /arm64-v8a/i.test(a.name); })[0] ||
      apks[0];
  }

  function parseRelease(json) {
    var assets = Array.isArray(json.assets) ? json.assets : [];
    var apk = pickApk(assets);
    var sha = null;
    if (apk) {
      sha = assets.filter(function (a) { return a.name === apk.name + '.sha256'; })[0] ||
        assets.filter(function (a) { return /^sonora-.*\.sha256$/i.test(a.name); })[0] || null;
    }
    return {
      version: String(json.tag_name || json.name || '').replace(/^v/i, ''),
      date: json.published_at || '',
      page: json.html_url || FALLBACK,
      apk: apk ? { name: apk.name, url: apk.browser_download_url, size: apk.size } : null,
      sha: sha ? { url: sha.browser_download_url } : null
    };
  }

  function renderRelease() {
    var meta = document.getElementById('release-meta');
    var detail = document.getElementById('release-detail');
    var buttons = [document.getElementById('download-btn'), document.getElementById('download-btn-2')];

    if (!release) {
      if (meta) meta.textContent = releaseFailed ? t('relError') : t('releaseFallback');
      return;
    }
    var href = release.apk ? release.apk.url : release.page;
    buttons.forEach(function (b) {
      if (!b) return;
      b.href = href;
      if (release.apk) b.setAttribute('download', release.apk.name); else b.removeAttribute('download');
    });
    var size = release.apk ? formatSize(release.apk.size) : '';
    if (meta) {
      var parts = [];
      if (release.version) parts.push(t('relVersion', { v: release.version }));
      if (size) parts.push(t('relSize', { size: size }));
      if (release.date) parts.push(t('relDate', { date: formatDate(release.date) }));
      meta.textContent = parts.length ? parts.join(t('relJoin')) + t('relEnd') : t('releaseFallback');
    }
    if (detail) {
      detail.textContent = '';
      if (release.apk) detail.appendChild(document.createTextNode(t('relDetail', { file: release.apk.name, size: size }) + ' '));
      if (release.sha) {
        var a = document.createElement('a');
        a.href = release.sha.url;
        a.textContent = t('relSha');
        detail.appendChild(a);
      }
    }
  }

  function loadRelease() {
    var cached = store('session', CACHE_KEY);
    if (cached) {
      try {
        var c = JSON.parse(cached);
        if (c && c.at && Date.now() - c.at < CACHE_TTL && c.release) {
          release = c.release;
          renderRelease();
          return;
        }
      } catch (e) { /* bad cache: refetch */ }
    }
    if (!window.fetch) return;
    fetch(API, { headers: { Accept: 'application/vnd.github+json' } })
      .then(function (r) {
        if (r.status === 404) return null; // no release published yet
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (json) {
        if (!json) return;
        release = parseRelease(json);
        store('session', CACHE_KEY, JSON.stringify({ at: Date.now(), release: release }));
        renderRelease();
      })
      .catch(function () {
        // Rate limited, offline or no release yet: keep the /releases/latest fallback link.
        releaseFailed = true;
        renderRelease();
      });
  }

  /* ---------- Screenshot fallback (for images that failed before this script ran) ---------- */
  function fixScreenshots() {
    document.querySelectorAll('img[data-fallback]').forEach(function (img) {
      if (img.complete && img.naturalWidth === 0 && img.getAttribute('src') !== img.dataset.fallback) {
        img.src = img.dataset.fallback;
      }
    });
  }

  /* ---------- Init ---------- */
  function init() {
    document.querySelectorAll('[data-lang]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var next = btn.getAttribute('data-lang');
        store('local', 'sonora-lang', next);
        applyLang(next);
      });
    });
    var themeBtn = document.getElementById('theme-toggle');
    if (themeBtn) themeBtn.addEventListener('click', toggleTheme);
    if (darkQuery) {
      var onChange = function () { syncThemeButton(); };
      if (darkQuery.addEventListener) darkQuery.addEventListener('change', onChange);
      else if (darkQuery.addListener) darkQuery.addListener(onChange);
    }
    syncThemeButton();
    applyLang(detectLang());
    fixScreenshots();
    loadRelease();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
