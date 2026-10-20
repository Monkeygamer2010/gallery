# PhoneAgent: decisions

Decisions from the project brief, recorded so they survive context resets. Newer entries at the
bottom of each section override older ones. "Assumption" marks a call made without asking; change
it by editing this file.

## Goals and scope

- Personal automation tool first, open-source release later, and a learning project. Each phase
  status explains the non-obvious Android concepts it touches.
- Everything runs on the device. No voice, no cloud models, no telemetry.
- Test device: Galaxy S25+ (12 GB RAM). Default browser: Firefox with uBlock Origin.
- Working name **PhoneAgent** (placeholder). No Google branding.

## Working agreement

- Work happens on branch `claude/phone-agent*`; never on `main`. No force-push, no tags.
  - Assumption: the cloud session is pinned to `claude/phone-agent-ik31nz` (the push proxy only
    allows that name). CI triggers on `claude/phone-agent**`, so both names work.
- One phase at a time. After each phase: commit, push, short status (what changed, on-device
  checklist, measurements, open questions, next step), append the status to `docs/LEARNINGS.md`,
  then wait for the owner's OK.
- Ask the owner only about decisions that are theirs; otherwise state the assumption here.
- Never print or commit secrets or keystores.

## Repo constraints (stay mergeable with upstream)

- New code lives under `com.google.ai.edge.gallery.phoneagent`. No Kotlin package rename.
- Upstream files get small, appended blocks only. Rename touches only applicationId, label and
  icon (plus the visible title strings).
- Keep `versionName` (1.0.20) and the pinned LiteRT-LM version unless a change is required.
- Keep LICENSE and the upstream file headers. NOTICE lists the fork's changes.
- Upstream's model list (`ALLOWLIST_BASE_URL` + versionName) knows nothing about our task id. The
  PhoneAgent task lists `Gemma-4-E4B-it` (default) and `Gemma-4-E2B-it` in `modelNames`, and
  imported `.litertlm` files are selectable.
- Hugging Face login is only needed for gated models. Gemma 4 is Apache-2.0; model import is the
  fallback if anonymous download fails.
- Context budget is ~4k-10k tokens and GPU math can distort numbers: observations are compact and
  refer to elements by id only, never by coordinates.

## Privacy contract

- No network traffic at startup or idle. Network only on explicit owner actions: model download,
  "Refresh model list", installing uBlock Origin (Phase 5 fallback only) and the browser's own
  traffic.
- The model list loads from the on-disk cache, else from a bundled snapshot in assets (generated
  from upstream `model_allowlists/<versionName>.json`). GitHub is contacted only on "Refresh model
  list".
- Removed in one re-appliable commit: Firebase (init, analytics, FCM, manifest entries, receivers,
  analytics toggle; `firebaseAnalytics` becomes a local no-op stub), ML Kit GenAI / AICore
  (including the startup auto-download), feedback upload (`FEEDBACK_API_KEY`),
  play-services-oss-licenses and its plugin (replaced by a static licenses screen), scheduled
  notifications and boot receiver, and the c2dm / GSERVICES / GET_ACCOUNTS /
  RECEIVE_BOOT_COMPLETED / AICore permissions.
- Play Services TFLite is proprietary and GMS-dependent. Replace with open-source LiteRT if the
  diff is small, else ask. If removing a proprietary dependency breaks a tile, stop and ask.
- Play Services TFLite turned out to be unused by any code, so it is removed with no
  replacement.
- MediaPipe (Scrapbook, Smart Album, Video Moment Finder) uploads usage stats through Firebase's
  Clearcut transport (`transport-runtime` + `transport-backend-cct`, log source
  `COREML_ON_DEVICE_SOLUTIONS`). Owner decision (Phase 0): keep the open-source `transport-api`
  interfaces, exclude the uploader and every `com.google.firebase` artifact, and replace the
  runtime with no-op classes in the `:telemetry-stub` module. A build check fails if MediaPipe
  references any datatransport/firebase class or member the classpath doesn't provide. The
  stub is documented in `docs/NETWORK.md`; the three tiles are on the on-device checklist and
  get hidden in a follow-up if one crashes.
