# PhoneAgent phase log

One entry per phase: what changed, the Android concepts involved, an on-device checklist,
measurements, open questions and the proposed next step. Newest phase at the bottom.

---

## Phase 0: fork hygiene, privacy contract, CI, audit (2026-10-15)

Branch: `claude/phone-agent-ik31nz` (the cloud session's push access is pinned to this name; CI
triggers on `claude/phone-agent**`, so it covers both).

### What changed

| Commit | Summary |
|---|---|
| Privacy contract | Firebase (Analytics, FCM), ML Kit/AICore, Play services TFLite, oss-licenses, scheduled notifications and the feedback key removed. `firebaseAnalytics` is a no-op stub. MediaPipe's usage-stats uploader replaced by `:telemetry-stub`. Static licenses screen. Manifest: no cleartext, no backup or device transfer, WebView metrics and Safe Browsing off. |
| Privacy guard plugin | `./gradlew :app:privacyGuard`: dependency/plugin ban, license report, stale-license-list check, bytecode check that the telemetry stub covers every MediaPipe reference. |
| Offline model list | Bundled snapshot of upstream `model_allowlists/1_0_20.json`; GitHub contacted only by Settings > Refresh model list. |
| Rename | applicationId `io.github.monkeygamer2010.phoneagent`, label/title "PhoneAgent", placeholder icon, NOTICE, `ci` build type with env-based signing. |
| CI | `build-apk.yml` (guard, unit tests, ci APK, rolling `phoneagent-ci` pre-release); upstream workflows made manual-only. |
| Docs | CLAUDE.md, DECISIONS.md, SECURITY.md, NETWORK.md, this log, README header. |

### Android concepts in this phase

- **Transitive dependencies.** A library brings its own dependencies along. Upstream's
  `SkillManager` used `androidx.documentfile` without declaring it; it only compiled because
  Firebase pulled it in. Removing Firebase broke the build until we declared it. Gradle's
  `exclude(group = ...)` removes a transitive dependency for one configuration or for all.
- **Manifest merging.** Your `AndroidManifest.xml` is only part of the final one: every library
  contributes permissions, services, receivers and providers. That's how upstream ended up
  declaring `AD_ID`, AdServices attribution and `READ_PHONE_STATE` without asking for them.
  `tools:node="remove"` deletes an entry a library adds (we use it for WorkManager's
  `RECEIVE_BOOT_COMPLETED`). Check the result in
  `app/build/intermediates/merged_manifests/<variant>/.../AndroidManifest.xml`.
- **Lazy class linking.** Android's runtime (ART) resolves a referenced class or method the
  first time code runs, not at install. A missing class is a crash in the middle of a tile, not
  a build error. That's why the telemetry stub can work at all (same class names, so MediaPipe
  links against ours), and why we added a bytecode check to catch gaps at build time.
- **applicationId vs. package.** The applicationId is the app's identity on the device (installs,
  updates, data folder). The Kotlin package (`com.google.ai.edge.gallery`) and the Gradle
  `namespace` are code organisation and stay as upstream, which keeps merges easy.
- **Build types and signing.** Android only installs an update if it is signed with the same key
  as the installed app. Each machine has its own debug key, so a CI debug-signed APK can't update
  a locally built one (and vice versa). The `.dev` suffix makes the CI build a separate app, and
  CI caches its debug key so CI builds update each other.
- **Backup vs. device transfer.** On Android 12+, `allowBackup="false"` stops cloud backup but
  not device-to-device migration; `dataExtractionRules` controls both.
- **WorkManager.** It stores scheduled work in its own database and normally reschedules it after
  a reboot (hence its boot permission). Upstream's model downloads run on WorkManager, which is why
  an interrupted download resumes on the next start.

### Audit results

