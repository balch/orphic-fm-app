# Orphic DJ — Play Console setup walkthrough

A click-through checklist for getting Orphic DJ from "AAB on disk" to "closed-testing
opt-in URL in beta testers' inboxes." Intended to be followed in order; each section
is self-contained so you can pause and resume.

Companion document: [`listing.md`](listing.md) holds the marketing copy.

---

## 0. Before you sit down at the laptop

Have these to hand:

- [ ] Google account that owns the Play Console developer license (`balch61@gmail.com`)
- [ ] $25 one-time developer registration fee (already paid if you've used the console before)
- [ ] Phone with the app installed for screenshots
- [ ] `apps/djapp/play-store/listing.md` open in another window (you'll paste from it)
- [ ] Final app icon: `apps/djapp/play-store/assets/play-icon-512.png`
- [ ] Feature graphic: `apps/djapp/play-store/assets/feature-graphic-1024x500.png`
- [ ] Phone screenshots (≥2; see §3 below for capture instructions)
- [ ] Privacy policy URL: `https://orphic.fm/dj/privacy/` (confirm it's live)
- [ ] Release-signed AAB at `apps/djapp/androidApp/build/outputs/bundle/release/androidApp-release.aab`

---

## 1. Personal-account 14-day rule (READ FIRST)

If your Play Console account is registered as a **personal** developer (most likely
for `balch61@gmail.com`), Google requires:

> **At least 12 testers must opt into your closed testing track and the track must
> remain active for at least 14 continuous days before you can promote the app to
> production / open testing.**

Practical consequences:

1. Closed testing → Production is ≥ 14 days minimum, no shortcuts.
2. You need to recruit 12+ real testers before the clock starts. Friends count.
3. The 14-day timer resets if you have <12 active testers at any point. Add buffer.
4. **Recommendation**: aim for 15–20 invited testers so a few drop-offs don't reset
   the timer.

For an organization-verified Play Console account this rule does not apply, but
verifying as an organization requires a D-U-N-S number, which is more friction
than the 14 days.

If you absolutely need a faster path: **internal testing** has no review delay and
is unlimited (up to 100 testers), but it does NOT count toward the 14-day rule.
Run internal testing in parallel for the first week or two while closed-testing
gathers eligibility hours.

---

## 2. Create the app in Play Console

1. Go to <https://play.google.com/console/>.
2. Click **Create app** (top right of the All apps page).
3. Fill in:
   - **App name**: `Orphic DJ`
   - **Default language**: `English (United States) – en-US`
   - **App or game**: `App`
   - **Free or paid**: `Free`
4. Tick the two declarations:
   - [x] Developer Program Policies
   - [x] US export laws
5. Click **Create app**.

You'll land on the **Dashboard**. The left rail lists every form you have to
complete; "Set up your app" is the section that gates the first release.

---

## 3. Phone screenshots

Play requires **at least 2** phone screenshots. Recommend 4–8 for a real
listing — they show in the carousel directly under the feature graphic.

### Capture from a real device (recommended)

```sh
# Plug in phone with USB debugging on. From repo root:
~/Library/Android/sdk/platform-tools/adb devices         # confirm device
~/Library/Android/sdk/platform-tools/adb shell screencap -p /sdcard/orphic-1.png
~/Library/Android/sdk/platform-tools/adb pull /sdcard/orphic-1.png \
    apps/djapp/play-store/assets/screenshots/01-main.png
~/Library/Android/sdk/platform-tools/adb shell rm /sdcard/orphic-1.png
```

Save them under `apps/djapp/play-store/assets/screenshots/` with predictable
names so the order is obvious in Play Console upload.

### Capture from desktop JVM build (fallback)

If you can't get the device handy, run the JVM app and screenshot the window
manually with `Cmd+Shift+4`. **Caveat**: Play Console expects 16:9 or 9:16
aspect ratio. Desktop window aspect ratio probably won't match — you'll likely
need to resize/crop. Phone capture is genuinely easier.

### Constraints (Play hard-blocks if violated)

- **Format**: JPEG or 24-bit PNG (no alpha)
- **Size**: 320 px – 3840 px on each side
- **Aspect ratio**: between 16:9 and 9:16
- **Min**: 2 images. **Max**: 8 images.

### Suggested shot list (4–6 frames, in order)

1. **Hero**: the four-knob main screen with vibe playing. Show the artwork.
2. **Active mix**: knobs visibly turned, vibe name visible.
3. **Vibe picker**: gallery of available vibes.
4. **Timer panel**: the sleep timer in action.
5. **Tablet/landscape** (optional but boosts conversion).

Avoid screenshots that show debug UI, crash dialogs, or internal subsystem
names (Pulsar, etc.).

---

## 4. Main store listing (left rail → Grow → Store presence → Main store listing)

Paste from `listing.md`:

| Field | Source | Notes |
|---|---|---|
| App name | "Orphic DJ" | already set in §2 |
| Short description | `listing.md` § Short description | ≤ 80 chars |
| Full description | `listing.md` § Full description | ≤ 4000 chars |
| App icon | `assets/play-icon-512.png` | 512×512 |
| Feature graphic | `assets/feature-graphic-1024x500.png` | 1024×500 |
| Phone screenshots | `assets/screenshots/*.png` | min 2 |
| 7-inch tablet screenshots | _(skip for v1)_ | optional |
| 10-inch tablet screenshots | _(skip for v1)_ | optional |
| Video | _(skip)_ | optional |

Click **Save** at the bottom. You can come back and edit anytime; Play
re-reviews the listing on each save but doesn't delay releases.

---

## 5. Store settings (left rail → Store presence → Store settings)

| Field | Value |
|---|---|
| App category | `Music & Audio` |
| Tags | pick 2–5 from the dropdown: `Music`, `Audio Player`, `Music & Audio` |
| Store listing contact details | email: `orphic.fm.apps@gmail.com`; website: `https://orphic.fm` |
| External marketing | _(opt-in; safe to leave off for v1)_ |

---

## 6. App content forms (left rail → Policy → App content)

This is the section that takes the longest. Eight or so questionnaires. Don't
worry — for an offline music app most answers are short.

### 6.1 Privacy policy

- **URL**: `https://orphic.fm/dj/privacy/`
- This must be a stable URL, return HTTP 200, and not require a login.

### 6.2 App access

- **All functionality is available without restrictions**: yes
- (Skip the credentials section.)

### 6.3 Ads

- **Does your app contain ads?**: No

### 6.4 Content ratings (IARC questionnaire)

Click **Start questionnaire**. For a music-generation app:

- Email: `orphic.fm.apps@gmail.com`
- Category: `Music` *(generative-music tool — cleanest fit; the older docs
  suggested `Reference, news, or educational` but `Music` matches Play's
  primary category and avoids a re-questionnaire down the road)*
- Violence / sexual content / language / drugs / gambling: **No** to all
- User-generated content: **No** *(users don't share content from the app)*
- Personal info collection: **No** *(no telemetry, no accounts)*
- Location collection: **No**
- Digital purchases / IAPs: **No**

Submit. You'll get a rating instantly (likely "Everyone" / PEGI 3 / ESRB E).

### 6.5 Target audience and content

- **Target age groups**: pick `13+` and up. Do **not** include children's age
  groups (5 and under, 6–8, 9–12) unless you specifically want to opt into the
  Designed for Families program — that requires extra COPPA disclosures and
  changes the data safety bar significantly.
- **Appeals to children**: No
- **Ads SDK**: N/A (no ads)

### 6.6 News app declaration

- **Is this a news app?**: No

### 6.7 COVID-19 contact tracing & status apps

- **Does this app contain COVID-19 contact tracing or status features?**: No

### 6.8 Data safety

This is the form that takes the most reading. Play wants to know what data
your app collects, shares, or processes.

For Orphic DJ, the answers are mostly "no":

- **Does your app collect or share any of the required user data types?**: No
  - Confirms: no personal info, no audio recording, no location, no contacts,
    no device identifiers, no usage analytics.
- **Is all of the user data collected by your app encrypted in transit?**: N/A
  *(no data leaves the device)*
- **Do you provide a way for users to request that their data be deleted?**:
  N/A *(no data is collected)*

If at any point you add Firebase Crashlytics, RevenueCat, or anything that
sends data off-device, **come back and update this form before that release
ships**. Play audits this form post-release and a mismatch is a takedown
risk.

### 6.9 Advertising ID

- **Does your app use advertising ID?**: No

### 6.10 Government apps

- **Is this a government app?**: No

### 6.11 Financial features

- **Does this app have financial features?**: No

### 6.12 Health

- **Does this app have health features?**: No

---

## 7. Sensitive permission declarations (left rail → Policy → App content → Sensitive app permissions)

Currently the AAB declares:

- `MODIFY_AUDIO_SETTINGS` — Normal permission, no declaration needed.
- `FOREGROUND_SERVICE` — Normal permission, no declaration needed.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — Normal permission, **does** require a
  use-case justification on the **Foreground services policy** form.
- `POST_NOTIFICATIONS` — Runtime permission, no declaration needed.

### Foreground services declaration

When prompted:

- **Does your app use foreground services?**: Yes
- **Foreground service type**: `mediaPlayback`
- **Use case**: paste:

> Orphic DJ continuously generates and plays original music in real time. The
> media-playback foreground service keeps audio rendering active when the
> screen is off and surfaces system-level transport controls (notification,
> lock screen) so the user can pause without unlocking the device.

- **Why a foreground service is necessary**:

> Generative audio rendering must run in a process that the OS will not
> kill mid-playback. A bound or background service does not meet this
> requirement on Android 14+.

---

## 8. Set up Play App Signing (automatic on first upload)

When you upload your first AAB, Play will prompt you to enroll in
**Play App Signing** (recommended choice you already made).

What to choose:

- **Use Play App Signing** (default).
- **Provide your own upload key** (sub-option). Click **Export and upload an
  encrypted upload key** — Play generates a private key for the App Signing
  key, and your local keystore becomes the **upload key** only. This means:
  - You sign uploads with your local keystore.
  - Google re-signs with the App Signing key before distributing to users.
  - If you ever lose your local keystore, you can request an upload-key reset
    (Google emails the reset link to your account) without losing the ability
    to ship updates.

You don't need to upload a separate "encrypted private key" file unless you
want to import an existing app-signing key — for a brand-new app, accept
the default and let Play generate the App Signing key.

---

## 9. Closed testing track (left rail → Test → Closed testing)

1. Click **Create track**. Give it an internal name like `closed-beta-1`.
2. **Testers**:
   - Click **Manage testers**.
   - Choose either:
     - **Email list**: paste up to 100 addresses, one per line.
     - **Google Groups**: better long-term. Create a group at
       `groups.google.com` (e.g. `orphic-dj-beta@googlegroups.com`) and paste
       its address. New testers join by emailing the group; you don't have
       to redeploy.
   - **Recommend**: Google Group. Less churn, easier to track.
3. **Feedback URL or email** (optional but useful):
   `orphic.fm.apps@gmail.com` or a Google Form link.
4. **Track-level countries**: leave at "All countries" unless you have a
   regional reason to restrict.
5. Click **Save**.

### Tester opt-in URL

After creating the track and adding testers, Play surfaces an opt-in URL
of the form `https://play.google.com/apps/testing/org.balch.djapp`. Email
this to testers. They click it, accept, and the app appears in their Play
Store within ~15 minutes.

---

## 10. First release on closed testing

1. Left rail → **Test → Closed testing → `closed-beta-1`**.
2. **Create new release**.
3. **App bundles**: drag-drop
   `apps/djapp/androidApp/build/outputs/bundle/release/androidApp-release.aab`.
4. Play parses it. Confirm:
   - Package: `org.balch.djapp` ✓
   - Version code: (current `git rev-list --count HEAD` value)
   - Version name: `1.0.0` ← assumes you tag HEAD as `v1.0.0` before building
   - Permissions list matches §7
5. **Release name** (internal): `1.0.0 – first closed beta`
6. **Release notes** (per locale; en-US):

   ```
   <PASTE FROM listing.md § Release notes WHEN FINALIZED>
   ```

   Keep it short. The first 80 chars are what shows up under the version
   number in the user's Play Store updates list.

7. Click **Save**, then **Review release**, then **Start rollout to closed
   testing**.

### What happens next

- Play runs a **pre-launch report** automatically (~30 min – 2 h). It boots
  the app on a few real devices, walks the UI a bit, and flags crashes.
  Worth checking before announcing the beta.
- **First-time review**: closed-testing first releases trigger a full
  Play review (typically 1–3 days, occasionally 7). Subsequent updates
  on the same track usually publish in hours.
- Once the review passes, the track shows **Active**. Testers who clicked
  the opt-in URL will get the app on next Play Store refresh.

---

## 11. Post-publish checklist

- [ ] Confirm the opt-in URL works in incognito / a non-tester account
      (it should show "you are not a tester yet" — that's correct)
- [ ] Send the opt-in URL + a short note to your beta tester list
- [ ] Set a calendar reminder for **+14 days** to check the testing-time
      counter (visible in the Closed testing track summary)
- [ ] Skim the **Crashes & ANRs** dashboard daily for the first week
- [ ] If the pre-launch report flagged anything, file a follow-up issue

---

## 12. Promoting to production (later — not in this session)

After 14+ days of active closed testing with 12+ testers:

1. Left rail → **Production → Create new release**.
2. Use the **same AAB** you uploaded to closed testing (Play has a
   "promote release" button on the closed track that does this in one click).
3. Production releases require a fresh review (1–3 days, sometimes longer
   for first production).
4. **Staged rollout**: start at 5–10%, monitor crashes for 24–48 hours,
   ramp to 100%.

---

## Appendix A — Versioning workflow (when you tag v1.0.0)

The convention plugin derives `versionName` from `git describe --tags --always
--dirty`. To get a clean `1.0.0`:

```sh
# Make sure HEAD is exactly the commit you want to ship.
git tag -a v1.0.0 -m "v1.0.0 — first closed beta"
git push origin v1.0.0     # only after merging to main

# Rebuild the AAB. versionName will now be "v1.0.0".
./gradlew :apps:djapp:androidApp:bundleRelease
```

`versionCode` is `git rev-list --count HEAD` and increments automatically with
each commit. No manual bumping required, no risk of forgetting it.

If `versionName` shows as `v1.0.0-1-gXXXXXXX` after tagging, it means you
have at least one commit past the tag. Either re-tag the new HEAD or accept
the suffix — Play accepts it, just looks slightly less polished in admin
views.

---

## Appendix B — Common gotchas

| Symptom | Cause | Fix |
|---|---|---|
| "App bundle is not signed correctly" | First upload but you signed with a debug key | Confirm `keystore.properties` exists at repo root before building. The release build silently falls back to debug signing if it's missing. |
| "Version code XXX has already been used" | You re-uploaded the same AAB | Make a commit (any commit) to bump `git rev-list --count HEAD`, rebuild. |
| "Your app contains ads" review failure | Some SDK we pulled in declares an ad component | Run `./gradlew :apps:djapp:androidApp:bundleRelease --scan`, search the dependency tree for `com.google.android.gms:play-services-ads*`. None should be present. |
| Pre-launch report flags missing English version of release notes | You only filled in one locale | Add at least an `en-US` entry. |
| "Listing requires phone screenshots" | Track creation but no screenshots uploaded | See §3. |
| Closed testing time-counter not advancing | Testers aren't actually opening the app | The 14-day window only counts days when ≥12 testers have the app installed and have opened it at least once. Send a reminder. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` declaration form keeps coming back | You changed the foreground service type and Play asks again | Re-fill it; this is normal on every change. |

---

## Appendix C — Files in this directory

- `listing.md` — marketing copy (short / full description, release notes, tags)
- `play-console-setup.md` — this document
- `assets/play-icon-512.png` — Play Store app icon
- `assets/feature-graphic-1024x500.png` — Play Store feature graphic
- `assets/screenshots/*.png` — phone screenshots (TODO)
