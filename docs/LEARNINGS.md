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
   If any tile crashes, send the crash text (Settings > Developer options > "Bug report", or
   `adb logcat -b crash`) and we hide that tile in a follow-up.
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
