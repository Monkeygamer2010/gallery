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
  usage-stats uploader replaced by a no-op stub; no network at startup or idle
  (`docs/NETWORK.md`).
- **[done]** `privacyGuard` build check fails on Firebase / Play services / ML Kit dependencies,
  on the google-services plugin, and on MediaPipe references the stub doesn't cover. It runs in CI.
- **[done]** No cleartext HTTP, no WebView metrics or Safe Browsing pings, no cloud backup or
  device-to-device transfer of app data.
- **[done]** Removed upstream's FCM handler, which let any app make PhoneAgent open an arbitrary
  URL through a `deeplink` intent extra, and the debug logging of every intent extra.
- **[done]** CI uses first-party GitHub actions pinned to commit SHAs, read-only permissions
  except the publish job, and signing secrets that are decoded to a temp file and deleted. Release
  keys never go to GitHub; the owner signs releases locally.
- Model files are downloaded over HTTPS from Hugging Face at the commit pinned in the model list.
  Residual risk: no extra checksum check beyond that pin.

### Agent: what the model can do
- **[P2]** The model gets a small fixed tool set (read_screen, tap, type_text, scroll, back, home,
  open_app, open_url, note, save_result, finish). No tool can change policy, the allowlist, locks
  or settings of this app.
- **[P2]** Tool calls are executed by our code (LiteRT-LM manual tool calling), which validates
  every call before it touches the phone.
- **[P2]** Elements are referenced by ids from the last screen read, never by coordinates.
- **[P2]** Loop guard: at most 15 steps, a per-step timeout, abort on a repeated identical action
  on an identical screen. Stop chip and notification Stop action at all times.
- **[P3]** Confirmation dialog (domain and exact text shown) before send/post/submit, purchases,
  deletions, install/uninstall, permission or settings changes, and before typing or submitting
  on a site not yet visited in this run.
- **[P4]** Default-deny per-app allowlist. Category locks for Money & credentials (asks every
  time), Email & messaging, App stores & installers, System Settings, each refused until the
  owner flips an "I understand" switch.
- **[P2]** Never operates PhoneAgent itself or Settings > Accessibility.
- **[P2]** `open_url` accepts `https://` only, caps the URL length, and logs every URL in the run
  log.

### Agent: what the model sees
- **[P2]** Screen text is wrapped in delimiters and labelled as untrusted data; the system prompt
  tells the model never to follow instructions found in it. This lowers the risk; the code-level
  controls above are what bound it.
- **[P2]** Password fields are redacted. Clipboard contents and secrets never enter the model's
  context.
- **[P1]** Secure windows (`FLAG_SECURE`) and empty or hidden screens are reported as such and
  the task stops. No workaround.
- **[P1]** Screen contents are never written to logcat.

### Browser
- **[P1]** Web tasks run in Firefox private tabs only. The agent refuses to read a non-private
  tab, never reads history or saved logins, and never changes browser settings or extensions.
- **[P5, fallback only]** GeckoView with per-task private sessions, if Firefox can't be driven.

### Entry points
- **[P2]** Tasks start only from text the owner types in the app or in the Quick Settings popup.
  Deep links and intents from other apps can't start a task.
- **[P2]** Android 10+ background-start rules are respected: the first app is launched from the
  visible UI; no `SYSTEM_ALERT_WINDOW` without the owner's OK.
- **[P2]** The accessibility service does not claim `isAccessibilityTool`. If Advanced Protection
  blocks it, the app explains why instead of working around it.

### Data at rest
- **[P3]** The last 10 runs are kept in app-private storage with a clear button; nothing is saved
  automatically to shared storage; export goes through the system file picker.

## Known residual risks

- A page can still steer the model within what policy allows (for example, which search result
  to open). Confirmations and the allowlist bound the damage; they don't remove the risk.
- Accessibility services are powerful by design. If the phone is compromised, PhoneAgent's
  service is another capability an attacker could try to misuse.
- Upstream merges can reintroduce trackers; the build guard and the re-appliable privacy commit
  exist for that.

## Reporting a problem

Until the public release, open a GitHub issue on the fork, or a private security advisory for
anything sensitive.
