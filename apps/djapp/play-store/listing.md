# Orphic DJ — Play Store listing

**The marketing copy is not in this file.** It lives in
[`../androidApp/src/main/play/`](../androidApp/src/main/play/) and is uploaded from there by
Gradle Play Publisher. Edit the `.txt` files; this document holds only the fields that have no
file in that tree.

This file used to carry a second copy of the descriptions and drifted from what actually shipped —
it claimed 9 vibes against the tree's 11 and was missing an entire paragraph. A pointer can't drift.

## Where each field lives

Published from the tree — edit the file, never the Console:

| Field | File | Limit |
|---|---|---|
| Title | `listings/en-US/title.txt` | 30 |
| Short description | `listings/en-US/short-description.txt` | 80 |
| Full description | `listings/en-US/full-description.txt` | 4,000 |
| What's New | `release-notes/en-US/default.txt` | 500 |
| Icon, feature graphic, screenshots | `listings/en-US/graphics/` | see below |
| Contact email / website | `contact-email.txt`, `contact-website.txt` | — |
| Default language | `default-language.txt` | — |

Two different tasks push them, which is why "publish a build" and "change the listing" are separate
acts:

```bash
./gradlew --no-configuration-cache :apps:djapp:androidApp:publishOgReleaseBundle -PplayTrack=alpha
```

That uploads the AAB **and** `release-notes/` for that release only. Title, descriptions and
graphics move separately via `publishListing` / `publishOgApps` — so "leave the listing alone"
during a release is the default, not something you have to arrange. See the `release-djapp` skill.

## Console-only fields

No file backs these. They are set once in the Play Console and are the reason this document still
exists. Full walkthrough in [`play-console-setup.md`](play-console-setup.md).

| Field | Value |
|---|---|
| Primary category | `Music & Audio` |
| Tags (max 5, fixed dropdown) | `Music`, `Music & Audio` |
| Privacy policy | `https://orphic.fm/dj/privacy/` (Policy → App content) |
| Content rating, Data safety, Target audience | see `play-console-setup.md` §6 |

**Why the title is `Orphic DJ: Play It Live`** and not the launcher label: title is Play's
highest-weight ranking field, so it leads with the live/interactive hook — the app's real
differentiator — and stays out of the contested "generative music" keyword. The on-device launcher
label remains plain `Orphic DJ` (`androidApp/src/main/res/values/strings.xml`).

## Writing rules

Constraints Play hard-blocks or fails review on:

- Short description ≤ 80 chars. No ALL-CAPS marketing words, no emoji, no other app's trademark.
- No comparisons to named competitors ("like Spotify") — automatic review failure.
- No unsubstantiable claims ("the best", "AI-powered" unless the data-safety form defends it).
- No promo codes, sale language, or "act now" — Play treats those as spam.

Conventions this listing keeps, which are ours rather than Play's:

- Lead in the first 1–2 sentences. Play truncates after a few lines on small phones.
- Bullets ≤ 80 chars, grouped by what the user does, not by subsystem.
- Never name internal subsystems (`Pulsar`, `C++ DSP graph`) or third-party DSP modules — the
  latter is a trademark problem as well as a clarity one.
- No roadmap promises with dates.
- Keep the counted bullets (`• N original vibes`) true. The vibe count is the number of literal
  `CatalogEntry(VibeStatus.LIVE` entries in `VibeCatalog.kt` — grepping for the bare enum name
  overcounts, because doc comments mention it too.

## Assets

Sources render to PNG under `assets/`, and the shipped copies live in the tree's `graphics/`:

- `assets/framed/hero-card.html`, `feature-graphic.html`, `tv-banner.html` — headline art
- `assets/framed/frame.html`, `frame-large.html`, `frame-widgets.html` — the screenshot captions
- `assets/screenshots/` — raw captures; `assets/framed/*.png` — rendered frames

Captions are baked into the images, so fixing copy in a caption means re-rendering the PNG and
re-running `publishListing`, not just editing text.

Apple's equivalent copy is in [`../app-store/`](../app-store/), which mirrors this layout.
