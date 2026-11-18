# Upstream sync recipe

How to merge a new google-ai-edge/gallery release into PhoneAgent as its own pass, so that any
change in behaviour afterwards can be traced to the merge. Decisions behind each step:
`docs/DECISIONS.md`, "Upstream sync 1.0.23". Phase log: `docs/LEARNINGS.md`.

## Last sync

| | |
|---|---|
| Upstream tag | `1.0.23` (commit `9cb31cf`, 2026-11-04) |
| Old upstream base | `8f5f2a0` (2026-10-13), the base of `claude/phone-agent-ik31nz` |
| Upstream commits merged | 104 |
| Fork base | `claude/phone-agent-ik31nz` at `87e2f2e` |
| Branch | `upstream-sync-1.0.23`; merge commit `76aea47` (a real merge, two parents) |
| versionName / versionCode | `1.0.23-phoneagent.1` / `46` |
| LiteRT-LM | `0.18.0` (unchanged; upstream uses 0.18.1, which the merged code does not need) |
| MediaPipe | `tasks-vision` 1.0.0, `tasks-retrieval` 1.1.0 (pinned; upstream 1.2.0 ships a native Clearcut uploader) |
| Not taken | upstream `main` past the tag (31 commits on 2026-11-17, untagged, heading to 1.0.24) |

## Rules for a sync pass

- Merge a **tag**, not upstream `main`. A newer tag is taken only if its extra commits add no
  library, host, permission or telemetry event; otherwise take the older tag and report what is
  newer.
- New branch `upstream-sync-<tag>` from the main build branch. A real merge commit: no rebase, no
  squash, no force-push, no tags. Push after every commit. The main build branch is not touched
  until the owner says so.
- No new features, no agent changes. The privacy guard is never loosened.
- Upstream features the fork doesn't ship stay **in the tree, out of the build** (below), so the
  next merge doesn't turn into delete-versus-edit conflicts. Delete only where that isn't workable.
- Every new host or URL, even a tappable link, goes in `docs/NETWORK.md`.
- **Pinned libraries stay pinned** until the newer version is checked: MediaPipe (`tasksVision`,
  `tasksRetrieval` in `gradle/libs.versions.toml`) moves only after the newer version's native
  libraries, in every ABI, pass the APK check's native rules with no new allowlist entry, or with
  entries the owner approves. A library's Java side being clean says nothing about its native side.

## Signing

