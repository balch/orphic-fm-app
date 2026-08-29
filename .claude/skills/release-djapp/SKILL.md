---
name: release-djapp
description: Use when cutting a new release of the DJ app (Orphic DJ) — tagging, building AAB + APK, creating a GitHub release with structured notes (without attaching binaries to the release), publishing to the Google Play alpha track and/or Apple TestFlight, and handling mid-release commit additions or backfilling releases for tags that already exist. Triggers on phrases like "cut a release", "tag v1.x.x", "release the dj app", "build a release aab", "create a github release", "publish to testflight", "publish to alpha", or "backfill releases for old tags". Use this skill even when the user only mentions one part of the flow (just tagging, just building, just iOS) — the steps interlock and a tag without a release-notes plan tends to drift out of sync with the artifact on disk.
---

# Releasing the DJ App

## What this skill is for

The DJ app's release pipeline has three main components (git tag → AAB/APK build → Store upload & GitHub release) that all need to stay in sync. Note that we do **not** attach AAB/APK binaries to the GitHub release page anymore, as they are published directly to the Play/App stores. The convention plugin (`build-logic/convention/src/main/kotlin/orpheus.android.app.gradle.kts`) derives versions from `git describe --tags --always --dirty`, so **the tag has to exist before the build** for the artifact name to come out right. If you build first you end up with a `djapp-v1.0-N-gSHA-release.aab` filename and a versionCode based on the rev-list count alone, which won't supersede previous Play Store uploads cleanly.

This skill captures the canonical flow plus the recovery paths for the common "I need to add one more commit" and "I never made a GitHub release for v1.0.0" cases.

## Mental model: versioning is git-derived

The convention plugin reads two things at configuration time:

- `gitVersionCode = git rev-list --count HEAD` — every commit bumps this by 1.
- `gitVersionName = git describe --tags --always --dirty` — resolves to the tag if HEAD is exactly tagged, otherwise something like `v1.1.0-3-gabc1234`, otherwise the short SHA.

The AAB / APK base name gets `-v$versionTag` appended automatically (`base.archivesName.set("$archivesBase-v$versionTag")`), and the flavor + build type follow. So when HEAD is exactly at a `vX.Y.Z` tag, you get clean filenames like `djapp-v1.9.0-og-release.aab`. When it isn't, you get a noisier suffix. **Always tag before building distributable variants.**

Debug-like build types (`debug`, `debugRelease`) pin to `versionCode = 1` and `versionName = "dev"` so installs aren't invalidated by every commit. Only `release` and `benchmark` use the git-derived numbers.

## The canonical flow

For a clean release at HEAD on `main`:

### 1. Pre-flight

```bash
# Make sure HEAD is what you want to ship and origin/main is in sync.
git status                   # working tree clean
git fetch origin             # any new tags / commits?
git log --oneline -10        # confirm HEAD
git tag -l "v1.*" --sort=-creatordate | head -5   # what's the last release?
```

If working tree isn't clean, ask the user — `--dirty` would taint the version name.

### 2. Annotated tag

Use an **annotated** tag (`-a`), not a lightweight one. `git describe` prefers annotated tags, and the message becomes the canonical changelog source.

```bash
git tag -a v1.X.Y -m "$(cat <<'EOF'
v1.X.Y: <one-line summary>

- Bullet 1
- Bullet 2
- ...
EOF
)" HEAD
```

Tag-message structure mirrors what ends up in the GitHub release body. Keep bullets factual (what changed, where), not marketing.

### 3. Build the AAB (Play Console upload format)

**Flavors matter.** The app has an `edition` dimension with two flavors: `og` (appId
`org.balch.djapp`) and `ai` (appId `org.balch.djapp.ai`, adds INTERNET and the AI deps).
**`og` is the flavor that ships to the Play Store.** The unqualified `bundleRelease` /
`assembleRelease` tasks build *every* release variant, so name the flavor explicitly:

```bash
./gradlew :apps:djapp:androidApp:bundleOgRelease
```

Output: `apps/djapp/androidApp/build/outputs/bundle/ogRelease/djapp-v1.X.Y-og-release.aab`

Note the flavor appears in three places — the task name (`bundleOgRelease`), the output
directory (`bundle/ogRelease/`), and the filename (`-og-release`).

This is the file you upload to Play Console. Signed with the release keystore if `keystore.properties` exists at the repo root, otherwise the debug key (which Play will reject).

### 4. Build the APK (sideload distribution)