Dependencies (release runtime classpath): 368 modules before, 303 after. All 303 have a license
in their POM: Apache-2.0 285, MIT 12, BSD-2-Clause 4, BSD-3-Clause 2 (protobuf), BSD 1
(camera-core), and `checker-compat-qual` dual GPL-2.0-with-Classpath-Exception / MIT (MIT
applies). Full list: `app/src/main/assets/phoneagent/third_party_licenses.tsv`, also printed by
the guard in CI.

Found and removed or neutralised:

| Item | Where | What it did |
|---|---|---|
| Firebase Analytics + AppMeasurement | app deps | usage analytics, advertising-ID access |
| Firebase Cloud Messaging | `GalleryFcmMessagingService` | push messages; its `deeplink` extra let any app open any URL |
| Firebase Installations | transitive | install ID registration |
| ML Kit GenAI / AICore | `runtime/aicore` | Play-services-based runtime, proactive model download at startup |
| Play services TFLite (java, gpu, support) | app deps | unused by any code |
| Play services oss-licenses + plugin | Settings | proprietary license viewer |
| **MediaPipe usage stats** | `tasks-core` → datatransport | Clearcut upload (`COREML_ON_DEVICE_SOLUTIONS`) on each MediaPipe task start |
| Startup model-list fetch | `ModelManagerViewModel` | GitHub request on every launch |
| Scheduled notifications + boot receiver | `notifications/` | AlarmManager jobs, start at boot |
| `FEEDBACK_API_KEY` | build config | unused leftover |

Network call sites that remain are all owner-initiated; see `docs/NETWORK.md`. LiteRT-LM has no
network code (no Java networking classes, no socket imports in `liblitertlm_jni.so`).
`libicing.so` (MediaPipe retrieval) is a local search index.

### Measurements

| | Before (upstream) | After |
|---|---|---|
| Debug APK size | 265.2 MB | 252.9 MB |
| `ci` APK size (release-like, not minified) | n/a | 226.4 MB |
| Release runtime modules | 368 | 303 |
| Merged manifest: permissions | 26 | 15 |
| Services / receivers / providers / activities | 13 / 9 / 4 / 9 | 4 / 4 / 2 / 5 |
| Firebase / Play services / ML Kit classes in dex | many | 0 |
| Unit tests | 0 | 7 (licenses parser, bundled list, model-list snapshot, stub) |
| `privacyGuard` run time (warm) | n/a | about 1.5 min |

Most of the APK is native code (LiteRT-LM, MediaPipe) for four ABIs; Phase 6 can limit it to
arm64-v8a and enable R8.

### On-device checklist (Galaxy S25+)

Install the CI build (`PhoneAgent-ci.apk` from the `phoneagent-ci` pre-release once the workflow
has run) or a local `assembleDebug`. Report each item as pass/fail plus notes.

1. Launcher shows **PhoneAgent** with the teal phone icon; the home title reads "Phone Agent".
   Upstream's first-run terms dialog still appears (open question 1).
2. **Privacy, startup and idle:** follow `docs/NETWORK.md` > Option A (PCAPdroid). Expected: zero
   connections from PhoneAgent during 2 min on the home screen plus 15 min idle.
3. Settings: no analytics switch. "Model list" says "Using the list bundled with this app
   (1.0.20)". "View licenses" opens the offline list (303 libraries) and the Apache text.
4. LLM Chat lists Gemma-4-E4B-it and Gemma-4-E2B-it.
5. Download **Gemma-4-E4B-it** (3.66 GB, Wi-Fi) **without** a Hugging Face login. Does it start
   and finish? If it asks for a login, note the message (fallback: model import). With PCAPdroid
   on, only Hugging Face hosts should appear.
6. Chat with E4B on GPU: one short prompt. Note time to first token if shown. This checks
   LiteRT-LM still works after the removals.
7. Settings > **Refresh model list**: expect "Downloaded 11 models..." and exactly one connection
   to `raw.githubusercontent.com`. Then **Use bundled list**.