- Manifest: WebView metrics opt-out, Safe Browsing off, `usesCleartextTraffic=false`,
  `allowBackup=false`. The build never needs `google-services.json`.
- Never log screen contents.
- CI guard fails on any `com.google.firebase`, `com.google.android.gms` or `com.google.mlkit`
  dependency or the google-services plugin, unless the owner approves an exception in
  `docs/NETWORK.md`. It prints the full dependency and license list.
- `docs/NETWORK.md` lists every outbound connection and how to verify startup/idle silence.

## Agent behaviour

- Autonomy: runs freely, but code-level confirmation before send/post/submit, purchases,
  deletions, install/uninstall, permission or settings changes, and typing or submitting on a site
  not yet visited this session. The dialog shows the domain and the exact text. After the owner
  confirms, the agent may press Send.
- Category locks: Money & credentials, Email & messaging, App stores & installers, System
  Settings are refused until the owner flips an "I understand" switch per category. Money &
  credentials asks every time; the others offer a one-time "don't show again".
- Everything else is default-deny via a per-app allowlist managed in-app. All of this is enforced
  in code; never rely on the model refusing.
- Prompt injection: screen and page text is untrusted data. Delimit it, tell the model never to
  follow instructions in it, and give it no tool that can change policy, allowlist or locks.
  Redact password fields. Never put clipboard contents or secrets in the model context. Never
  operate this app or Settings > Accessibility. `open_url` accepts https only, caps URL length
  and logs every URL in the run log.
- Entry points: in-app text box and a Quick Settings tile that opens a small type-a-task popup.
- Model: Gemma 4 E4B default; E2B selectable with a warning. Thinking off by default (toggle).
  Speculative decoding (MTP) on when the model supports it.
- Results: in-app Results screen with copy, share, save-as-file (system picker). Keep the last 10
  runs with a clear button. No auto-save. No background scheduling.
- Web tasks first: search and summarize, fill and submit forms, extract data. No logged-in
  sites: Firefox private tabs only; never read normal tabs, history or saved logins.

## Architecture

- `CustomTask` in `phoneagent/task` (all fork code lives under `phoneagent/`), modelled on
  mobileactions; Hilt `@Provides @IntoSet`; `keepModelAlive = true`.
- ToolSet (12 or fewer): `read_screen`, `tap(id)`, `type_text(id,text)`, `scroll(dir)`, `back`,
  `home`, `open_app(name)`, `open_url(url)`, `note(text)`, `save_result(title,text)`,
  `finish(summary)`. Later: compose-only intent tools (message, email, dial, alarm) that prefill
  and never send without confirmation, reusing `IntentHandler`.
- Tool calls run with LiteRT-LM `automaticToolCalling = false`, so our code executes (and
  policy-checks) every call.
- AccessibilityService: node actions first, `ACTION_SET_TEXT` with paste fallback,
  `dispatchGesture` last. `read_screen` returns a compact numbered list `[id] role "label"
  state`: visible nodes only, our overlay excluded, about 800 tokens max, deduped, truncated,
  plus URL/domain in a browser. Empty or secure windows: say so and stop; never bypass.
- Browser backend interface. Primary: drive Firefox via accessibility in a private tab. Try (a)
  ACTION_VIEW with "open links in a private tab", (b) `org.mozilla.fenix.OPEN_PRIVATE_TAB` to
  IntentReceiverActivity, (c) driving the UI. Verify Firefox is the default browser; refuse to
  read a non-private tab; never change browser settings or extensions. Fallback: Phase 5.
- Background activity starts: launch the first app from the visible popup/activity; later
  launches go through the UI via accessibility (home + launcher). Ask before adding
  `SYSTEM_ALERT_WINDOW`.
- Loop: stateless steps. Each step rebuilds a small prompt (goal, notes of 400 tokens or fewer,
  last 5 one-line actions, current observation, "call exactly one tool") and resets the
  conversation. Max 15 steps, per-step timeout, abort on a repeated identical action + screen.
