# PhoneAgent security model

PhoneAgent lets an on-device language model operate the phone through an Android
AccessibilityService. That service can read almost everything on screen and act on almost every
app, so the design assumes the model can be wrong or manipulated, and keeps every safety decision
in ordinary code. This document is the threat model; it is updated as each phase lands.

Status legend: **[done]** shipped, **[P1]**-**[P6]** planned for that phase.

## What we protect

- What other apps show on screen: messages, mail, banking, documents.
- Credentials and payment details, the clipboard, saved logins and browser history.
- Actions with side effects: sending, posting, buying, deleting, installing, changing settings.
- The owner's privacy: what the app sends over the network (see `docs/NETWORK.md`).
- Local data: run history and results, the Hugging Face token, downloaded models.

## Who and what we defend against

1. **Untrusted page and app content (prompt injection).** The main threat. Any text the agent reads
   on screen may contain instructions aimed at the model.
2. **Model mistakes.** The model can misread a screen, hallucinate an element or loop.
3. **Other apps on the phone** sending intents to PhoneAgent or trying to start tasks.
4. **Network observers and third-party SDKs** collecting data.
5. **Supply chain:** libraries, model files, CI and upstream merges.

Out of scope: a rooted or compromised OS, someone with the unlocked phone in hand, and the
privacy of sites the browser visits during a task (they see a normal private-browsing visit).

## Controls

### Privacy and supply chain
- **[done]** Firebase, ML Kit/AICore, Play services TFLite and oss-licenses removed; MediaPipe's
  Java usage-stats uploader replaced by a no-op stub, and MediaPipe kept at 1.0.0/1.1.0 because
  1.2.0's native library carries its own Clearcut uploader (`https://play.googleapis.com/log`)
  that no stub can reach; no network at startup or idle (`docs/NETWORK.md`).
- **[done]** `privacyGuard` build check fails on Firebase / Play services / Play Core / ML Kit /
  AICore / common analytics dependencies, local file dependencies, the google-services plugin,
  and MediaPipe references the stub doesn't cover. `check<Variant>Apk` (with every assemble,
  install and bundle task) inspects every built APK: dex classes and references (no speech
  recognition or text-to-speech classes), native imports and native strings (no unapproved URL,
  host, Java networking class or telemetry word, every ABI), leftover debug logging, the source
  sets, and the merged and the packaged manifest (exactly the approved permissions from every
  permission element, exactly the approved intent filters compared whole, approved
  package-visibility entries only). The rules are constants in build-logic with their own tests;
  CI requires both pass reports. They inspect files, not run-time behaviour.
- **[done]** Upstream merges: features the fork doesn't ship stay in the source tree but out of the
  build, on one exclusion list; since 1.0.23 these are text-to-speech, speech recognition,
  dictation, the XR language teacher and AICore. The APK check fails if a class compiled from one of
  their files (by package and source-file name), any non-stand-in class in their packages, one of
  their assets, or any reference to the platform's speech classes is in the APK. It recognizes
  files by name, not content. Recipe: `docs/UPSTREAM_SYNC.md`.
- **[done]** Skill WebViews: no network unless the owner turns on a skill's "Allow network"
  switch, no access to app-private files, no WebRTC, camera and microphone denied, model-chosen
  script and page names confined to the skill's folder.
- **[done]** MCP servers: only enabled ones, only while Agent Chat is visible.
- **[done]** The Hugging Face token is sent only to Hugging Face hosts; secret fields are masked
  and never shown or logged.
- **[done]** No remote images from rendered text; no Geocoder; no speech recognizer or
  text-to-speech engine (not in the build since the 1.0.23 sync).
- **Open, the owner's decision:** the chat microphone is still in the build: **Record audio clip**
  in AI Chat and Agent Chat and the **Audio Scribe** tile record the microphone (`RECORD_AUDIO`)
  for the on-device model, which can transcribe speech. On the phone only, nothing is sent. Mobile
  Actions and Tiny Garden still ask for the microphone permission when they open, unused.
- **[done]** No cleartext HTTP, no WebView metrics or Safe Browsing pings, no cloud backup or
  device-to-device transfer of app data.