```bash
./gradlew :apps:djapp:androidApp:assembleOgRelease
```

Output: `apps/djapp/androidApp/build/outputs/apk/og/release/djapp-v1.X.Y-og-release.apk`

The APK path splits the flavor and build type into **separate directories** (`apk/og/release/`),
unlike the bundle's single combined `bundle/ogRelease/`. Easy to get wrong; `find`ing the
freshly-built file is faster than guessing.

This is a **single universal APK** containing both `arm64-v8a` and `x86_64` ABIs (the convention plugin sets `abiFilters` but no `splits.abi` block, so they go in one file). Friendlier than per-ABI APKs for testers who don't know their architecture. Typically ~8–9 MB.

The two builds reuse most intermediates, so running them back-to-back works:
`./gradlew :apps:djapp:androidApp:bundleOgRelease :apps:djapp:androidApp:assembleOgRelease`.

### 5. Push the tag

```bash
git push origin v1.X.Y
```

This is a visible action against the shared remote — fine without re-confirmation when the user explicitly asked for "cut a release" or "tag and release". Don't push tags they haven't explicitly created.

### 6. Create the GitHub release (without attaching binaries)

```bash
gh release create v1.X.Y \
  --title "v1.X.Y — <short headline>" \
  --notes "$(cat <<'EOF'
<release body — see "Release-notes structure" below>
EOF
)"
```

Verify with `gh release view v1.X.Y` and surface the URL to the user.

### 7. Publish to Google Play (alpha track)

The signed AAB is uploaded to Play via **Gradle Play Publisher (GPP)**, not by hand. The
**going-forward closed testing track is `alpha`** (the former custom `Launch` track was
retired 2026-06-22). Publish the bundle built in step 3:

```bash
./gradlew --no-configuration-cache :apps:djapp:androidApp:publishOgReleaseBundle -PplayTrack=alpha
```

`publishOgReleaseBundle` builds + signs + uploads the AAB and assigns it to the track in one
task. Use the **flavor-qualified** name: bare `publishReleaseBundle` would publish every
release variant, and `ai` is not a Play Store app. There is **no `--track` CLI flag** — the
track is the `-PplayTrack=<id>` Gradle property read by `play { track.set(...) }` in
`apps/djapp/androidApp/build.gradle.kts`. Omit it and it defaults to `internal`. Success
looks like GPP logging
`Updating [completed] release (org.balch.djapp:[<versionCode>]) in track 'alpha'` then
`Committing changes`.

**`publishOgReleaseBundle` does not touch the store listing.** It uploads the bundle and the
`release-notes/` text for that release only. Title, descriptions and graphics move via
`publishListing` / `publishOgApps`, so when the user says "leave the listing alone", the
bundle task is already the right choice — just don't edit `src/main/play/` and the previous
"What's New" copy carries over verbatim.

**The "What's New" Play notes are a file**, separate from the GitHub release body:
`apps/djapp/androidApp/src/main/play/release-notes/en-US/default.txt`. GPP sends whatever is
in that file. To reuse the previous release's copy, leave it untouched. To force the
in-app-update flow, add `-PplayUpdatePriority=5` (un-deferrable) or `4` (after 3+ days stale).

**Promote instead of re-publish** to move an *already-uploaded* versionCode to another track
(no rebuild, no fresh review of the bytes):

```bash
./gradlew :apps:djapp:androidApp:promoteOgReleaseArtifact \
  --from-track <source> --promote-track alpha --version-code <N> --release-status completed
```

Re-running `publishOgReleaseBundle` for a versionCode that's already uploaded is a no-op
(duplicate versionCode → GPP reports `UP-TO-DATE`); use promote for that case.

Full Play-publishing details (service account, credentials lookup, the androidpublisher-scoped
token recipe for raw `edits` API track queries, and per-version history) live in the
`project_djapp_play_publishing` memory.

### 8. Build + upload iOS to TestFlight

Orphic DJ (OG edition, Xcode scheme `DjApp`, bundle `org.balch.djapp.app`) ships to the App
Store / TestFlight the same way the Android side ships to Play: via CLI, authenticated with an
App Store Connect **API key** rather than an interactive Xcode account session (the account
session goes stale independently of the cert — see the `reference_apple_dev_team` memory).

**Credentials — never hardcode these in a committed file:**

- The `.p8` private key lives at `apps/djapp/play-store/.secrets/AuthKey_<KEYID>.p8`
  (gitignored — confirm with `git check-ignore -v` before touching that directory).
