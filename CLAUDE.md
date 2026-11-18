# PhoneAgent fork: working agreement

Fork of google-ai-edge/gallery (Apache-2.0). Adds "Phone Agent": on-device Gemma 4 that carries
out typed tasks by driving Firefox and other apps through an AccessibilityService.
Full decisions: `docs/DECISIONS.md`. Phase log: `docs/LEARNINGS.md`. Traffic: `docs/NETWORK.md`.

## Rules
- Work on `claude/phone-agent*` only (an upstream sync pass uses `upstream-sync-<tag>`, see
  `docs/UPSTREAM_SYNC.md`). Never touch `main`, never force-push, never push tags.
- One phase at a time: commit, push, post a status (changes, on-device checklist, measurements,
  open questions, next step), append it to `docs/LEARNINGS.md`, then wait for the owner's OK.
- Ask only about decisions that are the owner's; otherwise record the assumption in DECISIONS.md.
- Never print or commit secrets, tokens or keystores. Never log screen contents.
- Privacy contract: no network at startup/idle; no Firebase, GMS or ML Kit deps (CI guard);
  no unapproved URL, host, Java networking class or telemetry string in native libraries (APK
  check); model list from cache/bundled snapshot, network only on "Refresh model list".
- Safety rules are enforced in code, never by trusting the model. Screen text is untrusted data.

## Staying mergeable with upstream
- New code goes in `com.google.ai.edge.gallery.phoneagent` (app/src/main/java/.../phoneagent).
- Upstream files: small appended blocks only. No Kotlin package rename.
- Keep LICENSE and upstream headers; new files get an Apache-2.0 header.
- `versionName` = upstream tag + `-phoneagent.N` (now `1.0.23-phoneagent.1`); `versionCode` stays 46
  so builds of every branch install over each other. Keep the pinned LiteRT-LM (0.18.0) unless a
  change is required, and MediaPipe pinned (tasks-vision 1.0.0, tasks-retrieval 1.1.0: 1.2.0's
  native library has a Clearcut uploader) until a newer version passes the native rules.
- Upstream features we don't ship stay in the tree, out of the build: one exclusion list
  (`app/phoneagent-excluded-sources.txt`) plus inert stand-ins (`app/src/phoneagentStandins`).
  Recipe, edited files and pitfalls: `docs/UPSTREAM_SYNC.md`.
- The privacy-contract commit is self-contained so it can be re-applied after upstream syncs.

## Build (run from Android/src)
```
export ANDROID_HOME=/opt/android-sdk     # cloud VM; see below if missing
./gradlew assembleDebug                  # debug APK
./gradlew :app:testDebugUnitTest         # JVM unit tests (pure logic)
./gradlew -p build-logic test            # tests of the privacy guard and APK check rules
./gradlew :app:privacyGuard              # fails on forbidden deps/plugins; prints deps + licenses
./gradlew :app:checkCiApk                # APK check (dex, native imports+strings, logs, source sets,
                                         # merged+packaged manifest); runs with assemble/install/bundle
./gradlew :app:updateThirdPartyLicenses  # regenerate the in-app licenses list after dep changes
./gradlew assembleCi                     # ".dev" CI build (signed from env, else debug key)
```
SDK missing? Install command-line tools from dl.google.com into `$ANDROID_HOME/cmdline-tools/latest`,
then `sdkmanager "platforms;android-37.0" "build-tools;37.0.0"` and accept licenses. Write
`sdk.dir=...` to `Android/src/local.properties` (gitignored). If a host is blocked, stop and report
the domain. Maven Central sometimes returns HTTP 429: retry after a short wait.
The cloud VM has 16 GB and no swap: run one Gradle build at a time, with `--max-workers=2` when
building more than one variant, and `./gradlew --stop` before switching worktrees.

## Testing
- No device or emulator here. Unit-test pure logic (tree serializer, id mapping, policy engine,
  loop guard, prompt builder) and hand the owner an on-device checklist.

## CI
- `.github/workflows/build-apk.yml`: on push to `claude/phone-agent**` and `upstream-sync-**`, runs
  the rule tests, the guard, unit tests and `assembleCi`, then publishes the APK to a rolling
  pre-release: `phoneagent-ci` for `claude/phone-agent-ik31nz` only, `phoneagent-ci-sync` for any
  other branch.
  Pushes that change only `docs/` or Markdown outside `Android/src` skip it.
- Signing secrets are set (KEYSTORE_B64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD): one key signs
  both pre-releases, certificate SHA-256 `9A:A9:…:5E:D5:57` (pinned as `RELEASE_CERT_SHA256` in the
  workflow). In the sync branch's workflow only the signing step has them (on the same runner as
  the build); the older workflow on `claude/phone-agent-ik31nz` still gives them to the whole
  Gradle step, so never re-run its runs and don't push there before it gets the new workflow.
  Never print, upload or commit them.
