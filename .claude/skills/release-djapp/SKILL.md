---
name: release-djapp
description: Use when cutting a new release of the DJ app (Orphic DJ) — tagging, building AAB + APK, creating a GitHub release with structured notes, attaching artifacts, and handling mid-release commit additions or backfilling releases for tags that already exist. Triggers on phrases like "cut a release", "tag v1.x.x", "release the dj app", "build a release aab", "create a github release", or "backfill releases for old tags". Use this skill even when the user only mentions one part of the flow (just tagging, just building) — the steps interlock and a tag without a release-notes plan tends to drift out of sync with the artifact on disk.
---

# Releasing the DJ App

## What this skill is for

The DJ app's release pipeline has three artifacts (git tag → AAB → APK → GitHub release) that all need to stay in sync. The convention plugin (`build-logic/convention/src/main/kotlin/orpheus.android.app.gradle.kts`) derives versions from `git describe --tags --always --dirty`, so **the tag has to exist before the build** for the artifact name to come out right. If you build first you end up with a `djapp-v1.0-N-gSHA-release.aab` filename and a versionCode based on the rev-list count alone, which won't supersede previous Play Store uploads cleanly.

This skill captures the canonical flow plus the recovery paths for the common "I need to add one more commit" and "I never made a GitHub release for v1.0.0" cases.

## Mental model: versioning is git-derived

The convention plugin reads two things at configuration time:

- `gitVersionCode = git rev-list --count HEAD` — every commit bumps this by 1.
- `gitVersionName = git describe --tags --always --dirty` — resolves to the tag if HEAD is exactly tagged, otherwise something like `v1.1.0-3-gabc1234`, otherwise the short SHA.

The AAB / APK base name gets `-v$versionTag` appended automatically (`base.archivesName.set("$archivesBase-v$versionTag")`). So when HEAD is exactly at a `vX.Y.Z` tag, you get clean filenames like `djapp-v1.1.1-release.aab`. When it isn't, you get a noisier suffix. **Always tag before building distributable variants.**

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