- The **Key ID** is the `<KEYID>` in the filename above.
- The **Issuer ID** (a UUID) is account-level, from App Store Connect → Users and Access →
  Integrations → App Store Connect API. It is **not** stored in this repo. Check the
  `reference_apple_dev_team` / `ios-appstore-publishing` memories first; if neither has it,
  ask the user or have them look it up in the ASC UI — don't guess, and don't paste it into
  any file this skill or the repo tracks.

**Prerequisites:** `xcodegen` (Homebrew), `DEVELOPMENT_TEAM` set in
`apps/djapp/iosApp/project.yml`, and an `ExportOptions.plist` with
`method: app-store-connect` (one already exists under
`apps/djapp/iosApp/build/export-appstore*/ExportOptions.plist` — reuse it rather than
recreating).

```bash
# 1. Regenerate the Xcode project from project.yml (picks up any project.yml edits).
xcodegen generate --spec apps/djapp/iosApp/project.yml --project apps/djapp/iosApp

# 2. Build the shared KMP framework for device, Release configuration.
./gradlew --no-configuration-cache :apps:djapp:shared:linkReleaseFrameworkIosArm64

# 3. Compute versions from git — same source of truth as Android, see the "iOS mirrors
#    Android versioning" note below.
TAG=$(git describe --tags --abbrev=0 --always)
MARKETING_VERSION="${TAG#v}"
CURRENT_PROJECT_VERSION=$(git rev-list --count HEAD)

# 4. Archive. ALL paths must be absolute — a relative -authenticationKeyPath hard-errors,
#    and a relative -project silently resolves against the wrong cwd.
xcodebuild -project <ABSOLUTE>/apps/djapp/iosApp/DjApp.xcodeproj -scheme DjApp \
  -configuration Release -destination generic/platform=iOS \
  -archivePath <ABSOLUTE>/DjApp.xcarchive \
  -allowProvisioningUpdates \
  -authenticationKeyPath <ABSOLUTE>/apps/djapp/play-store/.secrets/AuthKey_<KEYID>.p8 \
  -authenticationKeyID <KEYID> -authenticationKeyIssuerID <ISSUER_ID> \
  MARKETING_VERSION="$MARKETING_VERSION" CURRENT_PROJECT_VERSION="$CURRENT_PROJECT_VERSION" \
  archive > /tmp/xcodebuild-archive.log 2>&1
echo "EXIT_CODE=$?"   # `xcodebuild ... | tee log` reports tee's exit (0) even on failure —
                       # always redirect + check $? explicitly instead.

# 5. Export the signed IPA.
xcodebuild -exportArchive \
  -exportOptionsPlist <ABSOLUTE>/apps/djapp/iosApp/build/export-appstore3/ExportOptions.plist \
  -archivePath <ABSOLUTE>/DjApp.xcarchive \
  -exportPath <ABSOLUTE>/export-appstore-vX.Y.Z \
  -allowProvisioningUpdates \
  -authenticationKeyPath <ABSOLUTE>/apps/djapp/play-store/.secrets/AuthKey_<KEYID>.p8 \
  -authenticationKeyID <KEYID> -authenticationKeyIssuerID <ISSUER_ID> \
  > /tmp/xcodebuild-export.log 2>&1

# 6. Upload to App Store Connect. altool needs the key named exactly `AuthKey_<KEYID>.p8`
#    in the dir pointed to by API_PRIVATE_KEYS_DIR.
API_PRIVATE_KEYS_DIR=<ABSOLUTE>/apps/djapp/play-store/.secrets \
  xcrun altool --upload-app -f <ABSOLUTE>/export-appstore-vX.Y.Z/DjApp.ipa --type ios \
  --apiKey <KEYID> --apiIssuer <ISSUER_ID>
```

Success looks like `UPLOAD SUCCEEDED with no errors` plus a `Delivery UUID`. Apple then
processes the build server-side (usually a few minutes) before it appears under TestFlight in
App Store Connect. **Internal** testers need nothing further — internal groups pick up every
processed build automatically. **External** testers need step 9; the upload alone does not
reach them.

**iOS mirrors Android versioning, with one deliberate deviation.** `CURRENT_PROJECT_VERSION`
uses the exact same `git rev-list --count HEAD` as Android's `versionCode` (monotonic forever,
satisfies Apple's always-increasing build-number rule). `MARKETING_VERSION` uses
`git describe --tags --abbrev=0 --always` (nearest tag only, **no** `-N-gHASH-dirty` suffix)
rather than Android's full `--dirty` string — App Store Connect groups builds by
`CFBundleShortVersionString`, so a suffix that changes every commit would fork every build into
its own version group instead of stacking under one release.

