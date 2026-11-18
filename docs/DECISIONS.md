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
- `versionName` is the upstream tag plus a PhoneAgent suffix (`1.0.23-phoneagent.1` since the
  1.0.23 sync), never the plain tag. Keep the pinned LiteRT-LM version (0.18.0) unless a change is
  required.
- Upstream features the fork doesn't ship stay in the tree at upstream's paths but out of the
  build: one exclusion list (`Android/src/app/phoneagent-excluded-sources.txt`) and inert
  stand-ins for the types the rest of upstream's code refers to (`app/src/phoneagentStandins`).
  Recipe for the next sync: `docs/UPSTREAM_SYNC.md`.
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
- ToolSet (12 or fewer): `tap(id)`, `type_text(id,text)`, `scroll(dir)`, `back`, `home`,
  `open_app(name)`, `open_url(url)`, `note(text)`, `save_result(title,text)`, `finish(summary)`.
  (`read_screen` was dropped in Phase 2.1: every step already shows the screen, read after the
  last action settled.) Later: compose-only intent tools (message, email, dial, alarm) that prefill
  and never send without confirmation, reusing `IntentHandler`.
- Tool calls run with LiteRT-LM `automaticToolCalling = false`, so our code executes (and
  policy-checks) every call.
- AccessibilityService: node actions first, `ACTION_SET_TEXT` with paste fallback,
  `dispatchGesture` last. Each step's screen read is a compact list: links and controls are
  numbered `[id] role "label" state`, text is plain (a paragraph is one line, its links numbered
  inline), visible nodes only, our overlay excluded, about 800 tokens max, deduped, truncated,
  plus URL/domain in a browser (details: Phase 2.2). Empty or secure windows: say so and stop;
  never bypass.
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
- `.github/workflows/build-apk.yml` builds on push to `claude/phone-agent**` and
  `upstream-sync-**` and publishes the APK to a rolling pre-release: `phoneagent-ci` for the main
  build branch (`claude/phone-agent-ik31nz`) only, `phoneagent-ci-sync` for every other branch.
  Build type `ci` (applicationIdSuffix `.dev`) is signed with the owner's key from the repository
  secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (set 2026-11-17; see
  "Signing" below). A run without them still builds and tests, but publishes nothing.

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
  place until the owner adds the signing secrets. (Superseded on 2026-11-17: see "Signing".)
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

## Phase 2.1 (assumptions; change any by editing here)

- Web page nodes (inside a web view) count as on screen when their bounds overlap the visible part
  of the web view; their own "visible to user" flag is ignored (GeckoView sets it from Gecko's
  lazily built viewport cache). Native views keep the flag. Page nodes off screen or without
  bounds give no line, but their children are still checked.
- Text lines keep up to 400 characters; element labels (buttons, links, inputs) up to 100.
- Agent reads copy at most 5000 nodes and skip page parts more than one screen height away.
- No `read_screen` tool. After an action the runner waits for the screen to change (2.5 s for a
  tap, 4 s for navigation, 1.5 s for small actions), then for two identical reads with any web
  page's text present, at most 10 s after navigation or a tap and 3 s after small actions. That
  read is the next observation.
- A web page with no text in the read after the wait stops the run ("can't see the page content").
- Scroll uses the web view's own scroll action first (GeckoView: about 80% of the visible
  height), then the largest scrollable node, then a swipe.
- The run log keeps each step's observation text, counts and node classification (memory only,
  Copy run log).
- Firefox's address is also read from the start of its Compose toolbar text.
- Private tabs: each `open_url` still opens a new private tab. Closing the agent's tabs would mean
  operating Firefox's tab tray, one of Firefox's own screens, which the policy forbids, and Firefox
  has no public intent for it. Close them with Firefox's "Close private tabs" notification. (Phase
  2.2: the address bar on a private tab's own screen is used for searches and as `open_url`'s
  fallback, under the same address rule as `open_url`.)
