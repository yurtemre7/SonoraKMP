# Jam

A Jam plays the same queue on several phones at the same time, for a room with
more than one speaker or a group with headphones. One phone hosts; up to 7 more
join as guests (8 phones in all).

## How to use it

- **Start**: Now Playing › More options › Start a Jam, the "Start a Jam" button
  on the weekly mix (it plays the mix first), or Settings › Jam. Choose what guests
  may do: "Guests can add songs" (on by default) and "Guests can skip and pause"
  (off by default). Both can be changed while the Jam runs.
- **Join**: Settings › Jam › Join a Jam, or Now Playing › More options › Join a
  Jam. Pick the host from the list and type the 6-digit code shown on the host.
  If the host is not listed, type the address shown on the host's Jam screen
  (for example `192.168.1.20:41234`).
- **During the Jam** the host controls the queue. A guest sees "In a Jam with
  <host>" in Now Playing, the host's queue under Up next, and only the controls
  the host allows. Songs a guest does not have are shown as "Not on this phone"
  and that phone stays silent until the next song it has. Every phone keeps its
  own volume, and a guest can leave at any time; its own queue comes back.
- **A guest's own music**: while in a Jam, a guest cannot play its own music without leaving. Playing a
  song, an album, a playlist, the weekly mix or a related song asks "Leave the Jam to play your own music?":
  "Leave and play" leaves the Jam (the guest's own queue comes back) and then plays the choice; "Stay" keeps
  the Jam and drops it. For "Play next" and "Add to queue", when the host allows guests to add songs, the
  first choice is "Add to the Jam queue" (the host gets the songs, the app says "Sent N songs to the Jam",
  and the guest stays). Many songs at once go out in batches of up to 100; guests can fill the Jam queue up
  to 500 songs (the host takes the first ones that fit, and the app counts only what it sent, or says "The
  Jam queue is full"). If the Jam ends while the question is open, it is dropped. Seeking, shuffle, repeat,
  reordering and the sleep timer are not available to a guest. Play/pause and skip, in the app and from the
  notification, lock screen, a headset or the widget, are sent to the host when it allows guests to skip and
  pause, and ignored otherwise; outside controls cannot seek or change the queue during a Jam. A headset's or
  car's "play" only asks the host to play when it is paused (and "pause" only when it plays), so a late button
  press never flips the host.
- **Headphones unplugged**: when a guest's phone pauses by itself (headphones or a Bluetooth device
  disconnected, or another app took the audio for good), it stays paused: the host's next update does not
  start it again, so music never comes out of the phone's speaker by surprise. The Jam line says "Paused. Tap
  play to rejoin"; play (shown even when the host does not let guests pause) rejoins the host where it is now.
  If the phone resumes by itself when the headphones come back ("Resume on connect"), it simply plays on.
- **Ending**: the host taps End Jam. Guests finish the current song, then see
  "Jam ended". The host can also remove a single guest.

## What to expect

- Each phone plays its **own copy** of the song: nothing is streamed and no
  internet connection is needed. Songs are matched by title, artist and length;
  the album only decides between several matches.
- **Sound quality comes before exact sync.** A guest never speeds up, slows down
  or jumps in the middle of a song to catch up. It may trail the host by a
  fraction of a second; a gap under about half a second is normal. If a phone
  drifts more than 2 seconds, it corrects itself at the next pause or song change.
- A Bluetooth speaker adds its own delay. Settings › Playback › Jam › Speaker
  delay (−500 to +500 ms, default 0) moves this phone's start earlier or later.
  It is stored on this phone only and not included in backups.
- If a guest's connection drops, it keeps playing the current song and reconnects
  within 30 seconds. After a Wi-Fi change it leaves cleanly and offers Rejoin
  (the code is needed again). If the host disappears, guests finish the current
  song and then show "Jam ended".
- A Jam keeps running with the screen off (a notification shows while it runs).
- A speed effect on the host (Slowed, Nightcore, custom speed) or skip silence is
  not copied to guests: they play at normal speed without skipping and fall behind;
  the host screen says so.
- The host's repeat mode is followed. On **Repeat One** each guest loops the song
  in its own player (no reload, no cut at each loop); on Repeat All the last song
  finishes before the first one starts again.

## Limits

- All phones must be on the **same Wi-Fi** (or the host's hotspot). Up to 8
  phones.
- Guest and hotel Wi-Fi often use "client isolation", which hides phones from each
  other. The Jam then does not show up in the list: type the host's address
  instead. If that fails too, the network blocks phone-to-phone traffic; use a
  phone's hotspot.
- Host and guests need the same Jam version of SonoraKMP; otherwise the guest
  sees "This Jam uses a different version of SonoraKMP".
- There is no QR-code joining (on purpose: no camera permission), and songs a
  guest lacks are not streamed or copied. Device sync can copy them beforehand.

## Checks on real phones

Unit tests cannot cover audio routes or speakers; before a release, try on two or more phones:

- Start, join, play, pause and skip; songs a guest lacks stay silent; End Jam.
- **Unplug headphones while a guest**: the guest stays paused (nothing from the
  phone's speaker) through the host's next song; play rejoins; "Resume on connect"
  plays on when they come back.
- **Host on Repeat One**: guests loop the song with it, without a few seconds of
  the next song or a cut at each loop; turning repeat off brings the next songs back.
- Add 200 songs as a guest: all arrive in the host's queue, the guest stays connected.
- Headset and lock-screen play/pause on a guest with and without "Guests can skip and pause".
- A Bluetooth speaker with the speaker delay; Wi-Fi change and Rejoin.

## Security

- The 6-digit code is **new for every Jam** and only lives as long as the Jam.
  Nothing is stored as a trusted device.
- Each guest proves it knows the code with a password-authenticated key exchange
  (J-PAKE, the same as device sync), so the code is never sent over the network
  and someone listening on the Wi-Fi cannot learn it or read the Jam's traffic.
  Every connection is encrypted.
- Three wrong codes lock joining for a short time (the guest sees a countdown);
  too many wrong codes in total block the code until the host makes a new one.
- A guest that drops can reconnect for 30 seconds with a one-time session key;
  after that, or once removed, it needs the code again.
- The LAN advertisement (mDNS `_sonora-jam._tcp`) carries only the protocol
  version and the host's display name, never the code.