- The four signing secrets exist (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`, set 2026-11-17). The same key signs both pre-releases, so a sync branch's APK
  installs over the main build and back (same applicationId, versionCode 46, same key).
- Expected certificate SHA-256: `9A:A9:A0:CF:36:8E:49:CA:81:24:10:DE:5C:EC:F1:31:2D:9E:78:C2:12:E8:45:DE:D9:4C:7D:EB:5A:5E:D5:57`. CI prints it
  in the log and in the release notes and refuses to publish an APK signed with anything else.
  Check a downloaded APK with `apksigner verify --print-certs PhoneAgent-ci.apk`.
- The keystore lives only in the owner's password manager and in the GitHub secrets. Losing it
  means every installed copy must be uninstalled once (Android refuses an update signed with another
  key). Details: `docs/DECISIONS.md`, "Signing".

## Commands

From the repository root (the upstream remote is fetch-only):

```
git remote add upstream https://github.com/google-ai-edge/gallery.git   # once
git remote set-url --push upstream DISABLED                              # once
git fetch --tags upstream                       # shallow clone: add --shallow-since=<date>
git tag --sort=-creatordate | head              # newest tags
git log --oneline <tag>..upstream/main          # what is newer than the tag
git diff <tag> upstream/main -- Android/src/gradle/libs.versions.toml Android/src/app/build.gradle.kts \
  Android/src/app/src/main/AndroidManifest.xml  # new libraries or permissions past the tag?
git diff --stat <old-base> <tag> -- .github/    # upstream workflow changes (must stay off)

git switch -c upstream-sync-<tag> origin/claude/phone-agent-ik31nz
git merge-tree --write-tree HEAD <tag>          # preview the conflicts
git merge --no-ff --no-commit <tag>             # resolve, then commit the merge and push
```

Then, from `Android/src` (one Gradle build at a time; `--max-workers=2`):

```
./gradlew :app:compileDebugKotlin               # iterate: new references into excluded code
./gradlew -p build-logic test                   # the privacy rules' own tests
./gradlew :app:testDebugUnitTest :telemetry-stub:testDebugUnitTest
./gradlew :app:updateThirdPartyLicenses         # after dependency changes; commit the result
./gradlew :app:privacyGuard printCiSigning :app:assembleCi   # assembleCi runs checkCiApk
cp ../../model_allowlists/<version>.json app/src/main/assets/phoneagent/model_allowlist_snapshot.json
```

The snapshot copy is needed whenever `versionName` names a new upstream version
(`ModelListSnapshotTest` fails until it is done). Check the new list first: models of the old list
must be unchanged byte for byte, or downloaded models stop showing as downloaded.

## Upstream code that is in the tree but not in the build

`Android/src/app/phoneagent-excluded-sources.txt` is the one exclusion list. Each line is a path
under `src/main`, starting with `java/` or `assets/`, naming a file or a folder followed by `/**`.
The app is compiled from a filtered copy of `src/main/java` and `src/main/assets`
(`phoneagentFilterSources` in `app/build.gradle.kts`), because KSP (Hilt, Moshi) has no way to
exclude a source file from a source folder.

Out of the build since 1.0.23:

| Feature | Excluded | Why |
|---|---|---|
| XR language teacher (glasses) | `customtasks/xrlanguageteacher/**`, `assets/xr/**`, `assets/xr_earcons/**` | needs ML Kit GenAI schema, `androidx.xr.projected` and the glasses service |
| Text-to-speech, speech recognition | `services/tts/**`, `services/asr/**`, `data/supplementary/**`, `data/SpeechEngineError.kt`, `di/SpeechServicesModule.kt` | ML Kit GenAI speech, MediaPipe tasks-audio, the system speech services; the product has no voice |
| Read aloud, dictation, voice settings, speech model cards | `ui/common/tts/**`, `ui/common/asr/**`, `ui/common/SpeechEngineErrorDialog.kt`, seven files in `ui/modelmanager/` | the screens of the above |
| Multi-model selection screen | `ui/modelmanager/TaskModelSelectionViewModel.kt`, `ui/modelmanager/SectionedModelSelectionScreen.kt` | only the XR teacher uses it |
| AICore runtime | `runtime/aicore/**` | ML Kit GenAI prompt API and Play services; removed in Phase 0 |

**Stand-ins.** The rest of upstream's code still refers to some excluded types and functions.
`Android/src/app/src/phoneagentStandins/java` holds inert declarations with the same
fully-qualified names (`*StandIns.kt`, one file per package), so upstream's files compile unchanged:

- `rememberChatTtsController()` and `rememberChatAsrDictationViewModel()` return null (upstream's
  own "not available" path: no read-aloud, no dictation button; the chat's **Record audio clip**
  and the Audio Scribe tile are not voice features of this list and stay, see `docs/DECISIONS.md`,
  "Open decisions");
- `HoldToDictateViewModel.dictationAvailable` is false (Tiny Garden and Mobile Actions show text
  input only);
- the Audio tab state is empty (no Audio tab), no task is eligible for the audio promo, no speech
  model is registered, the selection screen is never needed.

Compiling a stand-in next to the real file fails with a redeclaration, so a passing build shows the
excluded file isn't in it. `checkCiApk` then checks the APK: no class compiled from an excluded
file (the dex keeps each class's source file name; D8's synthetic classes count as their outer
class), no class at all in an excluded package without stand-ins, only stand-in classes (and what
Hilt and D8 generate from them) in an excluded package with stand-ins, no excluded asset, no
reference to the platform's speech classes, and no source set besides the filtered `src/main`,
`src/phoneagentStandins` and the test sets. It recognizes files by name and package, not by
content: a copy of excluded code under another name in another package is caught only through the
classes it uses.

**After a merge:**

1. If compilation fails with unresolved references into an excluded package, upstream added a
   use. Add the missing declaration to the stand-in file of that package, inert, with upstream's
   exact signature; or, for an entry point (a route, a menu item, a setting), remove the call in a
   small marked block in upstream's file.
2. If upstream added files to an excluded feature, they are excluded automatically when their
   folder is listed. A new file elsewhere that only excluded code uses goes on the list (the
   1.0.23 pass found `SpeechEngineErrorDialog.kt`, which held a Play Store link, this way: list
   the new upstream files that are still compiled and look for voice, XR, ML Kit or AICore).
3. A pattern that matches no file fails the APK check: upstream renamed or deleted it. Fix the
   line.
4. Search the compiled sources for new URL literals and network calls against the previous build
   and record them in `docs/NETWORK.md`.
5. **Scan the native libraries**, every ABI, of the new APK against the previous one. `checkCiApk`
   fails on any URL, host name, Java networking class (`java/net`, `javax/net`, `android/net`) or
   telemetry word (`clearcut`, `playlog`, `firebase`, `telemetry`, ...) in a native library's strings
   that `ApkRules.NATIVE_ALLOWLIST` doesn't approve for that library. A finding is never
   allowlisted to make a build pass: find out what it is (`strings -n 6 lib/arm64-v8a/<lib>.so |
   grep -F '<finding>'` around it), and add an entry with its reason only if it cannot reach the
   network; otherwise pin the library back or take its feature out, as for MediaPipe 1.2.0. Kotlin
   and dex being clean says nothing about native code.

## Files where the fork edits upstream code

Every edit in an upstream file is a small block marked `PhoneAgent fork:`. Fork-owned code lives in
`com.google.ai.edge.gallery.phoneagent`, `build-logic`, `telemetry-stub` and `phoneagentStandins`.
After the 1.0.23 merge these upstream files carry fork edits (lines added / removed against the
tag, `git diff --numstat 1.0.23 HEAD`):

- Build: `app/build.gradle.kts` (+101/-21: ci build type, signing, privacy guard, exclusion
  wiring, removed dependencies), `gradle/libs.versions.toml` (+12/-29), `settings.gradle.kts`,
  `build.gradle.kts`, `gradle.properties`, `.gitignore`.
- Manifest and resources: `AndroidManifest.xml` (+104/-82), `res/values/strings.xml`,
  `themes.xml` (day and night), `res/xml/data_extraction_rules.xml`; fork-only resources
  `phoneagent_*`.
- App shell: `Analytics.kt`, `GalleryApplication.kt`, `MainActivity.kt`, `GalleryAppTopBar.kt`,
  `intents/IntentHandler.kt`, `di/AppModule.kt`, `runtime/ModelHelperExt.kt`.
- Model manager and downloads: `ui/modelmanager/ModelManagerViewModel.kt` (+39/-42; the audit
  fixes add `forgetLicenceRefusal`), `ui/modelmanager/GlobalModelManager.kt`,
  `data/DownloadRepository.kt`, `data/ModelUtils.kt`, `worker/DownloadWorker.kt` (protected file
  names for archives), `worker/DownloadSlowConnectionDetector.kt`, `worker/DownloadZipExtractor.kt`
  (protected files), `ui/common/ModelDownloadGates.kt` (login guard; the licence page no longer
  restarts a download).
- Audit fixes (2026-11-18) also touch: `gradle/libs.versions.toml` (MediaPipe pinned),
  `app/build.gradle.kts` (unit-test framework stubs return defaults), `AndroidManifest.xml`
  (AppAuth's redirect activity removed).
- Screens: `ui/navigation/GalleryNavGraph.kt`, `ui/home/SettingsDialog.kt`, `ui/home/HomeScreen.kt`,
  `ui/common/ModelPageAppBar.kt`, `ui/common/textandvoiceinput/TextAndVoiceInput.kt`,
  `ui/common/chat/ChatMessage.kt`, `ui/common/chat/MessageBodyWebview.kt`,
  `ui/common/GalleryWebView.kt`, `ui/common/MarkdownText.kt`, `ui/llmchat/LlmChatModelHelper.kt`.
- Agent Chat, skills and tools: `customtasks/agentchat/` (nine files), `skills/SkillManager.kt`,
  `skills/SkillExtensions.kt`, `tools/RunJsTool.kt`, `tools/RunIntentTool.kt`, `tools/RunMcpTool.kt`,
  `tools/ToolAction.kt`, five built-in skill pages under `assets/skills/`.
- Other tasks: `customtasks/mobileactions/` (three files), `customtasks/tinygarden/TinyGardenScreen.kt`,
  `services/photolibrary/PhotoLibraryService.kt`.
- Deleted in Phase 0 and still deleted: `FcmMessagingService.kt`, `notifications/*`,
  `ui/notifications/NotificationsScreen.kt`, `assets/skills/schedule-notification/`. Upstream did
  not change them up to 1.0.23; if it does, consider moving them to the exclusion list instead.
- Workflows: `build-apk.yml` is the fork's; upstream's `build_android.yaml` and `static.yml` are
  manual-only (`workflow_dispatch`).

## Hard conflicts to expect

Each was resolved in 1.0.23 as recorded in `docs/DECISIONS.md`; the same files will conflict again:

- `ui/modelmanager/ModelManagerViewModel.kt`: our blocks (lazy login service, one engine at a time,
  offline model list, skipped runtimes and speech models, no resume at startup) inside upstream's
  most-changed file.
- `data/DownloadRepository.kt`, `worker/DownloadWorker.kt`: the Phase 0.1 download rules on top of
  upstream's downloader.
- Moved or deleted upstream files we had edited (1.0.23: `HoldToDictateViewModel.kt`, moved to
  `ui/common/asr/`; `runtime/aicore/AICoreModelHelper.kt`, deleted by us).
- `Analytics.kt`: upstream's wrapper keeps growing; its delegate must stay the no-op type.
- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`: upstream adds
  libraries, plugins, permissions and `<queries>` entries here.

## Pitfalls met in the 1.0.23 pass

- **KSP sees every file in a source folder.** Hilt failed on the excluded files until the app was
  compiled from a filtered copy; `sourceSets.exclude` does not reach KSP.
- **Kotlin block comments nest.** A KDoc that mentions `<folder>/**` opens a second comment and
  the file stops compiling far below.
- **Maven Central answers HTTP 429** on a fresh Gradle cache: wait and retry.
- **Upstream's list JSON is not strict JSON** (1_0_23 has a trailing comma). Gson reads it
  leniently; strict parsers don't.
- **The model list adds models with new runtime or model types** (1.0.23: `mlkit_speech`,
  `mlkit_asr`, `modelType` `tts`/`asr`). The fork's allowlist loop must skip runtimes and model
  types whose code is out of the build.
- **Upstream re-adds automatic behaviour.** 1.0.23's `processPendingDownloads` resumed partial
  downloads at startup and `ModelDownloadGates` started the Hugging Face login; both needed fork
  blocks. Look for startup work in the model manager, `GalleryApplication` and `MainActivity`.
- **A WorkManager constraint is automatic behaviour too.** The Phase 0.1 "wait for a network"
  constraint would make WorkManager restart a download by itself; it was dropped.
- **The home screen dereferences featured tasks with `!!`** (`SMART_ALBUM`,
  `VIDEO_MOMENT_FINDER`; `LLM_XR_LANGUAGE_TEACHER` only when registered). Excluding one of those
  tasks would crash Home.
- **MediaPipe's `tasks-core` ships the audio classifier's option protos** under
  `com/google/mediapipe/tasks/audio/`: data classes, present before the sync, not tasks-audio.
- **`git diff <tag> -- <path>` reports an untracked file as deleted**; stage a restored upstream file
  before comparing it with the tag.
- **CI caches are per branch.** Before the signing secrets existed (until 2026-11-17), each branch
  signed with its own cached debug key, so branch APKs didn't install over each other. One key now
  signs every branch.
- **Secret values are masked.** GitHub drops a job output that contains a secret's value and shows
  `***` for it in logs; the key alias is a word in the tag names. Pass only neutral values between
  jobs (run 30 lost its tag this way and stopped before publishing).
- **Startup auto-downloads.** `ModelManagerViewModel.startAutoDownloadModels()` downloads at startup
  every model whose runtime sets `autoDownloadsOnStartup`. Only AICore does, and AICore models are
  skipped. If upstream sets it for another runtime, downloads would start by themselves: check it at
  every sync.
- **versionCode.** Upstream's versionCode (52 at 1.0.23) is not taken: builds of every branch stay
  at the fork's versionCode so they install over each other in both directions.
- **Native code can phone home through Java.** MediaPipe 1.2.0's native library sends usage
  stats with `java.net.HttpURLConnection` over JNI: no socket import, no dex class, nothing the
  Java stub or the dependency guard can see. Step 5 of "After a merge" exists for this.
- **Generated classes live in the stand-ins' packages.** Hilt writes `<Type>_Factory` and
  `<Type>_HiltModules*` next to each injectable stand-in, and D8 writes `$$ExternalSynthetic*`
  classes with the source name `D8$$SyntheticClass`; the APK check accepts exactly these.
- **The licence page restarted a download.** Upstream's `ModelDownloadGates.onAgreementTabClosed`
  restarted a licence-gated download when the tab closed; the fork drops the licence refusal and
  waits for a tap. Check that this block survives a merge.

## Known gaps

- **No final size check before a model file is renamed** (audit N9). The worker checks each
  response's body against its Content-Length, but not the finished file against the model list's
  size, so a server that serves a short file whole leaves a short model. Closing it needs each
  file's expected size in the work's input (DownloadRepository passes only the total), and model
  list sizes known to be exact (several are multiples of 4 KiB, which may be rounding). Until then
  a short file shows up as a model that fails to load; Discard and Download fix it.