- One Phone Agent engine at a time, owned outside the activity; a different model or accelerator
  closes the old engine first, and any other task loading a model closes Phone Agent's first.
- "Keep the model loaded" defaults to Off; the other choices are "while PhoneAgent is open" and
  5/15/30 minutes after the last use. Android's low-memory and background-trim signals unload it
  whenever no task runs (with the minutes setting, a background trim unloads only when memory is
  actually low). No preloading in this pass.
- The tile's model is a setting (E4B by default, E2B possible).
- `FLAG_SECURE` on the Phone Agent, spike and popup screens unless "Allow screenshots" is on (default
  off). Other screens keep the view-level content-capture exclusion only.
- targetSdk stays 37. `ACCESS_LOCAL_NETWORK` is not requested (no local MCP server is set up).

## Phase 2.2 (assumptions; change any by editing here)

- **open_url** sends `ACTION_VIEW` with the address and the extra `private_browsing_mode=true` to
  Firefox's `IntentReceiverActivity`, which opens it in a private tab. (`OPEN_PRIVATE_TAB`, used
  before, is the launcher's "New private tab" shortcut and ignores the address.) If after the wait
  Firefox shows a private tab without a web page, the address is typed into that tab's address
  bar with Enter. A changed screen that holds still for 2.5 s without any web view counts as "no
  page coming". The model is told when no page loaded within 10 s; a page outside private
  browsing stops the run.
- **The address bar** obeys `open_url`'s rule in code: text that Firefox would treat as an
  address (a known scheme, or one word with a dot, slash or colon, or `localhost`) must be a public
  `https://` address and is typed in that form; other text (words with spaces, or one plain word)
  is a search, sent to Firefox's default search engine. A web page's own inputs take any text.
- **Waiting:** after an action the screen counts as settled when it changed (if a change was
  expected), two reads match, the node count hasn't changed for 1 s and a web page on screen shows
  text; at most 10 s after navigation or a tap, 3 s after small actions.
- **Changed screen:** when the element the model chose has changed by the time it acts, the
  action isn't run, the step isn't counted and the loop guard isn't touched; the model is asked
  again on the settled screen with a one-line note, at most twice in a row (a third time counts as
  a refused action).
- **Evidence:** `finish` has an `evidence` field. A finish passes when every quoted piece (split at
  "|", line breaks and ellipses) is on a screen of this run (ignoring case, spacing, quote styles,
  dashes and the `[12]` ids), every number in the answer is in the quotes, and every name of two or
  more capitalized words in the answer that isn't in the task is in the quotes (my addition to the
  number rule, to catch answers from memory like Phase 2.1 task 4). An answer with no number and
  no name needs no evidence ("not found"). The first failing finish is rejected with the reason
  and the run goes on; a second one ends the run as finished, marked "Evidence: NOT verified".
- **Final call:** when the loop guard (any of its stops) or the step limit ends a run that showed
  page text, one more model call that offers only `finish` gets the newest page screens (at most 3,
  about 2,400 tokens) and must answer from them with a quote or say the answer wasn't found. It is
  checked the same way, with one retry. Its answer counts as finished and the result says it came
  from the final call.
- **One line before the tool call:** LiteRT-LM 0.18 keeps text written before a tool call (its
  parser splits text and calls), so the model first writes one short line on whether the screen
  answers the task; the run log shows it as "model: ...". Thinking stays off by default.
- **Prompt:** searches go into the address bar (focused in a new private tab) with `submit`; known
  addresses through `open_url`; on a results page, no new search, tap the result ("Wikipedia",
  "Continued in Wikipedia"); never tap the search engine selector or Clear. Searching through an
  `open_url` search address is out until `open_url` is confirmed on the phone.
