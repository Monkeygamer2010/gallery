# PhoneAgent network inventory

The privacy contract: **no network traffic at startup or while idle.** The app connects only when
the owner does something that obviously needs the network. This file lists every outbound
connection the app can make, what other apps may do on its behalf, and how to check both on the
phone.

Last full review: Phase 0.1 (2026-10-18), after an independent audit of Phase 0: every network
call site in the Kotlin sources, the skill assets, the merged manifest, the dex files and the
native libraries in the built APK. Phase 1 and Phase 2 added no network code; the APK check
still finds no socket or DNS imports in native code.

## What the app connects to

| # | Who (code) | Host(s) | When | Owner-initiated? |
|---|---|---|---|---|
| 1 | Model download (`DownloadWorker`) | `huggingface.co` and its file CDN (`cdn-lfs*.huggingface.co`, `*.xethub.hf.co`); `dl.google.com` (Smart Album's sample photos, an optional extra file of EmbeddingGemma-2); `storage.googleapis.com` (Scrapbook's "Magic touch" model) | Tapping **Download** on a model. The Hugging Face token is sent only to `https://huggingface.co` and its subdomains | Yes |
| 1a | Resume of an interrupted download | same as 1 | Only when you tap **Resume download** on that model. Nothing resumes at app start or in the background, and download work waits for a network connection | Yes |
| 2 | "Refresh model list" (`phoneagent/modellist/ModelListStore.refresh`) | `raw.githubusercontent.com` | Settings > Model list > **Refresh model list** | Yes |
| 3 | Import a model from a URL (`ModelImportDialog`) | For a Hugging Face URL: `huggingface.co/api` (model details); otherwise a 1-byte request to the URL you typed to read the file size. Then the download (row 1) | Importing by URL | Yes |
| 4 | Hugging Face login (AppAuth) | `huggingface.co/oauth/*` | Not configured in this fork (placeholder client id): the app shows a message instead. Paste a token in Settings | — |
| 5 | Agent Chat skills (JS skills in a WebView) | `SKILL.md` from a skill URL you add; for a skill whose **Allow network** switch you turned on: whatever its pages load (for example `query-wikipedia` calls `<lang>.wikipedia.org`, `interactive-map` shows Google Maps) | Adding a skill from a URL; running a skill with network allowed. Every such call asks first | Yes. **Allow network** is off for every skill by default |
| 6 | Agent Chat MCP servers | the MCP server URLs you add | Adding a server; while Agent Chat is on screen, enabled servers only. Connections close when you leave Agent Chat or the app goes to the background. Every tool call asks first | Yes. None configured by default |
| 7 | Links in the UI (`ClickableLink`, skill homepages, Tiny Garden credits) | any | Tapping a link opens it in your browser; the browser makes the connection (see "Traffic made by other apps") | Yes |
| 8 | Phone Agent spike (Phase 1) and tasks (Phase 2) | the `https://` page you open from the spike screen; the public `https://` pages a task opens (every address is listed in the run log) and the links it taps | Firefox's own traffic, in private tabs, after you tap **Open** in the spike tools or start a task. The model runs on the phone: PhoneAgent's process, the accessibility service, the run service and the Quick Settings tile make no connections | Yes |

Nothing else from the app's own process. In particular:

- **Removed:** Firebase Analytics, Firebase Cloud Messaging, Firebase Installations, AppMeasurement,
  ML Kit GenAI / AICore (and its startup model download), Google Play services TFLite and
  oss-licenses, the feedback uploader, scheduled notifications, the startup model-list fetch from
  GitHub, the startup download resume, the in-app "View community skills" GitHub page and the
  platform Geocoder lookup in Smart Album (photos show raw coordinates).
- **Never fetched:** remote images in Markdown (chat, model descriptions, skill text). They are
  shown as links, and the app-wide image loader refuses `http(s)` images.
- **Not reachable or not wired in upstream:** the "new release" check (`NewReleaseNotification`,
  `api.github.com`), Smart Album's separate sample download (`SampleAlbumStorageManager`), the
  Hugging Face explore screen (`HfExploreViewModel`), the featured-skills list (empty URL) and the
  example custom task.
- **Native code:** no library in the APK imports socket or DNS functions (`./gradlew
  :app:checkCiApk` verifies this on every build). LiteRT-LM and MediaPipe load GPU drivers by name
  (`dlopen`), which the check reports.
- **WebView** (skills, Tiny Garden) runs with `android.webkit.WebView.MetricsOptOut=true` and
  `android.webkit.WebView.EnableSafeBrowsing=false`: no usage metrics, no Safe Browsing lookups.