- Foreground service started from the visible UI with the right FGS type, notification Stop
  action, Stop chip + step counter via `TYPE_ACCESSIBILITY_OVERLAY`. Verify the engine survives
  backgrounding on GPU and CPU.
- Onboarding: enable the service; Android 13+ "Allow restricted settings"; Samsung path
  (Settings > Accessibility > Installed apps); Samsung battery "Unrestricted" / never sleeping;
  Firefox: uBO "Run in private browsing" and "Open links in a private tab"; add the QS tile.
  Detect service-off. Don't set `isAccessibilityTool`. If Advanced Protection blocks the
  service, explain instead of working around it.

## Phases

0. Fork hygiene, CI, audit.
1. Feasibility spike without a model (Screen Inspector, tap/type/scroll, Firefox private tab,
   launches). Stop for the owner's results.
2. Task + E4B + core tools + loop + foreground service + QS tile; read-only web tasks. Accept:
   5 scripted search-and-summarize tasks, 3 or more succeed.
3. Forms and extraction: confirmations, new-domain gate, note/save_result, Results screen,
   injection hardening.
4. Safety layer: allowlist UI, category locks, message/call/settings tools, cross-app chores.
5. Only if the spike fails: GeckoView fallback with uBO (installed from AMO on first run, owner
   initiated), bundled agent WebExtension, wiped private sessions, Gecko traffic audit.
6. Polish and release prep (README, screenshots, license audit, release checklist; the owner
   signs release builds locally).

## Build and CI

- Build from `Android/src` with `./gradlew assembleDebug`. No device here: unit-test pure logic
  and hand the owner on-device checklists.