- **Compact observation:** text has no ids; a paragraph's text fragments and links (siblings in
  one page parent) are one line, links numbered inline (`[7]Bologna`), at most 1,200 characters;
  headings start with `# `. On a page, controls labelled only by their DOM id, clickable
  containers labelled by the start of their text, controls without any label (inputs excepted)
  and citation links (`[1]`, `[a]`, `[note 3]`) are not elements. Navigation and banner landmarks
  are skipped, except inputs, buttons starting with "Search" and elements with the tab role; a
  search landmark inside them is kept. So DuckDuckGo's All/Images/News links (links in a
  navigation landmark, not tabs) are dropped. Subtrees whose DOM id names a fundraising, cookie or
  consent notice (centralnotice, sitenotice, frb, fundrais, donat, cookie, consent, gdpr,
  onetrust) and controls labelled "Donate..." or with a bare amount ("€5") are skipped. Neighbouring
  lines with the same label become one (link over button over heading over text). The main
  landmark comes first, then the rest of the page, then Firefox's toolbar, which keeps up to half
  of the budget. The address bar's label no longer repeats the address shown in the header.
- **Lock:** the run stops at once when the screen turns off (`ACTION_SCREEN_OFF`, registered for
  the run only) and before any step or action when the keyguard shows, with "Stopped: the phone
  was locked."; every agent action refuses while locked; PhoneAgent isn't brought to the front.
  While a run is shown, the Stop chip's window keeps the screen on (`FLAG_KEEP_SCREEN_ON`, no
  permission), so the screen timeout doesn't end runs.
- **Tile:** the task runs on the tile model from Model memory (or, if it isn't downloaded, the
  first downloaded model of the task); a Phone Agent screen showing another model is replaced by
  that model's screen. Before Phone Agent loads any model, other loaded models (other tasks') are
  closed, for every Phone Agent load, not only the tile's. The run log's first line names the
  model the tile asked for.
- **CI:** pushes that change only `docs/` or Markdown files outside `Android/src` don't build.
- **Spike:** "Copy tree" copies the converted tree as JSON (no password values; clipboard marked
  sensitive), so real screens can become test fixtures.

## Upstream sync 1.0.23 (decisions and assumptions; change any by editing here)

The owner asked for the merge as its own pass: no Phase 3, no agent changes, no new features.
Recipe and file list: `docs/UPSTREAM_SYNC.md`.

- **Tag:** `1.0.23` (`9cb31cf`, 2026-11-04), the newest tag. Upstream `main` is 31 untagged commits
  further (to 2026-11-15; its source says 1.0.24). They add no library, permission, host or
  analytics event, but they are not a tag, so they are not taken.
- **Which branch is "main":** the owner's main build is `claude/phone-agent-ik31nz` (it publishes
  `phoneagent-ci`); the repository's `main` is upstream's base `8f5f2a0`. The sync branch
  `upstream-sync-1.0.23` starts from `claude/phone-agent-ik31nz` at `87e2f2e` (no commits after it).
  Neither branch is changed by this pass.
- **Merge:** a real merge commit (`76aea47`), 104 upstream commits, 15 textual conflicts.

### Hard conflicts

- **Model manager** (`ui/modelmanager/ModelManagerViewModel.kt`). *Upstream:* speech and
  "supplementary" models (text-to-speech and speech recognition settings, a speech model
  repository, voice preview), the ML Kit speech runtimes, a download planner, the mobile-data
  confirmation, a network state provider, a paused state for partial downloads, and a startup step
  (`processPendingDownloads`) that re-attaches to queued work and resumes partial downloads.
  *Ours:* the login service created only when a login starts; one engine at a time (Phone Agent's
  engine closed before another model loads, other models closed before Phone Agent loads); the
  model list from cache or the bundled snapshot; AICore models skipped; nothing re-queued at
  startup; Resume on tap. *Resolved:* upstream's file with our blocks. The allowlist loop also
  skips the `MLKIT_SPEECH` and `MLKIT_ASR` runtimes and every speech model; the startup step keeps
  upstream's watching and cancelling but its resume loop is removed; upstream's Resume button
  replaces ours; the speech parts compile against inert stand-ins. Our lifecycle rules win
  wherever they meet upstream's: `initializeModel` still starts with the one-engine hook,
  `onCleared` disposes the login service only if one was created.