- `usesCleartextTraffic="false"`: plain `http://` is refused (an `http://` MCP server won't work).
- `allowBackup="false"` plus data-extraction rules that exclude everything: no cloud backup and no
  device-to-device transfer of app data.

## Skills: no network unless you allow it

JS skills run in WebViews sandboxed by `phoneagent/skills/SkillWebViewSandbox.kt`:

- Pages are served only from the built-in skills (APK `assets/skills`) and imported skills
  (`files/skills`). The rest of the app's private folder (settings, tokens, chats) is never
  served, and `file://` and `content://` access is off.
- With **Allow network** off (the default, per skill, in the skill manager): every request that is
  not a local skill file is refused, navigation away is cancelled, `blockNetworkLoads` is on, and
  local pages carry a Content-Security-Policy that refuses WebSockets, plain `http:` and form
  posts away from the page. The model is told when a skill tried to go online, so it can tell
  you which switch to turn on.
- Always: WebRTC is removed from skill pages, service workers cannot load from the network,
  camera and microphone requests are denied, and script and page names chosen by the model or a
  skill cannot leave the skill's folder.
- Skills loaded from a URL need **Allow network** to run at all, because their code is downloaded
  each time they run. Deleting or renaming a skill resets its switch.
- Built-in skills that use the network: `query-wikipedia`, `learn-something-new` (Wikipedia,
  Google Fonts, cdnjs), `qr-code` (cdnjs), `interactive-map` (Google Maps), `mood-tracker`'s
  dashboard (Google Fonts, jsDelivr) and `text-spinner` (jsDelivr, `storage.googleapis.com`,
  camera). With the switch off they fail or fall back, and say so.

## Traffic made by other apps on PhoneAgent's behalf

Some features hand work to another app, which then makes its own connections under its own UID.
PCAPdroid attributes that traffic to the other app, not to PhoneAgent.

| What | Other app | When | What the fork does |
|---|---|---|---|
| Links, skill homepages, credits | your default browser (Firefox) | only when you tap a link | Nothing at startup: the Hugging Face login service (AppAuth), which binds the browser's Custom Tabs service and warms it up, is created only when a login is actually started, and login is not configured |
| Phone Agent tasks (Phase 2+) | Firefox | while a task you started runs | Pages open in private tabs only. Firefox's own telemetry settings apply; set them in Firefox. uBlock Origin set to run in private browsing blocks trackers on the pages |
| Hold-to-dictate | the on-device speech recognizer | while you hold the mic button | Only the on-device recognizer is used, with "prefer offline"; the button is hidden when the phone has none. The default (cloud-capable) recognizer is never used |
| Email, SMS, calendar, contacts, maps (Agent Chat skills, Mobile Actions) | your mail, messages, calendar, contacts and maps apps | after you tap **Allow once** | The other app opens with the content filled in; it sends nothing until you act in it (maps apps look up the place) |
| Location names for photos | location services (Google Play services) | never | Removed: Smart Album shows raw coordinates |
| Emoji fonts | font provider (Google Play services) | never | EmojiCompat's startup initializer is removed; the system emoji font is used |
| Screen text and typed text | content capture (system intelligence), autofill services, keyboards | never | Content capture is off, the window is excluded from autofill, every text field asks keyboards not to learn what you type, and Recents keeps no screenshot of the app (Android 13+) |

Android itself and other system services (connectivity checks, Play Store updates, time sync) make
their own connections whatever PhoneAgent does.

## MediaPipe usage stats: replaced by a stub

MediaPipe Tasks (used by Scrapbook, Smart Album and Video Moment Finder) contains
`com.google.mediapipe.tasks.core.logging.RemoteLoggingClient`. Every time a MediaPipe task starts it
builds a usage-stats event (task name, mode, device and app info, latency counters) and hands it to
Firebase's datatransport library, which stores events locally and uploads them to Google's Clearcut
endpoint (log source `COREML_ON_DEVICE_SOLUTIONS`) from a background JobService. MediaPipe creates
this client with no fallback, so simply removing the library would crash those tiles.

What the fork does instead (owner decision, Phase 0):

- `app/build.gradle.kts` excludes `com.google.android.datatransport:transport-runtime`,
  `com.google.android.datatransport:transport-backend-cct` (the uploader) and every
  `com.google.firebase` artifact. The guard also forbids the two uploader modules by name.
- It keeps `com.google.android.datatransport:transport-api` (Apache-2.0): plain interfaces
  (`Transport`, `TransportFactory`, `Event`, `Encoding`, ...) with no I/O.
- The `:telemetry-stub` module (`Android/src/telemetry-stub`) provides the three runtime classes
  MediaPipe calls, under their original names so MediaPipe links against them:
  - `com.google.android.datatransport.runtime.TransportRuntime`: `initialize()` does nothing,
    `newFactory()` returns transports whose `send()` drops the event without serializing it.
  - `com.google.android.datatransport.runtime.Destination`: interface only.
  - `com.google.android.datatransport.cct.CCTDestination`: carries no endpoint.
- The stub registers no services, receivers or jobs. The real runtime's
  `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver` and
  `TransportBackendDiscovery` are gone from the merged manifest.
- **Build check:** `./gradlew :app:privacyGuard` scans the bytecode of every library on the
  release classpath. If anything references a class or member in `com.google.android.datatransport`
  or `com.google.firebase` that the classpath doesn't define, or if the uploader packages come
  from a Maven library instead of a project module, the build fails. Today it finds 16 references,
  all from `com.google.mediapipe.tasks.core.logging`, all satisfied. The APK check then verifies
  that only the stub's classes exist in those packages in the built APK. A MediaPipe upgrade that
  needs more API therefore breaks the build instead of crashing a tile.

## Build-time checks and approved exceptions

The rules live in `Android/src/build-logic` as constants, not settings, so no build script can
loosen them, and the build stops if a build script disables either check.

- `./gradlew :app:privacyGuard` (run by CI) fails if any forbidden artifact appears in the debug,
  release or ci classpaths or in the root build files: Firebase, Google Play services, Play Core,
  ML Kit, the AICore SDK, Google ads, common crash and analytics SDKs, or Firebase's telemetry
  uploader. It also fails on local file dependencies and on the google-services / oss-licenses
  plugins. It prints every packaged library with its license and checks that the in-app license
  list (`app/src/main/assets/phoneagent/third_party_licenses.tsv`) is current.
- `check<Variant>Apk` runs with every `assemble<Variant>` and inspects the built APK: no forbidden
  classes in any dex file, telemetry classes only from `:telemetry-stub`, no native socket or DNS
  imports, no `Log.v/d/i` calls left in release and ci builds, and merged-manifest rules (approved
  permissions only; no component other apps can start beyond an approved list; cleartext and
  backups off; WebView metrics and Safe Browsing off; no EmojiCompat initializer).
- Both write a report under `app/build/reports/phoneagent/` only when they pass; CI fails if a
  report is missing or did not pass.

Approved exceptions: only the owner adds entries here, one backticked `group:artifact` (or
`group:*`) per line, with the reason and date. The guard reads this block.

<!-- privacy-guard-exceptions:start -->
(none)
<!-- privacy-guard-exceptions:end -->

## Checking it on the phone

### Option A: PCAPdroid (recommended, open source, no root)

PCAPdroid captures traffic per app through a local VPN. Nothing leaves the phone.

1. Install PCAPdroid (F-Droid or Play Store).
2. On the phone: Settings > Apps > PhoneAgent > **Force stop**, so you test a cold start.
3. In PCAPdroid leave **Target apps** empty (all apps) and leave TLS decryption off; you only need
   to see connections. Tap **Start** (accept the VPN prompt).
4. Startup: open PhoneAgent (the CI build is the one whose package ends in `.dev`) and stay on the
   home screen for 2 minutes. Open Settings and a few tiles, but don't download, refresh or run
   anything.
5. Idle: press Home and leave the phone for 15 minutes (screen off is fine).
6. Tap **Stop** and open the **Connections** tab.
   - **Expected: no connections from PhoneAgent at all.** DNS lookups count as connections too.
   - Connections from other apps are expected (system, Play Store, ...). Check that none from
     Google Play services or Firefox line up with the moment you opened PhoneAgent.
7. Positive control: start a capture again, open Settings > Model list > **Refresh model list**.
   Expected: exactly one PhoneAgent connection, to `raw.githubusercontent.com:443`.

If you see a PhoneAgent connection in step 6, note the host, port and time and send me the
connection list (Export > CSV).

Note: another VPN can't run at the same time. If you use a VPN-based ad blocker, pause it during
the test.

### Option B: DNS log (per-app firewall)

RethinkDNS (open source) combines a per-app firewall with a DNS log. Set it to log, open the app's
entry under **Apps**, and repeat steps 2-7 above. Expected: no DNS queries attributed to PhoneAgent
during startup and idle. A plain Private DNS log (NextDNS, AdGuard DNS, Pi-hole) shows queries from
the whole phone, not per app, so it can only show the absence of hosts like `firebaseinstallations`,
`app-measurement.com`, `firebaselogging` or `raw.githubusercontent.com` at app start.