- `.github/workflows/build-apk.yml` builds on push to the branch and publishes the APK to a
  rolling pre-release. Build type `ci` (applicationIdSuffix `.dev`) is signed from secrets
  `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, falling back to the debug key
  while they are unset (the owner adds them later; don't ask). Real release keys never go on
  GitHub.

## Assumptions made in Phase 0 (change any by editing here)

- applicationId `io.github.monkeygamer2010.phoneagent` (maps to the owner's GitHub Pages domain);
  the CI build adds `.dev`, so a CI build and a local debug build install side by side. Each one
  needs its own copy of the model (or use model import).
- Build type `ci` is release-like (not debuggable), so its speed measurements are representative.
- Interrupted model downloads: superseded in Phase 0.1 (owner decision: no auto-resume; a
  "Resume download" button instead).
- "Refresh model list" saves the new list and applies it at the next app start (reloading in place
  could duplicate models in tasks or orphan a loaded engine). "Use bundled list" deletes the
  downloaded copy.
- Removing the scheduled-notification code also removes Agent Chat's built-in
  `schedule-notification` skill and its try-out chip; the `schedule_notification` intent now
  returns a clear failure (the `learn-something-new` skill's optional reminder step fails
  gracefully).
- Device-to-device transfer is excluded along with cloud backup (`allowBackup=false` alone does not
  cover it on Android 12+).
- Upstream workflows `build_android.yaml` and `static.yml` are switched to manual-only
  (`workflow_dispatch`) so upstream syncs into `main` don't trigger them.
- CI caches its debug signing key (Actions cache, not the repo) so successive CI APKs update in
  place until the owner adds the signing secrets.
- The README gets a short fork header above upstream's unchanged text; the full rewrite is Phase 6.
- Upstream's first-run Google terms-of-service dialog and Gemma terms links are left as is for now
  (open question for Phase 6).
- New files carry an Apache-2.0 header with "Copyright 2026 The PhoneAgent Authors".

## Phase 0.1 (owner decisions and assumptions)

Owner decisions (from the Phase 0 audit review):
- Remote images are never loaded: Markdown images show as links (alt text); no tap-to-load yet.
- No Geocoder: Smart Album shows raw coordinates.
- AppAuth's Custom Tabs warmup happens only when the Hugging Face login is configured and used.
- No download auto-resume. A "Resume download" button resumes it, download work needs a network,
  extra files are resumed only if they were chosen, and the system scheduler cannot resume work
  queued by an earlier run.
- Skill WebViews: no network and no app-private storage by default; a per-skill "Allow network"
  switch, off by default; URL parameters validated; the app's private folder never served. The
  in-app "View community skills" page is hidden.
- MCP: only enabled servers, only while Agent Chat is on screen; streams closed on exit; stored
  auth headers never sent to disabled servers.
- Hold-to-dictate only with an offline on-device recognizer; never the default recognizer.
- WebView camera and microphone requests denied by default.
- Secret fields masked; token-prefix logs removed; debug logging off in release and ci; the
  upstream deep link not exported.
- Guard: APK-level check, more forbidden groups (Play Core, AICore SDK), not overridable from
  build scripts.
- Until Phase 4's policy engine: calendar, contacts and network-capable tools in the existing
  tiles ask for confirmation on every call.

Confirmed by the owner after Phase 0.1: camera and microphone stay denied for skill pages; MCP
connections close in the background; Safe Browsing stays off; "Always allow" stays removed until
Phase 4.

Assumptions (change any by editing here):
- Camera and microphone stay denied for every skill page, with no per-skill switch. The
  `text-spinner` skill therefore cannot use the camera.
- "Allow network" is stored per skill and keyed by where its code comes from (built-in folder,
  import folder or URL). Deleting or renaming a skill resets it. Skills loaded from a URL cannot
  run while it is off. Safe Browsing stays off for allowed skill pages too (privacy over phishing
  warnings; Firefox has its own).
- With "Allow network" on, skill pages may load any `https` URL; plain `http` is refused.
- MCP connections also close when the app goes to the background and reopen when Agent Chat is
  visible again.
- Confirmation scope: Agent Chat intents except reading the date and time, skills with network
  allowed, and every MCP call; Mobile Actions create contact, send email, show on map and create
  calendar event (not flashlight or Wi-Fi settings). "Always allow" is removed for now.
- An Agent Chat intent runs only while an enabled skill's instructions name it.
- Debug logs are removed by a bytecode transform (`Log.v/d/i`, app and libraries) because the
  release build does not run R8. Warnings and errors stay. LiteRT-LM's native logging is limited
  to errors in release and ci and warnings in debug.
- App links are accepted only through a non-exported alias used by the app's own notifications.
- EmojiCompat's startup initializer is removed (beyond the listed scope): it can ask Google Play
  services' font provider to download emoji fonts at startup. The system emoji font is used.
- The app opts out of content capture, autofill and Recents screenshots, and asks keyboards not
  to learn typed text. `FLAG_SECURE` is not set, so you can still take screenshots of the app.


## Phase 1 (assumptions; change any by editing here)

- The spike tools live behind Settings > **Open spike tools** (a separate, non-exported screen),
  not a home-screen tile; the PhoneAgent task arrives in Phase 2.
- Every test runs in the app in front after a 5 s countdown. PhoneAgent moves itself to the
  background when you start a test and, by default, the service brings it back with the result.
- Results (compact read, details, stats) are kept in memory only, at most 5, and never saved,
  logged or uploaded. **Copy** puts them on the clipboard marked as sensitive. Saved instance
  state is not used for them.
- The service never acts on PhoneAgent itself or in Settings, and it reads PhoneAgent's own
  screen as empty.
- Tap: node click on the element or its nearest clickable parent (up to 5 levels), then a tap
  gesture. Type: set-text, then paste (the clipboard is cleared afterwards), then optional IME
  Enter. Scroll: the largest scrollable node's scroll action, then a swipe.
- Token counts are estimates (about 4 characters per token for Latin text, 1 per character for
  other scripts) until Phase 2 can use the model's tokenizer.
- Private-tab detection is a heuristic for the spike: browser UI (outside the web view) whose
  view id or text mentions "private". Your results decide what Phase 2 relies on.
- Test pages are public: Wikipedia, a DuckDuckGo search and `httpbin.org/forms/post` as the
  simple form page.
- Package visibility is limited to the Firefox builds and "who handles https links".
- The service listens only to window-change events and keeps nothing from them. No
  `SYSTEM_ALERT_WINDOW`, no overlay yet (the Stop chip is Phase 2).

## Phase 2 (assumptions; change any by editing here)

- **Scope until Phase 4's allowlist:** the agent reads and acts only in Firefox private tabs.
  Other apps are refused, and their screens are not even converted: only the package and app
  name are known. Firefox's own settings, menus and add-on pages (no web page and no home page on
  screen) are refused too; Back is allowed there in private mode so the agent can leave.
- `open_app` opens only Firefox, as a new private tab (`OPEN_PRIVATE_TAB` without an address).
  `open_url` opens every address in a new private tab; public `https://` hosts only (no IP
  addresses, `localhost`, `.local`, `.lan`, `.internal`, `.home.arpa` or `.onion`), at most 2048
  characters. Every address is listed in the run log.
