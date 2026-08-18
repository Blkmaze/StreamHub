# StreamHub TV

An Android TV / Fire TV IPTV player built around two things you asked for:

1. **A stream that bends to the customer's internet instead of buffering**, and
2. **A way to talk to the customer from inside the app.**

Works on Fire TV Stick (all generations), Fire TV Cube, Android TV / Google TV boxes,
Nvidia Shield, Chromecast with Google TV, and ordinary Android phones and tablets.
Minimum Android 5.0 (API 21).

---

## What's in it

**Playback**
- Media3 / ExoPlayer with hardware decoding and automatic software fallback
- Live TV, Movies (VOD), Series with episode listings
- Multi-server: unlimited Xtream Codes panels and M3U/M3U8 playlists side by side
- Automatic failover — when a channel dies, the app finds the same channel on your
  other servers (matching names while ignoring `HD`/`FHD`/`VIP`/`[..]` noise) and switches
- Favourites, channel zapping with ▲▼, quick channel list, on-screen diagnostics

**The adaptive engine** (`net/AdaptiveEngine.java`, `net/NetworkMonitor.java`)
- Measures real throughput three ways: ExoPlayer's bandwidth meter, a short HTTP
  range-probe against *the customer's own panel*, and the last known estimate from
  the previous session — so the very first channel already starts at a sane quality
- Classifies the line into five tiers and derives everything from it:

  | Tier | Speed | Buffer profile | Bitrate ceiling | Resolution cap |
  |------|-------|----------------|-----------------|----------------|
  | Very slow | < 1.5 Mbps | Anti-buffer (45–180 s) | 1.1 Mbps | 480p |
  | Slow | < 4 Mbps | Anti-buffer | 2.8 Mbps | 720p |
  | Good | < 9 Mbps | Balanced (20–90 s) | 6 Mbps | — |
  | Fast | < 25 Mbps | Balanced | 16 Mbps | — |
  | Very fast | 25 Mbps+ | Low latency (8–30 s) | unrestricted | — |

- Never plans to use more than **72%** of measured throughput, leaving headroom for
  audio, TCP overhead and everyone else in the house
- **Every stall drops the ceiling 30% and deepens the buffer; 45 s of clean playback
  lifts it back up.** This is what stops the classic 1080p → stall → 1080p → stall loop
- Picks HLS (`.m3u8`, multi-rendition, truly adaptive) on a healthy line and plain TS
  (fewer round trips, faster start) on a weak one — and swaps transports automatically
  as a first recovery step when a stream fails
- HTTP connect/read timeouts scale with line quality: a slow line gets patience, not an error
- Decoder failures (4K/HEVC on a cheap stick) are treated as a signal to request a
  smaller stream rather than a dead end

**Customer messaging** (`chat/ChatClient.java`, `admin/console.html`)
- Two-way chat inside the app, backed by Supabase — no server for you to maintain
- **Addressed to individual customers, not just broadcast.** Three scopes:
  - **Device** — one stick. `messages.device_id = 'A1B2C3D4'`
  - **Account** — one customer, delivered to every device they own.
    `messages.device_id IS NULL, messages.account_ref = 'john@panel.tv:8080'`
  - **Everyone** — announcement banner to all devices (`notices.audience = 'all'`),
    or to one account, one device, or everyone carrying a tag
- Devices group themselves into accounts automatically: the account reference is derived
  from the customer's own line (`username@host`), so a household with three sticks is one
  conversation, and a reply reaches all three. Override it with your own invoice/CRM code
  via `BuildDefaults.ACCOUNT_CODE` or Settings if you'd rather use your own numbering
- Every device registers itself on launch, so customers appear in your directory before
  they ever write in — you can open a conversation first
- Row-level security scopes reads to the device's own thread plus its account's, and the
  account is resolved from the server-side registry rather than trusted from the request.
  Holding the anon key does not let anyone read another customer's messages
- Tags and private notes per account; filter the directory by unread, active-in-24h,
  never-written, or tag
- Fallbacks that need no backend at all: one-tap WhatsApp, Telegram, and email,
  each pre-filled with the device ID so you know which line is calling

---

## Building the APK

You do not need Android Studio for route A.

### Route A — GitHub builds it for you (easiest)

```bash
cd streamhub-tv
git init && git add . && git commit -m "StreamHub TV"
gh repo create streamhub-tv --private --source=. --push
# or: create the repo on github.com, then
#   git remote add origin https://github.com/YOURNAME/streamhub-tv.git
#   git branch -M main && git push -u origin main
```

The included workflow (`.github/workflows/build-apk.yml`) runs on every push. When it
finishes (~4 minutes) you get:

