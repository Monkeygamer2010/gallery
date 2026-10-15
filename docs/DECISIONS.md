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

- `CustomTask` in `customtasks/phoneagent`, modelled on mobileactions; Hilt `@Provides @IntoSet`;
  `keepModelAlive = true`.
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
- An interrupted model download still resumes at the next app start (upstream behaviour): it
  continues a download the owner started. Documented as row 1a in `docs/NETWORK.md`.
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