- **[done]** Removed upstream's FCM handler, which let any app make PhoneAgent open an arbitrary
  URL through a `deeplink` intent extra, and the debug logging of every intent extra.
- **[done]** The app opts out of content capture, autofill and Recents screenshots, and asks
  keyboards not to learn typed text. API 37 deprecates the app-wide content-capture switch, so
  screens that show other apps' text (Phone Agent runs and results, spike tools, the task popup)
  are also marked secure (`FLAG_SECURE`: no screenshots, recordings or system capture) unless
  the owner turns on "Allow screenshots".
- **[done]** CI uses first-party GitHub actions pinned to commit SHAs and read-only permissions
  except the publish job. The key that signs the CI builds is held only in the owner's password
  manager and in four repository secrets. One CI step sees them: it decodes the keystore into a
  private temp folder, signs the APK and deletes the keystore on every exit; no secret is printed,
  uploaded or committed. No Gradle step has a secret in its environment, but the signing step runs
  on the same runner right after the build (see the residual risks). CI publishes only an APK whose
  certificate matches the pinned SHA-256 (`docs/DECISIONS.md`, "Signing"), and nothing without the
  secrets. This is the sync branch's workflow: until the merge, the main build branch's older
  workflow passes the passwords to the whole Gradle build and leaves the keystore on disk for all
  its build steps.
- **[done]** Downloads start only from a tap and are never restarted or resumed by anything
  automatic, also not when a gated model's licence page closes. Files of a model are written only
  inside the models folder; an archive is unpacked only inside its model's folder, every entry
  inside the folder it is unpacked to; no entry may replace a file of the model or name the same
  file as another entry (ignoring case); at most 20,000 entries and 16 GiB, no entry over 1 MiB
  compressed more than 100 to 1, and never more than the archive declares, per entry and in all. A
  refused archive is deleted with the files and folders unpacked from it.
- Model files are downloaded over HTTPS from Hugging Face at the commit pinned in the model list.
  Residual risk: no extra checksum check beyond that pin.

### Agent: what the model can do
- **[done, interim]** In the existing tiles, calendar, contacts, messaging and network-capable
  tool calls ask for confirmation every time, and an Agent Chat intent runs only while an enabled
  skill offers it. Phase 4's policy engine replaces this.
- **[done]** The model gets a small fixed tool set (tap, type_text, scroll, back, home,
  open_app, open_url, note, save_result, finish). No tool can change policy, the allowlist,
  locks or settings of this app.
- **[done]** Tool calls are executed by our code (LiteRT-LM manual tool calling), which validates
  every call before it touches the phone.
- **[done]** Elements are referenced by ids from the last screen read, never by coordinates.
  Right before acting, the screen is read again and the action runs only if the id still names
  the same element.
- **[done]** Loop guard: at most 15 steps, a per-step timeout, abort on a repeated identical
  action on an identical screen, on repeated unusable replies and on repeated refusals. Stop chip
  and notification Stop action at all times.
- **[done, interim]** Until Phase 4's allowlist, tasks read and act only in Firefox private tabs.
  Other apps' screens are not even converted; `open_app` opens only Firefox.
- **[P3]** Confirmation dialog (domain and exact text shown) before send/post/submit, purchases,
  deletions, install/uninstall, permission or settings changes, and before typing or submitting
  on a site not yet visited in this run.
- **[P4]** Default-deny per-app allowlist. Category locks for Money & credentials (asks every
  time), Email & messaging, App stores & installers, System Settings, each refused until the
  owner flips an "I understand" switch.
- **[done]** The service acts only when the owner starts a test or a task, never on PhoneAgent
  itself, in Settings or in permission and install dialogs, and does not claim
  `isAccessibilityTool`.
- **[done]** `open_url` accepts public `https://` addresses only (no IP addresses or local host
  names), caps the URL length, and lists every URL in the run log. An address typed into
  Firefox's address bar is held to the same rule in code (and typed as `https://`); search words
  go to Firefox's search engine.
- **[done]** A task stops as soon as the screen turns off or the keyguard shows, and no action runs
  while the phone is locked.

