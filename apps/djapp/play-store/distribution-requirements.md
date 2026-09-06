# Orphic DJ — Play Store distribution-requirements compliance

Compliance record for Google Play's **country/region-specific distribution
requirements** (Play Console Help, [answer 6223646](https://support.google.com/googleplay/android-developer/answer/6223646)).
This page is **not** the general launch checklist — it covers per-region legal
and account obligations (Brazil, EU, Japan, Korea, Vietnam, Israel). Most items
are conditional and trigger only on **paid apps / in-app purchases**, **games**,
**gambling / loot boxes**, or **location collection**.

Companion docs: [`play-console-setup.md`](play-console-setup.md) (click-through
setup), [`listing.md`](listing.md) (Console-only listing fields; the marketing copy itself lives in
[`../androidApp/src/main/play/`](../androidApp/src/main/play/)).

Last reviewed: **2026-06-14** (Orphic DJ v1.4.2, `org.balch.djapp`).

---

## The four determinants

Almost every regional obligation hangs on these. Verified against the repo:

| Determinant | Orphic DJ | Evidence |
|---|---|---|
| Paid app or in-app purchases? | **No** — free, no billing | No `com.android.billingclient`; only the in-app *update* API is present (`androidApp/build.gradle.kts`). App declared **Free** in `play-console-setup.md` §2. |
| App or game? | **App** | Declared `App` in `play-console-setup.md` §2. |
| Collects location? | **No** | Manifest requests no location permission (`androidApp/src/main/AndroidManifest.xml`). |
| Collects / shares user data? | **No** | Data safety = "no collection"; privacy policy confirms offline-only, no telemetry/accounts/ads. |

Permissions in the shipped manifest: `MODIFY_AUDIO_SETTINGS`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`.
Target audience: **13+**, "appeals to children: No".

---

## Region-by-region verdict

| Region | Requirement | Applies? | Rationale |
|---|---|---|---|
| **Brazil** | Merchant verification (all *sellers*) | ❌ No | Free, no IAP → not a seller. |
| **Brazil** | Digital ECA / Play Age Signals API; loot-box rules | ❌ No | Does not target children; rated 13+ with no child appeal. No loot boxes. |
| **EU** | No unjustified geo-blocking by nationality | ✅ **Live, compliant** | Distribution set to "All countries"; do not arbitrarily restrict EU member states. |
| **Japan** | Business-operator name / phone / address disclosure | ❌ No | Required only for paid apps / IAP. |
| **Japan** | Payment Services Act registration | ❌ No | No payment features. |
| **Korea** | GRAC game rating certificate | ❌ No | Not a game. |
| **Korea** | MOGEF age verification (harmful-to-juveniles) | ❌ No | Everyone-rated, no harmful content. |
| **Korea** | KCC location-services license | ❌ No | No location collection. |
| **Korea** | Developer account business/e-commerce numbers | ❌ No | Required only for paid apps / IAP. |
| **Vietnam** | Online-game license (Decree 147/2024) | ❌ No | Not a game. |
| **Israel** | KYC identity verification | ⚠️ Conditional | Only when Google **requests** it. If asked, complete within their timeline (gov photo IDs / business docs + a ~10-min live verification call). |

**Verdict for page 6223646: nothing blocks publishing.** The only live item is
keeping EU availability open (already done). Israel KYC is reactive — respond if
and when Google asks.

---

## Watch list — what would flip an ❌ to required

Re-open this doc before shipping any release that adds:

- **In-app purchases / paid tier** → triggers Brazil merchant verification,
  Japan operator disclosure, Korea developer business/e-commerce numbers.
- **Location collection** → triggers Korea KCC license + consent, and changes
  Data safety + privacy policy.
- **Game mechanics or gambling/loot-box-style randomization** → triggers Korea
  GRAC, Vietnam game licensing, Brazil loot-box rules.
- **Targeting children (under-13 age groups / Designed for Families)** → triggers
  Brazil Digital ECA + Play Age Signals API and COPPA-grade data-safety bar.

The existing Data safety / IAP / content-rating answers in `play-console-setup.md`
§6 are the source of truth — keep them in sync with any such change, since Play
audits post-release and a mismatch is a takedown risk.

---

## Adjacent requirements NOT on this page that still gate publishing

These are separate from answer 6223646 but matter for an actual go-live —
confirm each in the Console before promoting to production:

| Item | Status | Notes |
|---|---|---|
| **EU DSA trader-status declaration** | ⚠️ Confirm in Console | Since Feb 2025, every developer distributing to EU users must declare trader / non-trader status and verify developer identity. Apps without it are removed from EU. Set under Play Console → account-level settings. |
| **Privacy policy URL live** | ✅ Verified | `https://orphic.fm/dj/privacy/` returns 200, public, accurately states no data collection (checked 2026-06-14). |
| **Target API level** | ✅ Verified | `targetSdk = 37` (latest), `minSdk = 26` — within Google's "target within one year of latest release" rule. |
| **Content rating (IARC)** | ⚠️ Confirm in Console | Music category, "No" to all sensitive-content questions → expect Everyone / PEGI 3. See `play-console-setup.md` §6.4. |
| **Foreground-service declaration** | ⚠️ Confirm in Console | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` requires a use-case justification on the Foreground services form. Copy is in `play-console-setup.md` §7. |

---

## Go / no-go summary

- **Page 6223646 (this doc's subject):** ✅ no blockers — free, non-game, offline,
  no-IAP, no-location app falls outside the regional obligations.
- **Before production rollout, click-confirm in Console:** EU DSA trader status,
  content rating submitted, foreground-service justification filed.
- **Reactive:** respond to an Israel KYC request if Google sends one.