8. **MediaPipe tiles (stub check)**, each with PCAPdroid running. Expected: no crash, and no
   connections except the model downloads you start (no `firebaselogging`, `play.googleapis.com`):
   1. **Scrapbook**: download "Magic touch" (30 MB), open a photo, tap a subject to cut it out.
   2. **Smart Album**: download EmbeddingGemma-2 (485 MB), index the sample or your photos,
      search for something. The optional sample photos come from `dl.google.com` (expected).
   3. **Video Moment Finder**: add a short video, run a search.
   If any tile crashes, send only the crash text (`adb logcat -b crash -d`, or the text of the
   crash dialog) and we hide that tile in a follow-up. Don't send a full bug report: it includes
   the whole log, which in Phase 0 builds could hold past prompts.
9. Agent Chat: the "schedule a reminder" chip and the `schedule-notification` skill are gone.
10. Optional: after a reboot, Settings > Apps > PhoneAgent > Battery shows no background use.

### Open questions

1. Upstream shows Google's app terms of service on first launch and links the Gemma terms. Keep
   them for now and decide in Phase 6, or remove the first-run ToS dialog now?
2. Assumptions to confirm or change (details in `docs/DECISIONS.md`): applicationId
   `io.github.monkeygamer2010.phoneagent`; interrupted downloads still auto-resume at start;
   refreshed model list applies after a restart.
3. Couldn't verify here that Gemma 4 downloads without a Hugging Face login (this sandbox blocks
   `huggingface.co`). Checklist item 5 settles it.

### Next step

Phase 1, feasibility spike without a model: the AccessibilityService, a "Screen Inspector" (5 s
delay, then dump the compact tree of the foreground app plus a token estimate), tap/type/scroll
tests, the Firefox private-tab checklist (ACTION_VIEW with "open links in a private tab",
`org.mozilla.fenix.OPEN_PRIVATE_TAB`, UI driving), launching Firefox from the service, and unit
tests for the tree serializer and id mapping. Then stop for your results.

---

## Phase 0.1: privacy fixes from the Phase 0 audit (2026-10-18)

An independent audit of Phase 0 against the privacy contract found upstream behaviour that sent
data off the phone, or could, without an obvious owner action. This phase fixes the items the
owner chose, then a read-only pass over four more areas (tool authorization, other entry points,
native logging, OS-level capture) and fixes what it confirmed. `docs/NETWORK.md` is rewritten.

### What changed