- **Actions → latest run → Artifacts →** `StreamHubTV-apk` (both debug and release)
- **Releases → `latest` →** a permanent direct download URL for the release APK:
  `https://github.com/YOURNAME/streamhub-tv/releases/download/latest/StreamHubTV-release.apk`

That release URL is what you shorten for the Downloader app.

### Route B — Android Studio

Open the `streamhub-tv` folder, let Gradle sync (it downloads the SDK bits it needs),
then **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

### Route C — command line

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

### Signing

The signing key is **not** committed — `.gitignore` keeps `app/keystore/*.jks` out of the
repository, which matters because the repo has to be public for Downloader to fetch the
release without a login.

- **Locally:** keep `app/keystore/streamhub.jks` where it is and `assembleRelease` signs
  with it, using the store/key password recorded in your own password manager (see below —
  it is deliberately not written in this file).
- **In CI:** add four repository secrets and the workflow restores the key before building
  — **Settings → Secrets and variables → Actions → New repository secret**:

  | Secret | Value |
  |--------|-------|
  | `KEYSTORE_BASE64` | contents of `KEYSTORE_BASE64.txt` |
  | `KEYSTORE_PASSWORD` | your generated password |
  | `KEY_ALIAS` | `streamhub` |
  | `KEY_PASSWORD` | same generated password |

  The alias is just a label, not a secret — `streamhub` is fine to leave as-is. The
  password is the part that actually protects the key, so it should be a long random
  string kept in a password manager, never committed to the repo, and never the same
  value used for anything else. Do not write it in this README, in commit messages, or
  in any file that lands in this (public) repository.

- **Without the secrets** the build still works — it falls back to the debug key, and the
  APK still installs. Sticks installed that way need an uninstall before moving to a
  properly signed update, so add the secrets before you hand builds to customers.

Keep the `.jks` file backed up somewhere private. Losing it means every customer has to
uninstall and reinstall to take an update, because Android refuses an update signed with
a different key.

### What not to commit to a public repo

`BuildDefaults.java` is committed, so leave real customer credentials out of it
(`PRESET_USER` / `PRESET_PASS`). The Supabase **anon** key is designed to be shipped in
the app and is safe there; the **service_role** key never belongs in the repo — it stays
in your browser when you use the console.

### Network security and credential exposure

Two things worth understanding rather than just accepting:

- **Cleartext HTTP is enabled globally** (`network_security_config.xml`), and it has to
  be — this is a generic Xtream/M3U client, customers add arbitrary hosts at runtime, and
  a large share of real-world IPTV panels only serve plain HTTP. There's no fixed domain
  list to scope a `domain-config` against. Every client in this category has the same
  constraint. Prefer `https://` panels where your provider offers one — the app already
  respects an explicit scheme.
- **Xtream credentials travel in the URL query string** (`player_api.php?username=...`)
  because that is the Xtream Codes protocol itself, not something this app does
  differently. On an HTTP panel, that means the login is visible to anything on the
  network path — a proxy, an access log, a curious router. This is unavoidable for
  protocol compatibility; the mitigation is provider choice (HTTPS panels), not client
  code.
- **What *is* scoped:** release builds trust **system CAs only**
  (`app/src/main/res/xml/network_security_config.xml`). Debug builds additionally trust
  user-installed CAs (`app/src/debug/res/xml/network_security_config.xml`), so you can
  point a debug build at a proxy like mitmproxy while developing — but a release build a
  customer installs will not trust a certificate profile someone gets onto their device,
  closing off the MITM-via-malicious-profile route even though cleartext stays on.

### Never host `admin/console.html`

That page runs with your Supabase **service_role** key — full read/write on every table,
no row-level security. It's safe as a local file you open yourself. It is not safe on any
reachable host: GitHub Pages, a static site bucket, anything. The file refuses to render
if it detects it isn't running from `file://` or `localhost`, as a backstop — but don't
rely on that instead of just not deploying it. Keep it local.

---

## Installing on a Fire TV Stick

1. On the stick: **Settings → My Fire TV → Developer Options → Install unknown apps →**
   enable it for **Downloader**
2. Install **Downloader** (by AFTVnews) from the Amazon Appstore
3. Open Downloader, type your short link, press Go
4. Install → Open. The app appears in your apps row with its own banner

For Android TV / Google TV boxes, sideload the same APK with Downloader,
`adb install StreamHubTV-release.apk`, or a USB stick.

---

## Configuring it for your customers

Edit `app/src/main/java/com/wm/streamhub/util/BuildDefaults.java` before building and
the app ships preconfigured — no typing on a remote:

