# PhoneAgent network inventory

The privacy contract: **no network traffic at startup or while idle.** The app connects only when
the owner does something that obviously needs the network. This file lists every outbound
connection the app can make, and how to check the claim on the phone.

Last full audit: Phase 0 (2026-10-15), by reading every network call site in the Kotlin sources,
the merged manifest and the bytecode of LiteRT-LM and MediaPipe.

## What the app connects to

| # | Who (code) | Host(s) | When | Owner-initiated? |
|---|---|---|---|---|
| 1 | Model download (`DownloadWorker`, `HuggingFaceApiClient.checkModelAccessibility`) | `huggingface.co` and its file CDN (`cdn-lfs*.huggingface.co`, `*.xethub.hf.co`); `storage.googleapis.com` (Scrapbook "Magic touch" model only) | Tapping **Download** on a model | Yes |
| 1a | Resume of an interrupted download | same as 1 | At app start, only if a download you started was interrupted (WorkManager may also resume it when the network returns) | Continuation of 1. Cancel the download to stop it |
| 2 | Smart Album sample photos (`SampleAlbumStorageManager`) | `dl.google.com` | Downloading the samples in Smart Album | Yes |
| 3 | "Refresh model list" (`phoneagent/modellist/ModelListStore.refresh`) | `raw.githubusercontent.com` | Settings > Model list > **Refresh model list** | Yes |
| 4 | Hugging Face model browser (`HfExploreViewModel`) | `huggingface.co/api` | Opening the Hugging Face explore screen in Model Manager, searching | Yes |
| 5 | Import a model from a URL (`ModelImportDialog`) | the URL you typed | Importing by URL | Yes |
| 6 | Hugging Face login (AppAuth) | `huggingface.co/oauth/*` | Downloading a gated model. Not configured in this fork (placeholder client id), so it fails; paste a token in Settings instead | Yes |
| 7 | Agent Chat skills and MCP | skill URLs you add; sites a JS skill fetches (for example `query-wikipedia`); MCP server URLs you add | Adding a skill/server; running a skill; opening Agent Chat when MCP servers are configured (it reconnects to them) | Yes. None configured by default |
| 8 | Links in the UI (`ClickableLink`) | any | Tapping a link opens it in your browser; the browser makes the connection | Yes |
| 9 | Phone Agent browser tasks (Phase 2+) | sites the task visits | Firefox's own traffic while a task you started runs | Yes |

Nothing else. In particular:

- **Removed:** Firebase Analytics, Firebase Cloud Messaging, Firebase Installations, AppMeasurement,
  ML Kit GenAI / AICore (and its startup model download), Google Play services TFLite and
  oss-licenses, the feedback uploader, scheduled notifications, and the startup model-list fetch
  from GitHub.
- **Never wired in upstream:** the "new release" check (`NewReleaseNotification`, calls
  `api.github.com`) is not used anywhere; the featured-skills list URL is empty; the example custom
  task is not registered.
- **LiteRT-LM** (`liblitertlm_jni.so` and its Kotlin API) has no network code: no Java networking
  classes and no socket imports in the native library.
- **WebView** (skills, Tiny Garden) runs with `android.webkit.WebView.MetricsOptOut=true` and
  `android.webkit.WebView.EnableSafeBrowsing=false`, so it sends no usage metrics and makes no
  Safe Browsing lookups. Pages it loads still make their own requests (row 7).
- `usesCleartextTraffic="false"`: plain `http://` is refused (an `http://` MCP server won't work).
- `allowBackup="false"` plus data-extraction rules that exclude everything: no cloud backup and no
  device-to-device transfer of app data.

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
  `com.google.firebase` artifact.
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
  from anywhere but `:telemetry-stub`, the build fails. Today it finds 16 references, all from
  `com.google.mediapipe.tasks.core.logging`, all satisfied. A MediaPipe upgrade that needs more
  API therefore breaks the build instead of crashing a tile.

## Build-time guard and approved exceptions

`./gradlew :app:privacyGuard` (also run by CI) fails if any `com.google.firebase`,
`com.google.android.gms`, `com.google.mlkit` or `com.google.gms` artifact appears in the debug,
release or ci classpaths or the build files, or if the google-services / oss-licenses plugins are
applied. It prints every packaged library with its license and checks that the in-app license
list (`app/src/main/assets/phoneagent/third_party_licenses.tsv`) is current.

Approved exceptions: only the owner adds entries here, one backticked `group:artifact` (or
`group:*`) per line, with the reason and date. The guard reads this block.

<!-- privacy-guard-exceptions:start -->
(none)
<!-- privacy-guard-exceptions:end -->

## Checking it on the phone

### Option A: PCAPdroid (recommended, open source, no root)

PCAPdroid captures traffic per app through a local VPN. Nothing leaves the phone.

1. Install PCAPdroid (F-Droid or Play Store).
2. In PCAPdroid: **Target apps** > select **PhoneAgent** (the CI build is the one whose package
   ends in `.dev`). Leave TLS decryption off; you only need to see connections.
3. On the phone: Settings > Apps > PhoneAgent > **Force stop**, so you test a cold start.
4. In PCAPdroid tap **Start** (accept the VPN prompt).
5. Startup: open PhoneAgent, stay on the home screen for 2 minutes. Open Settings and a few tiles,
   but don't download, refresh or run anything.
6. Idle: press Home and leave the phone for 15 minutes (screen off is fine).
7. Tap **Stop** in PCAPdroid and open the **Connections** tab.
   - **Expected: no connections at all.** DNS lookups count as connections here too.
8. Positive control: start a capture again, open Settings > Model list > **Refresh model list**.
   Expected: exactly one connection, to `raw.githubusercontent.com:443`.

If you see anything in step 7, note the host, port and time, and check whether a model download
was interrupted earlier (row 1a). Send me the PCAPdroid connection list (Export > CSV).

Note: another VPN can't run at the same time. If you use a VPN-based ad blocker, pause it during
the test.

### Option B: DNS log (per-app firewall)

RethinkDNS (open source) combines a per-app firewall with a DNS log. Set it to log, open the app's
entry under **Apps**, and repeat steps 3-8 above. Expected: no DNS queries attributed to PhoneAgent
during startup and idle. A plain Private DNS log (NextDNS, AdGuard DNS, Pi-hole) shows queries from
the whole phone, not per app, so it can only show the absence of hosts like `firebaseinstallations`,
`app-measurement.com`, `firebaselogging` or `raw.githubusercontent.com` at app start.

### What's outside the app's control

Android and Samsung system services (connectivity checks, Play Store updates, Firefox's own
telemetry settings) make their own connections. They are attributed to other apps in PCAPdroid,
not to PhoneAgent.