> **Finalize the Play "What's New" file _before_ you tag.** The Play publish (step 7) rebuilds
> the AAB from **current HEAD**, not from the tag. Editing + committing
> `src/main/play/release-notes/en-US/default.txt` _after_ tagging (a natural release-finishing
> instinct) puts one commit on top of the tag, so the published AAB is built one commit past it:
> `versionName` comes out `vX.Y.Z-1-gSHA` (**not** dirty — the `-1-gSHA` just means "1 commit
> after the tag") and `versionCode` bumps by 1. The GitHub artifacts, built at the tag in step 3,
> stay clean — so the two surfaces diverge. Commit the notes file as part of the pre-tag state and
> both come out clean. If you catch it too late, see the recovery in "Verifying versions" below.

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

```bash
./gradlew :apps:djapp:androidApp:bundleRelease
```

Output: `apps/djapp/androidApp/build/outputs/bundle/release/djapp-v1.X.Y-release.aab`

This is the file you upload to Play Console. Signed with the release keystore if `keystore.properties` exists at the repo root, otherwise the debug key (which Play will reject).

### 4. Build the APK (sideload distribution)

```bash
./gradlew :apps:djapp:androidApp:assembleRelease
```

Output: `apps/djapp/androidApp/build/outputs/apk/release/djapp-v1.X.Y-release.apk`

This is a **single universal APK** containing both `arm64-v8a` and `x86_64` ABIs (the convention plugin sets `abiFilters` but no `splits.abi` block, so they go in one file). Friendlier than per-ABI APKs for testers who don't know their architecture. Typically ~7–8 MB.

The two builds reuse most intermediates, so running them in series is fine; running them back-to-back via `./gradlew :apps:djapp:androidApp:bundleRelease :apps:djapp:androidApp:assembleRelease` works too.

### 5. Push the tag

```bash
git push origin v1.X.Y
```

This is a visible action against the shared remote — fine without re-confirmation when the user explicitly asked for "cut a release" or "tag and release". Don't push tags they haven't explicitly created.

### 6. Create the GitHub release

```bash
gh release create v1.X.Y \
  --title "v1.X.Y — <short headline>" \
  --notes "$(cat <<'EOF'
<release body — see "Release-notes structure" below>
EOF
)" \
  path/to/djapp-v1.X.Y-release.aab \
  path/to/djapp-v1.X.Y-release.apk
```

Verify with `gh release view v1.X.Y` and surface the URL to the user.

### 7. Publish to Google Play (alpha track)

The signed AAB is uploaded to Play via **Gradle Play Publisher (GPP)**, not by hand. The
**going-forward closed testing track is `alpha`** (the former custom `Launch` track was
retired 2026-06-22). Publish the bundle built in step 3:

```bash
./gradlew --no-configuration-cache :apps:djapp:androidApp:publishReleaseBundle -PplayTrack=alpha
```

`publishReleaseBundle` builds + signs + uploads the AAB and assigns it to the track in one
task. There is **no `--track` CLI flag** — the track is the `-PplayTrack=<id>` Gradle
property read by `play { track.set(...) }` in `apps/djapp/androidApp/build.gradle.kts`. Omit
it and it defaults to `internal`. Success looks like GPP logging
`Updating [completed] release (org.balch.djapp:[<versionCode>]) in track 'alpha'` then
`Committing changes`.

> **`publishReleaseBundle` rebuilds from HEAD, not from the tag.** The version baked into the
> uploaded AAB reflects wherever HEAD sits _at publish time_ — `versionName` is the raw
> `git describe --tags --always --dirty`, `versionCode` is `git rev-list --count HEAD`. Any commit
> landed since the tag (even a docs/notes commit) yields `vX.Y.Z-N-gSHA` and a bumped code. The
> **filename still looks clean** (`djapp-vX.Y.Z-og-release.aab`) because `base.archivesName` strips
> the `-N-gSHA` suffix via a separate `versionTag` — so the filename is _not_ a reliable signal.
> Confirm the real values with `bundletool dump manifest` right before publishing (see "Verifying
> versions" below).

**The "What's New" Play notes are a file**, separate from the GitHub release body:
`apps/djapp/androidApp/src/main/play/release-notes/en-US/default.txt`. GPP sends whatever is
in that file. To reuse the previous release's copy, leave it untouched. To force the
in-app-update flow, add `-PplayUpdatePriority=5` (un-deferrable) or `4` (after 3+ days stale).

**Promote instead of re-publish** to move an *already-uploaded* versionCode to another track
(no rebuild, no fresh review of the bytes):

```bash
./gradlew :apps:djapp:androidApp:promoteReleaseArtifact \
  --from-track <source> --promote-track alpha --version-code <N> --release-status completed
```

Re-running `publishReleaseBundle` for a versionCode that's already uploaded is a no-op
(duplicate versionCode → GPP reports `UP-TO-DATE`); use promote for that case.

Full Play-publishing details (service account, credentials lookup, the androidpublisher-scoped
token recipe for raw `edits` API track queries, and per-version history) live in the
`project_djapp_play_publishing` memory.

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

The signed Play-Console-ready bundle is attached: **`djapp-v1.X.Y-release.aab`**.
A universal sideload APK is also attached: **`djapp-v1.X.Y-release.apk`**.

## Full changelog

`vA.B.C...v1.X.Y` — <one-line synopsis>.
```

**Style notes:**

- Use backtick-quoted symbol names (`onPauseFromFocusLoss`, `MediaSession`) so they render as code in GitHub's renderer.
- Explain *why* each change matters, especially for fixes — "X now does Y because the old behavior caused Z" is more useful than "X now does Y".
- Bold the lead clause of each bullet so the page is scannable.
- The "Full changelog" footer uses GitHub's auto-generated diff URL syntax — `vA.B.C...vX.Y.Z` becomes a link automatically.
- Don't claim things the AAB doesn't actually do — the binary on the release page is what users get.

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

# 3. Rebuild artifacts at the new SHA.
./gradlew :apps:djapp:androidApp:bundleRelease :apps:djapp:androidApp:assembleRelease

# 4. Replace the artifacts on the release. --clobber overwrites the existing file.
gh release upload v1.X.Y \
  apps/djapp/androidApp/build/outputs/bundle/release/djapp-v1.X.Y-release.aab \
  apps/djapp/androidApp/build/outputs/apk/release/djapp-v1.X.Y-release.apk \
  --clobber

# 5. Edit the release notes to mention the new change.
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
)" \
  [path/to/aab if available] \
  [path/to/apk if available]
```

If the AAB for an old tag isn't on disk anymore (it was uploaded directly to Play Console and the build artifacts have been cleaned), create the release **without** binaries and note in the body: *"AAB for this version was uploaded directly to the Play Console — no artifact attached to this GitHub release."* Don't try to rebuild old AABs by checking out the tag and running the gradle build — the keystore is required for a valid signed AAB and the dependency graph at the time of the original build may not match what's resolvable now.

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

If `versionName` doesn't match the tag, HEAD wasn't actually at the tag when you built — most likely because a commit landed between `git tag` and the build (or the Play notes were committed after tagging; see step 1). Re-tag (see "add one more commit" above) and rebuild.

**Recovery when the tainted `vX.Y.Z-N-gSHA` build is _already on Play_:** Play rejects any
versionCode ≤ the one already live on the track, so you can't rebuild at the tag — that code
(`rev-list --count` at the tag) is now _lower_ than what's uploaded. Instead, move the tag
**forward** onto a commit whose `git describe` is exactly `vX.Y.Z` and whose count exceeds the
tainted code, then republish:

```bash
git tag -d v1.X.Y
git tag -a v1.X.Y -m "<same annotation>" HEAD   # HEAD's rev-list count must exceed the tainted code
git push origin v1.X.Y --force
./gradlew --no-configuration-cache :apps:djapp:androidApp:bundleOgRelease :apps:djapp:androidApp:assembleOgRelease
# verify: bundletool dump manifest ... shows a clean "vX.Y.Z" versionName
./gradlew --no-configuration-cache :apps:djapp:androidApp:publishOgReleaseBundle -PplayTrack=alpha
```

The fresh upload (higher versionCode, clean versionName) supersedes the tainted one. Re-clobber
the GitHub artifacts (see "add one more commit") so both surfaces carry the same build. Note that
re-pointing an already-pushed tag is a force-push — flag it once, but it's the documented path.

## Reference commands

| Goal | Command |
|------|---------|
| List recent tags | `git tag --sort=-creatordate \| head -10` |
| Show tag annotation | `git for-each-ref refs/tags/vX.Y.Z --format="%(contents)"` |
| Commits since last tag | `git log vX.Y.Z..HEAD --oneline` |
| Build AAB | `./gradlew :apps:djapp:androidApp:bundleRelease` |
| Build universal APK | `./gradlew :apps:djapp:androidApp:assembleRelease` |
| AAB output path | `apps/djapp/androidApp/build/outputs/bundle/release/djapp-vX.Y.Z-release.aab` |
| APK output path | `apps/djapp/androidApp/build/outputs/apk/release/djapp-vX.Y.Z-release.apk` |
| Push a tag | `git push origin vX.Y.Z` |
| Force-update a published tag | `git push origin vX.Y.Z --force` |
| List GitHub releases | `gh release list --limit 10` |
| View a release | `gh release view vX.Y.Z` |
| Create release | `gh release create vX.Y.Z --title "..." --notes "..." <files>` |
| Replace an asset | `gh release upload vX.Y.Z <file> --clobber` |
| Edit notes | `gh release edit vX.Y.Z --notes "..."` |
| Mark a release latest | `gh release edit vX.Y.Z --latest` |

## When something looks off

- **Filename has a `-N-gSHA` suffix** (e.g. `djapp-v1.1.0-3-gabc1234-release.aab`): HEAD isn't on a tag. Either tag HEAD first, or check out the right tag.
- **Filename says `-dirty`**: working tree has uncommitted changes. Stash or commit first.
- **`gh release create` fails with "release already exists"**: the release exists but maybe without binaries — use `gh release upload <tag> <file>` to add assets, or `gh release edit` to update notes.
- **Build picks up the wrong `versionCode`**: configuration cache. Try `./gradlew --no-configuration-cache :apps:djapp:androidApp:bundleRelease`.
- **AAB is unsigned**: `keystore.properties` is missing at the repo root. The convention plugin silently falls back to the debug signing config in that case. Play Console will reject the upload.
- **A commit landed after the tag and you want the tag to include it**: see "add one more commit to a published release" above.