```java
public static final String CHAT_BASE_URL   = "https://yourproject.supabase.co";
public static final String CHAT_API_KEY    = "eyJhbGciOi...";   // anon key
public static final String SUPPORT_WHATSAPP = "15551234567";
public static final String SUPPORT_TELEGRAM = "yourhandle";
public static final String SUPPORT_EMAIL    = "support@yourdomain.com";

// Optional: your own reference for this customer (invoice or CRM ID). Leave blank
// and the account is derived from their line, which is usually what you want.
public static final String ACCOUNT_CODE = "";

// Optional: preload the customer's line so they see channels on first launch
public static final String PRESET_NAME = "Main server";
public static final String PRESET_HOST = "http://panel.example.com:8080";
public static final String PRESET_USER = "customer123";
public static final String PRESET_PASS = "secret";
```

Everything is also editable at runtime under **Settings**.

### Setting up the support backend (5 minutes, free)

1. Create a project at supabase.com
2. **SQL Editor → New query →** paste `supabase/schema.sql` → Run
3. **Project Settings → API →** copy the **Project URL** and the **anon public** key
   into `BuildDefaults.java` (or into Settings on the device)
4. Open `admin/console.html` in any browser, paste the same Project URL and your
   **service_role** key, and press Connect

The console lists every customer account, groups their devices underneath, and shows
unread counts. Above the reply box is a **Send to** selector — *Whole account* (every
device that customer owns) or one specific device. **Send announcement** opens the banner
composer, where the audience is Everyone, one account, one device, or everyone with a tag.
The service_role key never leaves your browser tab.

### How a customer is identified

| Situation | Account reference |
|-----------|-------------------|
| Xtream line | `username@host:port` — derived automatically |
| M3U playlist with credentials in the URL | `username@host` |
| M3U playlist without credentials | `host-<hash>` |
| You set `ACCOUNT_CODE` or the Settings field | exactly what you typed |

Devices that share a reference share one conversation. If a customer changes provider,
their reference changes with it — set a manual account code if you want the thread to
follow the person rather than the line.

---

## Remote control map

**Browse screen**

| Key | Action |
|-----|--------|
| ◀ ▶ | Move between nav rail, servers, categories, channels |
| ▲ ▼ | Move within a column |
| OK | Open / play |
| OK (hold) | Add or remove a favourite |
| BACK | Step back one column, then exit |
| MENU | Settings |

**While watching**

| Key | Action |
|-----|--------|
| ▲ ▼ | Previous / next channel |
| OK | Quick channel list |
| ▶ | Show channel info |
| ◀ | Toggle the diagnostics overlay |
| MENU | Playback menu (adaptive on/off, buffering mode, quality ceiling, transport) |
| BACK | Close overlay, then exit to the browser |

---

## Troubleshooting

**"The panel rejected these credentials"** — username/password wrong, or the line has
expired. Use **Servers → Test connection**; it reports status, expiry date, and how many
of the allowed connections are currently in use.

**Channel buffers on one device but not another** — check the diagnostics overlay (◀ while
watching). If `NET` reads much lower than the customer's plan, the bottleneck is Wi-Fi,
not the panel. Fire TV sticks are notoriously bad on 2.4 GHz; an Ethernet adapter fixes
most "your service is buffering" complaints outright.

**"This device cannot decode that stream format"** — the stream is HEVC or 4K and the
stick's decoder can't take it. Set a quality ceiling of 4500 kbps in Settings, or ask
the provider for the H.264 variant of that channel.

**Everything times out on one network but works on a hotspot** — the ISP is likely
blocking the panel's port. Ask the provider for an alternate port or domain and add it
as a second server; failover will handle the rest.

---

## Project layout

```
app/src/main/java/com/wm/streamhub/
├── StreamHubApp.java          app startup, preset seeding
├── model/                     ServerProfile, StreamItem, Category, ChatMessage
├── data/                      XtreamClient, M3UParser, ContentRepository, NowPlaying
├── net/                       NetworkMonitor (measurement), AdaptiveEngine (policy)
├── player/PlayerActivity      playback + live adaptation + recovery
├── chat/ChatClient            messaging transport
├── ui/                        Main (3 columns), Servers, AddServer, Chat, Settings
└── util/                      Http, Prefs, BuildDefaults
admin/console.html             support console (open in any browser)
supabase/schema.sql            run once in the Supabase SQL editor
.github/workflows/build-apk.yml  builds and publishes the APK on every push
```

## A note on content

The app is a player. It ships with no channels, no playlists and no provider — the
customer supplies their own subscription details, exactly like VLC or Kodi. Make sure
whatever you point it at is content you are licensed to distribute.