- `note` and `save_result` exist now, in memory only (shown on the task screen with Copy). The
  Results screen and keeping the last 10 runs stay in Phase 3.
- `type_text` has an optional `submit` flag that presses Enter afterwards (needed for searches).
  Typing into password fields is refused in code.
- The system prompt suggests DuckDuckGo (`https://duckduckgo.com/?q=`) for searches and English
  Wikipedia for facts about well-known topics, and asks the model to name the website in its
  answer.
- **Start** needs the accessibility service, Firefox and Firefox as the default browser.
  Battery "Unrestricted" and notifications are recommended, not required (the Stop chip works
  without notifications).
- A run begins by opening Firefox's private home page from the visible task screen (the first app
  launch comes from the visible app); later launches come from the accessibility service.
- Model load for this task: text only (no vision or audio encoders), context of at least 4096
  tokens, speculative decoding on when the model file supports it; if the engine does not start
  with it, it starts without and the screen says so. Thinking is passed as `enable_thinking`, as
  upstream does.
- Sampling for agent steps is fixed in code (temperature 0.3, top-k 40, top-p 0.95) for steadier
  tool choices; the model settings dialog still sets the accelerator (GPU or CPU) and maximum
  tokens, but its sampling values don't apply to Phone Agent. Reply cap 512 tokens (2048 with
  thinking); step timeout 60 s (180 s with thinking). Constrained decoding stays off; it is a
  lever if tool calls turn out unreliable.
- Loop guard: the same action on the same screen anywhere in the run (not only twice in a row)
  ends it, as do 3 replies in a row without a usable tool call or 3 refused or failed actions in a
  row. A tool call left as JSON in the reply text is accepted.
- Before tapping or typing, the runner reads the screen again and acts only if the id still names
  the role and label the model saw.
- Foreground service type `specialUse` (permission `FOREGROUND_SERVICE_SPECIAL_USE`, added to the
  APK check's approved list): none of the specific types describes an agent run. Its
  notification shows only the step counter, never the task or anything from the screen, because
  notification listeners can read notifications.
- When a run finishes or stops on its own, PhoneAgent comes back to the front with the answer;
  after **Stop** you stay where you are.
- The Stop chip is an accessibility overlay at the right edge of the screen. It is hidden and lets
  touches through while a gesture runs, so a tap can never land on it.
- The Quick Settings popup hands the typed task to the task screen in memory (never in an
  intent), opens PhoneAgent and starts once the model is ready. It is excluded from Recents.
- The model stays loaded after you leave the task screen (`keepModelAlive`); after each run a
  plain conversation replaces the agent's, so other tasks that reuse the loaded model don't see
  the agent's prompt and tools.
- Run data (log with element labels, addresses, notes, results, answer, measurements) stays in
  memory. **Copy measurements** contains no screen text and no addresses.
- Measurements: model load time is measured around engine creation; memory is the app process's
  PSS (`Debug.getPss()`) before the run, after each step (peak) and after the run.
- Imported `.litertlm` models are offered in the task with a warning.
- Phone Agent appears after upstream's tasks on the home screen (upstream's ordering list is not
  edited, to keep the diff small).