### Agent: what the model sees
- **[done]** Screen text is wrapped in delimiters that page text cannot close, and labelled as
  untrusted data; the system prompt tells the model never to follow instructions found in it.
  Action results the model sees are PhoneAgent's own words, not screen text. This lowers the
  risk; the code-level controls above are what bound it.
- **[done]** Password field values are never shown, and typing into password fields is refused.
  Clipboard contents and secrets never enter the model's context.
- **[done]** Empty or hidden screens are reported as such and a task stops there. No workaround.
- **[done]** Release and ci builds contain no `Log.v/d/i` calls (app and libraries), and
  LiteRT-LM's native logging is limited to errors.
- **[done]** The accessibility service and spike screen never log, save or upload screen
  contents; spike results stay in memory and are copied only on request (clipboard marked
  sensitive). Password values are never shown, even in the developer view.

### Browser
- **[done, spike]** Firefox pages are opened with `https://` addresses only; private-tab
  detection uses the browser's own UI, never page content. The owner's Phase 1 results decide how
  reliable it is.
- **[done]** Web tasks run in Firefox private tabs only. The agent refuses to read a non-private
  tab, and refuses Firefox's own settings, menus and add-on pages, so it never reads history or
  saved logins and never changes browser settings or extensions.
- **[P5, fallback only]** GeckoView with per-task private sessions, if Firefox can't be driven.

### Entry points
- **[done]** Upstream's exported `com.google.ai.edge.gallery://` deep link is gone; app links are
  accepted only through a non-exported alias. AppAuth's exported OAuth redirect activity is removed
  while Hugging Face login is not configured.
- **[done]** Tasks start only from text the owner types in the app or in the Quick Settings popup
  (handed over in memory, never in an intent). Deep links and intents from other apps can't start
  a task: the popup and the run service are not exported, and only the system can bind the tile.
- **[done]** Android 10+ background-start rules are respected: the first app is launched from the
  visible UI; no `SYSTEM_ALERT_WINDOW` (the Stop chip is an accessibility overlay).
- **[done]** The run notification shows only a step counter, never the task or screen text.
- **[done]** The accessibility service does not claim `isAccessibilityTool`.
- **[P6]** If Advanced Protection blocks the service, the app explains why instead of working
  around it (onboarding polish).

### Data at rest
- **[done]** Phase 2 keeps a run (log, addresses, notes, results, answer, measurements) in memory
  only; Copy puts it on the clipboard marked as sensitive.
- **[P3]** The last 10 runs are kept in app-private storage with a clear button; nothing is saved
  automatically to shared storage; export goes through the system file picker.

## Known residual risks

- A page can still steer the model within what policy allows (for example, which search result
  to open). Confirmations and the allowlist bound the damage; they don't remove the risk.
- Private-mode detection reads Firefox's own UI. If a Firefox update changes it, the agent
  refuses to read (it fails closed) until the detection is updated.
- Accessibility services are powerful by design. If the phone is compromised, PhoneAgent's
  service is another capability an attacker could try to misuse.
- Upstream merges can reintroduce trackers; the build guard, the APK check, the exclusion list and
  the re-appliable privacy commit exist for that.
- A skill whose "Allow network" switch is on runs third-party code with network access; each
  call asks first.
- The CI signing key is as safe as push access to this repository: a workflow pushed to any of its
  branches can read the repository secrets. It is also as safe as the build's dependencies: the
  signing step runs on the same runner as the Gradle build, so a compromised plugin or library
  could leave a process behind that reads it. A separate signing job that runs no repository code
  would close that; the main build branch's older workflow is weaker still (it gives the passwords
  to the whole Gradle build).
- The native-library rules read strings, not code: a library could build a host name or URL from
  pieces, or reach the network through code paths with no tell-tale string. MediaPipe and
  LiteRT-LM stay pinned for this reason, and the phone test with PCAPdroid remains the run-time
  check.
- A model file is not checked against the size in the model list before it is renamed (only each
  response's length against its Content-Length): a server that serves a short file whole would
  leave a short model (`docs/UPSTREAM_SYNC.md`, known gaps).

## Reporting a problem

Until the public release, open a GitHub issue on the fork, or a private security advisory for
anything sensitive.