- **The two download files** (`data/DownloadRepository.kt`, `worker/DownloadWorker.kt`).
  *Upstream:* a rework: resume over HTTP Range, reconnects when a connection stalls, partial
  downloads reported as paused, zip extraction moved to `DownloadZipExtractor.kt`, a storage check
  before unzipping, analytics events. *Ours (Phase 0.1):* the token stored and sent only for
  Hugging Face https addresses; work from an earlier app process never runs; a "wait for a network"
  constraint. *Resolved:* upstream's files with our rules on top: the token rule (also first in
  upstream's header helper), the session id in each request and a session tag on each work item
  (work from an earlier process is refused and cancelled at the next start), a scheduler rerun ends
  the download, left paused (`DownloadPolicy.isSchedulerRerun`), and files are written only inside
  the models folder. The network constraint is **not** re-applied: it made WorkManager restart a
  download by itself when the network came back. Upstream's own check refuses to start a download
  without a connection.
- **The dictation file upstream moved** (`ui/common/textandvoiceinput/HoldToDictateViewModel.kt`
  to `ui/common/asr/`). *Upstream:* moved it and switched it to its speech engines. *Ours:*
  dictation only with an on-device recognizer. *Resolved:* upstream's moved file, unchanged and out
  of the build with the rest of the voice features; our edit is dropped (there is no dictation),
  and the stand-in's `dictationAvailable = false` keeps our unchanged check in `TextAndVoiceInput.kt`
  working.
- **The AICore helper we removed** (`runtime/aicore/AICoreModelHelper.kt`). *Upstream:* changed it.
  *Ours:* deleted in Phase 0. *Resolved:* upstream's file is back in the tree, with
  `AICoreModelDownloader.kt`, and out of the build, so later upstream edits merge cleanly.
- **Analytics** (`Analytics.kt`). *Upstream:* a `GalleryAnalytics` wrapper (`logEvent`,
  `logGalleryEvent`, collection switch, test hooks) around Firebase Analytics, and new events.
  *Ours:* Firebase removed. *Resolved:* upstream's wrapper, its delegate typed as the no-op
  `NoOpAnalytics` from `:telemetry-stub` and always null, so every event goes nowhere. Firebase is
  not on the classpath, so a direct Firebase call fails the build; the guard keeps it off.

Other conflicts: the version catalog and app build file (ML Kit, `androidx.xr.projected` and
MediaPipe tasks-audio not declared; `androidx.pdf` declared), `gradle.properties` (ours), the
manifest (ours: no `GlassesTapActivity`, no package-visibility entries for text-to-speech or the
glasses service), `GalleryApplication` (no Firebase, notifications or text-to-speech callbacks;
upstream's isolated-process check kept), `SettingsDialog` (no speech engine switcher, no Firebase
switch), `GalleryNavGraph` (ours plus upstream's imports), `ModelDownloadGates` (our login guard
moved there), and the download status UI and `DownloadAndTryButton` (upstream's versions, whose
paused state with Resume and Discard replaces our "(paused)" label and button).

### Left out of the build

- **Voice** (text-to-speech, speech recognition, dictation: ML Kit GenAI speech, MediaPipe
  tasks-audio, the system's speech services) and the **XR language teacher** (glasses, ML Kit
  schema, `androidx.xr.projected`). The product has no voice, and each needs its own privacy
  review. **AICore** stays out as before.