**Gotchas:**

- The archive step itself signs with whatever identity matches (often "Apple Development");
  the **export** step is what re-signs for distribution using a cloud-managed Distribution
  cert — nothing shows in `security find-identity` for it, that's expected, not a failure.
- If `xcodebuild` fails with a login/profile error, it's almost always the yearly Development
  cert or the Xcode account session going stale — the API-key path above is what avoids
  depending on that session at all. See `reference_apple_dev_team` memory.
- `DjAppAi` is Android/desktop-only right now — there's no iOS AI-edition graph, so only the
  `DjApp` (OG) scheme ships to TestFlight/App Store.

### 9. Assign TestFlight groups + submit for Beta App Review

`altool` only delivers the binary. Beta groups are a **separate App Store Connect API step**, and
the two group types behave differently in ways that are easy to misread as success:

- **Internal group** (`DjAppTesters`): receives every processed build automatically. Explicitly
  assigning one returns HTTP **422** `"Cannot add internal group to a build."` That is not an
  error to fix — verify with `GET /v1/betaGroups/{gid}/builds` and the build is already there.
- **External group** (`DjAppExt`): membership alone does **not** distribute. The POST returns 204
  and the build appears in the group, but external testers receive nothing until a **Beta App
  Review** submission exists and Apple approves it.

**Do not treat the 204 as done.** Check `GET /v1/builds/{id}/betaAppReviewSubmission`; `data: null`
means not submitted. Cross-check against prior builds — historically every externally-distributed
build shows `betaReviewState=APPROVED`, so a `None` on the new one is the tell.

The system `python3` has neither PyJWT nor `cryptography`. Build a throwaway env:

```bash
uv venv asc-env
uv pip install --python asc-env/bin/python "pyjwt[crypto]" requests
```

Auth is a JWT: ES256, `aud=appstoreconnect-v1`, `kid` = Key ID, `iss` = Issuer ID, short `exp`.
Same `.p8` as the upload. Key ID / Issuer ID / app ID / group IDs are in the
`project_ios_appstore_publishing` and `reference_apple_dev_team` memories — **never write them
into this repo.**

```
GET  /v1/betaGroups?filter[app]=<APP_ID>              # group ids + isInternalGroup
GET  /v1/builds?filter[app]=<APP_ID>&filter[version]=<BUILD_NUMBER>
                                                      # poll until processingState=VALID
POST /v1/builds/<BUILD_ID>/relationships/betaGroups   # {"data":[{"type":"betaGroups","id":"<GID>"}]}
                                                      # 204 external OK / 422 internal (expected)
GET  /v1/builds/<BUILD_ID>/betaAppReviewSubmission    # data:null = NOT submitted
POST /v1/betaAppReviewSubmissions                     # relationships.build -> build id
                                                      # 201 + betaReviewState=WAITING_FOR_REVIEW
GET  /v1/betaGroups/<GID>/builds                      # verify membership
```

Order matters: the build must reach `processingState=VALID` before any of the group or review
calls will find it. Processing typically takes a couple of minutes after `UPLOAD SUCCEEDED`; poll
rather than guessing a delay.

**Gotcha:** `GET /v1/builds/{id}/betaGroups` returns **403** with this API key. Query the inverse,
`GET /v1/betaGroups/{gid}/builds`, to confirm membership.

**Submitting for Beta App Review is an outward-facing action** — it puts the build in front of
Apple and, on approval, in front of external testers. Confirm with the user before submitting,
especially when the release carries pre-release dependencies or changes only verified on
simulator.

## Release-notes structure

The body should feel useful to a developer scanning the release page, not like a marketing announcement. Group by what the user/operator cares about, lead with the highest-impact items. The template that has held up well across versions:

```markdown
<One-paragraph framing — what kind of release this is, why it exists.>

## <Highest-impact category — e.g. "Audio focus correctness">

- **Bold lead-in.** Sentence-form explanation of the change, including the failure mode it fixes when relevant.
- ...

## <Next category — e.g. "Lifecycle / resource leaks">

- ...

## <Smaller bucket — e.g. "Cast / DLNA metadata", "Content", "DSP">

- ...

## Tests

- <N> new <TestClass> cases cover ... . <total/total> tests pass.

## Artifacts

The signed Play-Console-ready bundle (`djapp-v1.X.Y-og-release.aab`) and universal sideload APK (`djapp-v1.X.Y-og-release.apk`) are built, but are not attached to this release. The bundle is published directly to the Play Store alpha closed testing track.

## Full changelog

`vA.B.C...v1.X.Y` — <one-line synopsis>.
```