| Commit | Summary |
|---|---|
| Model list refresh | The ci build asked GitHub for `1_0_20-ci.json`; the build-type suffix is now dropped (unit test). |
| Downloads | No resume at app start: a **Resume download** button instead, which keeps your choice about extra files. Download work needs a network, and work queued by an earlier process is not run by the system scheduler. The Hugging Face token goes only to `https://huggingface.co` (unit test). The login service (which binds Firefox's Custom Tabs) is created only when a login starts; login shows a message because it isn't configured. |
| Remote images | Markdown images become links (unit test); the image loader refuses `http(s)`. |
| Photo library | No Geocoder: raw coordinates. |
| Dictation | Only the on-device recognizer, with "prefer offline"; the mic button is hidden without one. |
| Skill WebViews | Serve only skill files; per-skill **Allow network** switch, off by default; CSP, `blockNetworkLoads`, no WebRTC, no service-worker network; script and page names checked (unit test); built-in skills validate their URL parameters; camera/mic requests denied; community-skills page hidden. |
| MCP | Only enabled servers, only while Agent Chat is visible; streams closed on leave and in the background; removing a server deletes its auth header. |
| Confirmations | Calendar, contacts, email/SMS, maps, network-enabled skills and MCP calls ask **Allow once** every time (unit test). |
| Secrets | Password fields for skill secrets, MCP header values and the HF token; Settings no longer shows a token prefix. |
| App links | Upstream's exported `com.google.ai.edge.gallery://` filter removed; links are accepted only through a non-exported alias. |
| Log stripping | Bytecode transform removes `Log.v/d/i` from the app and all libraries in release and ci builds. |
| Guard | Rules are constants; disabling a check stops the build; more forbidden groups; file dependencies fail; new `check<Variant>Apk` on every assemble; CI requires both pass reports. EmojiCompat's startup initializer removed. |
| Gap pass | Intents run only while an enabled skill offers them (unit test); skill secrets keyed by the running skill; LiteRT-LM native logs limited to errors in release/ci; content capture, autofill, keyboard learning and Recents screenshots opted out. |
| Docs | NETWORK.md rewritten, SECURITY.md and DECISIONS.md updated, this entry. |

### Android concepts in this phase

- **WorkManager and JobScheduler.** WorkManager saves queued work in its own database and hands
  it to the system's JobScheduler, which can start the app in the background to run it, even
  after the app was closed. The fork tags download work with an id that is new in every process,
  so work left over from an earlier run fails immediately instead of downloading.
- **Exported components.** An activity marked `exported="true"` can be started by any app with an
  explicit intent, whatever its intent filters say. Filters only decide which implicit intents
  (for example a tapped link) reach it. That is why removing the deep-link filter was not enough,
  and why links now go through a non-exported `activity-alias`: when an alias starts the
  activity, `intent.component` names the alias, which only this app can use.
- **WebView request hooks.** `shouldInterceptRequest` sees most loads, but not WebSockets, WebRTC
  or service workers. The sandbox therefore layers a Content-Security-Policy (enforced by the
  page itself), `blockNetworkLoads`, a document-start script that removes WebRTC, and
  service-worker settings.
- **`WebViewAssetLoader`.** It serves app files under a fake `https://appassets.androidplatform.net`
  origin, so pages get normal web security without `file://`. Whatever folder you mount is
  readable by every page on that origin, hence mounting only `files/skills`.
- **AGP bytecode instrumentation.** `AsmClassVisitorFactory` rewrites compiled classes (including
  libraries) before dexing. Each `Log.d(...)` call becomes "drop the arguments, push 0", which
  keeps the bytecode valid without R8.
- **Activity-level privacy switches.** `ContentCaptureManager`, `importantForAutofill`,
  `setRecentsScreenshotEnabled` and the keyboard flag `IME_FLAG_NO_PERSONALIZED_LEARNING` control
  what the system, autofill services and keyboards get to see. Compose sets editor flags through
  `InterceptPlatformTextInput`.

### Measurements

| | Phase 0 | Phase 0.1 |
|---|---|---|
| `ci` APK size | 226.4 MB | 226.4 MB |
| Classes in the ci dex files | 54,500 (approx.) | 54,517 |
| `Log.v/d/i` call sites in the ci APK | present (app and libraries) | 0 |
| Native libraries importing socket/DNS functions | 0 | 0 (now checked on every build) |
| Merged manifest (ci): permissions; services / receivers / providers / activities | 15; 4 / 4 / 2 / 3 | 15; 4 / 4 / 2 / 3, plus 1 non-exported alias |
| Unit tests | 12 | 20 |
| `assembleCi` incl. APK check (incremental) | n/a | 4 min 14 s on the cloud VM |

### On-device checklist (Galaxy S25+)

Install the CI build (`PhoneAgent-ci.apk` from the `phoneagent-ci` pre-release). Report each item
as pass/fail plus notes. Items marked (re-check) repeat Phase 0 items with the corrected
expectation.

1. **Privacy, startup and idle (re-check):** `docs/NETWORK.md` > Option A, now capturing all apps.
   Expected: zero PhoneAgent connections in 2 min on the home screen plus 15 min idle, and nothing
   from Google Play services or Firefox at the moment you open PhoneAgent.
2. Settings > Model list reads "(1.0.20)". **Refresh model list**: expect "Downloaded 11
   models..." and one connection to `raw.githubusercontent.com`. Then **Use bundled list**.
   (re-check: this failed in the Phase 0 ci build.)
3. Settings > Hugging Face token: shows "Saved (hidden)" after pasting a token; the field is
   masked and the keyboard shows no suggestions.
4. **Resume:** start a model download, then force-stop the app mid-way. Reopen: nothing downloads
   by itself (PCAPdroid quiet), and the model shows **Resume download** with "(paused)". Tap it:
   the download continues from where it stopped.
5. With PCAPdroid on, download a model with a token saved: the token-bearing connections go only
   to Hugging Face hosts (TLS hides headers; just check no Google host is contacted for a
   Hugging Face model).
6. A chat reply containing an image (ask: "Reply with exactly: ![cat](https://example.com/cat.png)")
   shows a link "cat", not an image, and PCAPdroid shows no `example.com` connection.
7. Smart Album: open a photo with location: coordinates like "48.1351, 11.5820", no place name.
8. Hold-to-dictate: if the mic button shows, dictation works with airplane mode on. If the phone
   has no on-device recognizer, the button is hidden and text input works.
9. **Agent Chat skills:**
   1. Skill manager: every skill shows **Allow network**, off.
   2. Ask "What does Wikipedia say about Munich?": the skill fails, the reply says network is off
      for `query-wikipedia`, and PCAPdroid shows no `wikipedia.org`.
   3. Turn **Allow network** on for `query-wikipedia`, ask again: an **Allow once** dialog appears
      first; after allowing, the answer arrives and PCAPdroid shows `en.wikipedia.org`.
   4. `interactive-map` with network off: no map, and the reply says why. `mood-tracker`: log a
      mood, open the dashboard (works offline; the chart and font may be missing).
   5. Turn on the `send-email` skill (off by default) and ask the model to send an email: an
      **Allow once** dialog shows the parameters; **Don't allow** stops it. Turn the skill off
      and ask again: the model is told no enabled skill offers it.
   6. Add skill: there is no "View community skills" option.
10. **MCP** (only if you use one): disabled servers are never contacted; leaving Agent Chat or
    pressing Home closes the connection (PCAPdroid shows it ending); every tool call asks.
11. **Mobile Actions:** "create a contact for Alex 555 0100", "email bob@example.com hello", "show
    the Eiffel Tower on the map", "add a meeting tomorrow at 10": each asks first; flashlight
    does not.
12. **Links from other apps** (needs adb): `adb shell am start -n
    io.github.monkeygamer2010.phoneagent.dev/com.google.ai.edge.gallery.MainActivity -d
    "com.google.ai.edge.gallery://llm_chat"` opens PhoneAgent's home screen, never the chat.
    `adb shell am start -a android.intent.action.VIEW -d "com.google.ai.edge.gallery://llm_chat"`
    finds no app. A download that finishes while the app is in the background still posts a
    notification that opens the model.
13. **OS capture:** open Recents: PhoneAgent shows a blank card, not a screenshot. Typing a
    prompt does not add new words to the keyboard's suggestions later (best-effort; depends on
    the keyboard).
14. **MediaPipe tiles (re-check of Phase 0 item 8):** Scrapbook, Smart Album, Video Moment Finder
    open and work, with no connections except the model downloads you start. If a tile crashes,
    send only `adb logcat -b crash -d`.
15. Tiny Garden: the game loads and plays offline.

### Open questions

None new. Assumptions made in this phase are listed in `docs/DECISIONS.md` under Phase 0.1; the
ones most worth a look: camera and microphone stay denied for all skill pages (no per-skill
switch yet), MCP connections also close when the app goes to the background, and Safe Browsing
stays off for skill pages that you allow onto the network.

### Next step

Phase 1, feasibility spike without a model (unchanged from Phase 0): the AccessibilityService,
the Screen Inspector, tap/type/scroll tests, the Firefox private-tab checklist and unit tests for
the tree serializer and id mapping. Waiting for the owner's OK.