- How: the exclusion list, a filtered copy of `src/main/java` and `src/main/assets` for the build
  (KSP sees every file in a source folder), and stand-ins (`*StandIns.kt`) with the excluded names.
  Entry points removed in marked blocks: the model manager's speech-models tab row
  (`GlobalModelManager`), the XR task's lines in the config dialog (`ModelPageAppBar`), the speech
  engine switcher in Settings. The XR task is never registered (its module is excluded), so it has
  no card, route, featured slot or model.
- What that removes from the app: read-aloud buttons, banner and auto-play in chats; the
  dictation button in the chat input; hold-to-dictate in Tiny Garden and Mobile Actions (text
  only); the config sheet's Audio tab and its promo; the speech models tab and the 7 speech models
  of the 1.0.23 list; the speech engine setting; the XR language teacher and its assets.
  **Correction (audit, 2026-11-18):** an earlier version said "the microphone in the chat input".
  The chat microphone stays: **Record audio clip** (AI Chat and Agent Chat **+** menu) and the
  **Audio Scribe** tile record the microphone for the on-device model, with `RECORD_AUDIO`; Mobile
  Actions and Tiny Garden still ask for that permission on open. See "Open decisions".
- Also out: `data/SpeechEngineError.kt` and `ui/common/SpeechEngineErrorDialog.kt` (only excluded
  code used them; the dialog held a Play Store link).
- Assumption: unused strings of the excluded features and one vector icon stay in the resources
  (text only, no code or links; excluding them would mean editing upstream's `strings.xml`).

### Downloads, model list, telemetry

- **No automatic download:** a download starts only from a tap. Nothing resumes at startup, after a
  restart of the app, when the network returns, or when the system reruns the work; a stopped
  download shows as paused until the owner taps Resume (or Discard). Assumption: within one tapped
  download, upstream's reconnect after a stalled connection stays (same file, same address, while
  that download runs).
- **Archives** (`phoneagent/downloads/ZipPolicy.kt`, marked blocks in `DownloadZipExtractor.kt`
  and `DownloadWorker.kt`): upstream already refused entries outside the unzip folder. The unzip
  folder itself (a model-list field) must be inside the model's folder; an archive is checked
  before anything is written (at most 20,000 entries and 16 GiB unpacked, no entry above 1 MiB
  compressed more than 100 to 1, no entry that replaces a file of the model or names the same file
  as another, ignoring case); while unpacking, an entry its directory doesn't list, or more bytes
  than declared for an entry or in all, stop it; a refused archive is deleted with the files and
  folders unpacked from it (audit fixes, 2026-11-18).
- **Model list:** the bundled snapshot is upstream's `1_0_23` list (required by the versionName). It
  keeps the 11 models of `1_0_20` byte for byte and adds 7 speech models, all skipped.
- **Telemetry:** upstream's new events (model import, downloads, buttons) go to the no-op wrapper.
- **MediaPipe stays pinned** (audit of the sync, 2026-11-18): `tasks-vision` 1.0.0 and
  `tasks-retrieval` 1.1.0 (`tasks-core` 1.1.0), the versions the main build shipped, not upstream's
  1.2.0. MediaPipe 1.2.0's `libmediapipe_tasks_jni.so` contains a native Clearcut usage-stats
  uploader (`https://play.googleapis.com/log`, sent through `java.net.HttpURLConnection` over JNI)
  that the Java telemetry stub cannot reach. The merged code builds and passes its tests against
  the older versions. A newer MediaPipe is taken only after its native libraries, in every ABI,
  pass the APK check's native rules (`docs/UPSTREAM_SYNC.md`).

### Versions, signing, CI

- **versionName** `1.0.23-phoneagent.1` (`-ci` added by the ci build type).
- **versionCode stays 46**, the main build's. Upstream's 52 is not taken. Builds of both branches
  then install over each other in both directions (Android refuses only a lower versionCode). A
  rising versionCode would make the main APK a downgrade of the sync APK until the sync is
  merged. To make versionCode rise across branches from then on, CI could derive it from the run
  number once the main branch carries this workflow.