**Style notes:**

- Use backtick-quoted symbol names (`onPauseFromFocusLoss`, `MediaSession`) so they render as code in GitHub's renderer.
- Explain *why* each change matters, especially for fixes — "X now does Y because the old behavior caused Z" is more useful than "X now does Y".
- Bold the lead clause of each bullet so the page is scannable.
- The "Full changelog" footer uses GitHub's auto-generated diff URL syntax — `vA.B.C...vX.Y.Z` becomes a link automatically.
- Don't claim things the binary doesn't actually do.

**What NOT to include:**

- Internal Linear/Jira/issue numbers without context.
- Marketing copy ("the best", "next-generation"). The Play Store listing handles user-facing pitch separately.
- Mutable Instruments / Plaits / Rings / Clouds / Warps / Elements / Braids / Streams / Tides — trademarks. See the project's user-facing documentation rules.

## Common recovery: "add one more commit to a published release"

Tag has been pushed and GitHub release published, then the user lands another commit they want to include. This is destructive — you're re-pointing a published ref — but it's a routine operation when you catch a small fix between tagging and shipping. Flag the destructiveness once, then do it:

```bash
# 1. Move the tag locally.
git tag -d v1.X.Y
git tag -a v1.X.Y -m "$(cat <<'EOF'
<updated tag message — include the new commit's contribution>
EOF
)" HEAD

# 2. Force-push the tag.
git push origin v1.X.Y --force

# 3. Rebuild artifacts at the new SHA and re-publish them to Google Play / TestFlight.
./gradlew :apps:djapp:androidApp:bundleOgRelease :apps:djapp:androidApp:assembleOgRelease

# 4. Edit the release notes on GitHub to mention the new change.
gh release edit v1.X.Y --notes "$(cat <<'EOF'
<updated body — add the new commit under the appropriate section>
EOF
)"
```

A couple of nuances:

- The GitHub release stays attached to the tag *by name*, so re-pointing the tag silently moves the release with it — no need to delete & recreate.
- `versionCode` will bump automatically (new commit → higher `rev-list --count`), so the new AAB will cleanly supersede a previous Play Store upload of the same `versionName`.
- Anyone who already pulled the old `v1.X.Y` ref will see "diverged tag" on next fetch. That's the cost; the user accepted it by asking to add the commit.

## Common recovery: backfill releases for existing tags

Tags exist on origin but no GitHub release page was ever created (`gh release list` shows them missing). Workflow:

```bash
# Inspect each tag's annotation — that's your release-notes raw material.
git for-each-ref refs/tags/v1.0.0 refs/tags/v1.1.0 \
  --format="=== %(refname:short) (%(taggerdate:short)) ===%0a%(contents)"

# Walk commits between consecutive tags to flesh out the notes.
git log v1.0.0...v1.1.0 --oneline
```

For each missing release:

```bash
gh release create vA.B.C \
  --title "vA.B.C — <short headline>" \
  --notes "$(cat <<'EOF'
<release body>
EOF
)"
```

Do not attach binaries to the GitHub release; they are uploaded directly to Google Play / App Store Connect.

When backfilling multiple tags in one session, create them oldest-first so the natural ordering on the releases page matches release order. `gh release` automatically marks the last-created (newest tag chronologically by tag date, not creation order) as "Latest" — if the order ends up wrong, fix with:

```bash
gh release edit v1.X.Y --latest
```

## Verifying versions match expectations

After the build, sanity-check the `versionCode` / `versionName` actually baked into the AAB before uploading to Play Console:

```bash
# Need bundletool or aapt2; with bundletool:
bundletool dump manifest --bundle=path/to/djapp-v1.X.Y-release.aab | \
  grep -E "versionCode|versionName"
```

If `versionName` doesn't match the tag, HEAD wasn't actually at the tag when you built — most likely because someone landed a commit between `git tag` and `./gradlew`. Re-tag (see "add one more commit" above) and rebuild.

## Reference commands

| Goal | Command |
|------|---------|
| List recent tags | `git tag --sort=-creatordate \| head -10` |
| Show tag annotation | `git for-each-ref refs/tags/vX.Y.Z --format="%(contents)"` |
| Commits since last tag | `git log vX.Y.Z..HEAD --oneline` |
| Build AAB (shipping flavor) | `./gradlew :apps:djapp:androidApp:bundleOgRelease` |
| Build universal APK | `./gradlew :apps:djapp:androidApp:assembleOgRelease` |
| AAB output path | `apps/djapp/androidApp/build/outputs/bundle/ogRelease/djapp-vX.Y.Z-og-release.aab` |
| APK output path | `apps/djapp/androidApp/build/outputs/apk/og/release/djapp-vX.Y.Z-og-release.apk` |
| Publish to a Play track | `./gradlew --no-configuration-cache :apps:djapp:androidApp:publishOgReleaseBundle -PplayTrack=alpha` |
| List real task names | `./gradlew :apps:djapp:androidApp:tasks --all \| grep -iE "^bundle\|^publish"` |
| Push a tag | `git push origin vX.Y.Z` |
| Force-update a published tag | `git push origin vX.Y.Z --force` |
| List GitHub releases | `gh release list --limit 10` |
| View a release | `gh release view vX.Y.Z` |
| Create release | `gh release create vX.Y.Z --title "..." --notes "..."` |
| Edit notes | `gh release edit vX.Y.Z --notes "..."` |
| Mark a release latest | `gh release edit vX.Y.Z --latest` |
| Regenerate iOS Xcode project | `xcodegen generate --spec apps/djapp/iosApp/project.yml --project apps/djapp/iosApp` |
| Build iOS release framework | `./gradlew :apps:djapp:shared:linkReleaseFrameworkIosArm64` |
| Upload IPA to App Store Connect | `xcrun altool --upload-app -f <ipa> --type ios --apiKey <KEYID> --apiIssuer <ISSUER_ID>` |
| List TestFlight beta groups | `GET /v1/betaGroups?filter[app]=<APP_ID>` (see step 9) |
| Wait for a build to process | `GET /v1/builds?filter[app]=<APP_ID>&filter[version]=<N>` until `processingState=VALID` |
| Attach build to external group | `POST /v1/builds/<BUILD_ID>/relationships/betaGroups` (internal groups 422 by design) |
| Check if beta review submitted | `GET /v1/builds/<BUILD_ID>/betaAppReviewSubmission` (`data:null` = not submitted) |
| Submit for Beta App Review | `POST /v1/betaAppReviewSubmissions` relating `build` |

## When something looks off

- **Filename has a `-N-gSHA` suffix** (e.g. `djapp-v1.1.0-3-gabc1234-release.aab`): HEAD isn't on a tag. Either tag HEAD first, or check out the right tag.
- **Filename says `-dirty`**: working tree has uncommitted changes. Commit them first — the current flow is to tweak on a feature branch, then land it on `main` as logical, separately revertable commits. Reach for `git stash` only if the change genuinely should not ship, and check first that the dirty files are yours: the tree often carries the user's WIP or a sibling agent's edits.
- **`gh release create` fails with "release already exists"**: the release exists but maybe without binaries — use `gh release upload <tag> <file>` to add assets, or `gh release edit` to update notes.
- **Build picks up the wrong `versionCode`**: configuration cache. Try `./gradlew --no-configuration-cache :apps:djapp:androidApp:bundleOgRelease`.
- **Built artifact isn't where you expected**: the flavor segment moves between the bundle and APK trees (`bundle/ogRelease/` vs `apk/og/release/`). `find apps/djapp/androidApp/build/outputs -name "*-v1.X.Y-*"` beats guessing.
- **You built the `ai` flavor by mistake**: it carries appId `org.balch.djapp.ai` and the INTERNET permission. Play will treat it as a different app. Rebuild with the `Og` task.
- **AAB is unsigned**: `keystore.properties` is missing at the repo root. The convention plugin silently falls back to the debug signing config in that case. Play Console will reject the upload.
- **A commit landed after the tag and you want the tag to include it**: see "add one more commit to a published release" above.
- **iOS `xcodebuild archive`/`-exportArchive` fails with a login or provisioning-profile error**: the yearly Development cert or the Xcode account session has gone stale — see `reference_apple_dev_team` memory. Not a project misconfiguration.
- **iOS build succeeds but you don't have the Issuer ID**: check the `reference_apple_dev_team` / `ios-appstore-publishing` memories first. Never store it (or the `.p8` key) in a committed file.