- **LiteRT-LM stays 0.18.0.** Upstream moved to 0.18.1 for `maxOutputToken` on `sendMessage` and
  `sendMessageAsync`; 0.18.0 already has it, and the merged code compiles and passes against it.
- **Signing:** until the signing secrets existed, each branch signed with its own cached debug key,
  so the sync APK and the main APK didn't install over each other. Resolved on 2026-11-17: one key
  signs both pre-releases (see "Signing").
- **CI:** sync branches build and publish to `phoneagent-ci-sync`; only the main build branch
  publishes to `phoneagent-ci`. The build-logic tests run before the guard. Upstream's workflows are
  unchanged in 1.0.23 and stay manual-only.

### Kept from upstream as it is

- PDF attachments in chats (`androidx.pdf`): the document is rendered on the phone by a service in
  an isolated process (no network permission there), not exported.
- Session persistence and context compaction (`LlmSessionManager`): they use the conversation of the
  loaded model; no new engine is created anywhere (every engine and model-load call site is the same
  as before the merge).
- The paused-download UI, the mobile-data confirmation, the no-connection message, the network
  monitor (connectivity callbacks during a download, no traffic of its own), upstream's home layout.

## Signing (set up 2026-11-17)

- **One key for every CI build.** The owner's keystore signs both pre-releases, `phoneagent-ci`
  (main build) and `phoneagent-ci-sync` (other branches). With the same applicationId
  (`io.github.monkeygamer2010.phoneagent.dev`) and versionCode (46), a build of either branch
  installs over the other in both directions.
- **Certificate SHA-256** (public): `9A:A9:A0:CF:36:8E:49:CA:81:24:10:DE:5C:EC:F1:31:2D:9E:78:C2:12:E8:45:DE:D9:4C:7D:EB:5A:5E:D5:57`.
  The workflow pins it (`RELEASE_CERT_SHA256`); change it only together with the key.
- **Where the key lives:** only in the owner's password manager and in four repository secrets,
  `KEYSTORE_B64` (the keystore file, base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
  Never in the repository, a CI artifact, a log or a release.
- **How CI uses it** (the sync branch's workflow since `a202ac0`; the main branch gets it with the
  merge): one step decodes the keystore into a private temp folder, re-signs Gradle's APK with
  `apksigner` (scheme v2, as Gradle signs) and deletes the keystore on every exit; a cleanup step at
  the end of the job deletes it again even when the job failed. No Gradle step has a secret in its
  environment (they build with a throwaway debug key). **Correction (audit, 2026-11-18):** an
  earlier version said Gradle and its plugins "never see a secret". That holds for the
  environment only: the signing step runs on the same runner, as the same user, right after the
  build, and the runner removes leftover processes only at the end of the job, so a compromised
  build dependency could still reach the keystore or the passwords. A separate signing job that
  runs no repository code would close this; not done yet (no workflow changes in the fix pass).
  The certificate's SHA-256
  is printed in the log and in the pre-release notes. An APK whose certificate isn't the pinned one
  fails the build and is never published; without the secrets a run compiles and tests but uploads
  and publishes nothing. When a secret looks wrong, the error names which one without printing it.
  Until the merge, the main branch's older workflow passes the secrets to the Gradle build, which
  signs with the same key.
- **Losing the key:** Android installs an update only when it is signed with the same key. A lost
  key means a new one, a new pinned certificate, and every installed copy uninstalled once, which
  deletes downloaded models, settings, the accessibility enablement and the tile.
- **Secrets are masked everywhere.** GitHub replaces every secret's value with `***` in the logs
  and drops a job output that contains one. The key alias is a word in the package, branch and tag
  names, so it shows as `***` in many log lines, and the workflow passes only neutral values
  ("main" or "sync", "release" or "throwaway", the certificate) from the build job to the publish
  job. Keeping `KEY_ALIAS` as a plain repository variable instead of a secret would avoid the
  masking; it is the owner's choice.
- Local builds (`./gradlew assembleCi`) still sign with the keystore named by
  `PHONEAGENT_KEYSTORE_FILE` plus the three passwords, or else the debug key; they are not published.

## Audit of the upstream sync (2026-11-18): fixes and decisions

The audit of `upstream-sync-1.0.23` at `4c38e44` and its fix pass. Findings and resolutions:
`docs/LEARNINGS.md`, "Audit of the 1.0.23 sync and fix pass".

- **MediaPipe pinned** to the main build's versions (see "Downloads, model list, telemetry").
- **Native-library rules** in the APK check: every URL, host name, Java networking class and
  telemetry word in a native library's strings must be on `ApkRules.NATIVE_ALLOWLIST` for that
  library, each entry with its reason. The list started empty; what the pinned build needs was
  added: the NDK compiler's version string, documentation and bug links in LiteRT-LM's and
  MediaPipe's error messages, JSON Schema identifiers, protobuf type-URL prefixes, a build-machine
  name in a build path, a Go package name in a protobuf descriptor, `android/net/Uri`, and TFLite's
  hooks into Play services' TFLite logger in `libsqlite_vector_store_jni.so` (tasks-retrieval
  1.1.0, also on the main build). Assumption: those hooks are inert here, because they call a Java
  object that only Play services' TFLite library can supply, and that library is forbidden and
  absent. If the owner doesn't want even dormant hooks, Smart Album and Video Moment Finder (the
  users of tasks-retrieval) would have to leave the build.
- **Archives:** two entries for the same file, or for names that differ only in case, refuse the
  archive (the models folder is on case-folding shared storage, and a later entry would silently
  replace an earlier one). The ratio cap is 100 to 1 for entries above 1 MiB: Smart Album's
  `samples.zip` (photos), the only archive in the model list, is far below it.
- **Licence page:** closing it ends the pending download and drops the licence refusal from the
  model's status, so the next tap on Download tries again with the stored token; upstream
  restarted the download by itself.
- **AppAuth's redirect activity** is removed from the manifest until Hugging Face login is
  configured; restore it together with a real client id and redirect.
- **Source sets:** only the filtered `src/main`, `src/phoneagentStandins` and test source sets are
  allowed. The debug-only exported components (`androidx.compose.ui.tooling.PreviewActivity`,
  `androidx.activity.ComponentActivity`) stay explicit in `ApkRules.APPROVED_EXPORTED_DEBUG`.
- **JVM unit tests** let framework stubs return defaults (`unitTests.isReturnDefaultValues`):
  upstream's extractor logs a malformed archive and goes on, as it does on a phone.
- **Final size check before rename (audit N9): not done**, recorded as a known gap
  (`docs/UPSTREAM_SYNC.md`). The worker gets only the download's total, not each file's size, and
  the model list's sizes are not confirmed exact (several are multiples of 4 KiB); a wrong size
  would make a good download unusable.

## Open decisions (the owner's)

- **The chat microphone.** Record audio clip (AI Chat, Agent Chat), the Audio Scribe tile and the
  `RECORD_AUDIO` permission are in the build: on-device only, but they record speech for the model
  to understand, which a strict "no voice" reading excludes. Mobile Actions and Tiny Garden ask for
  the permission on open and show nothing without it, although nothing in them records any more.
  Options: keep and document as an on-device exception; or remove (marked blocks: no audio picker
  in the chat input, Audio Scribe not registered, no microphone gate in Mobile Actions and Tiny
  Garden, `RECORD_AUDIO` removed from the manifest and from the APK check's approved set). Not
  changed in the fix pass.
- **The signing job.** Move signing to a separate job that never runs repository code (and the
  secrets to a protected environment), and bring the new workflow to `claude/phone-agent-ik31nz`
  before any further push there. Not changed in the fix pass (no workflow changes).

