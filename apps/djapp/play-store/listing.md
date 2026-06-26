# Orphic DJ — Play Store listing draft

This is the source-of-truth document for everything that goes into the Play Console
"Main store listing" form. Fill in each section, then paste from here into the
Console UI. Keeping it under version control means you can iterate, get review
comments, and recover the text if you need to re-submit.

---

## Decided already

| Field | Value |
|-------|-------|
| App name | `Orphic DJ: Play It Live` *(Play store title, 23 chars. Leads with the live/interactive hook, the app's real differentiator, and exits the contested "generative music" keyword. Title is Play's highest-weight ranking field. On-device launcher label stays `Orphic DJ`.)* |
| Default language | English (US) |
| Category — primary | Music & Audio |
| Category — secondary | _(optional, see "Tags" below)_ |
| Contact email | `orphic.fm.apps@gmail.com` |
| Website | `https://orphic.fm` |
| Privacy policy | `https://orphic.fm/dj/privacy/` |

---

## Short description — 80 char hard limit

This is the line that appears under the app name in search results and on the
detail page. It's the most read piece of copy in the listing — your one-line
pitch.

**Easy. Playful. Infinite. Live electronic music for focus, sleep & parties.**

### Constraints

- ≤ 80 characters (Play Console hard-blocks longer text).
- No ALL-CAPS marketing words ("FREE", "BEST", etc.).
- No trademark of other apps or services.
- No emoji.
- No "Spotify-like" or comparisons to specific competitors.

---

## Full description — 4000 char hard limit

Shown on the listing detail page. Most users scan; few read every word. Format
matters more than total length.

```
Orphic DJ is live electronic music you perform with four knobs. No decks, no skills, no two performances alike. Twist Energy, Complexity, Mood, and Space and the music reshapes around you in real time. Open the DJ module to drop effects and ride the mix. Underneath, it composes itself endlessly, so every performance is a piece that never repeats.

No streaming. No library. No playlists. Just four knobs and music you play.

WHAT YOU CONTROL
Four knobs shape the entire mix:
• Energy: from calm ambient drift to a driving beat
• Complexity: from sparse and minimal to rich and layered
• Mood: from bright and uplifting to dark and moody
• Space: from intimate and dry to vast and reverberant

WHAT'S INSIDE
• 9 original vibes
• 11 live visualizations
• 8 generative tracks
• 5 mixer controls
• 4 ambiance knobs
• 2 turntables
• 1 home-screen widget
∞ infinite music

MADE FOR EVERY MOMENT
• Focus & study: steady, distraction-free instrumental music for deep work
• Relax & sleep: a built-in timer fades you off to sleep
• House parties: open the DJ module, layer effects, drop changes live
• Drives & commutes: music that never repeats and never needs a signal

Completely free. No account, no network, no ads, no in-app purchases, no tracking. Your music never leaves your device.

Tune into orphic.fm for more from Orpheus.
```

### Recommended structure (~600–1200 chars total — short is fine)

1. **Opening (1–2 sentences).** What the app *is* and what makes it different.
   Don't bury the lead — Play truncates after a few lines on smaller phones.
2. **Feature bullets (4–7).** Each line ≤ 80 chars, action verb at the front.
   Group features by what the user does, not by internal subsystem.
3. **For whom / when** (1 short paragraph). Sets the use-case context.
4. **Optional — what's NOT in the app.** Especially powerful when honesty is a
   feature. ("No accounts. No ads. No streaming. No internet.")
5. **Optional — a 1-line dev story or "why".** Caveat: keep it brief — anything
   that reads like a manifesto pushes users away.

### Style rules Play actually checks

- **No claims you can't substantiate.** Skip "the best", "the fastest", "AI-
  powered" (unless you can defend it on the data-safety form).
- **No comparisons to specific competitors by name.** "Like Spotify" / "better
  than Apple Music" → review fails.
- **No "FREE" in headers when the app has IAPs or ads** — we have neither so
  this is safe to mention, but Play prefers you let the price tag speak.
- **No promo codes, sale language, or "act now"** — Play's policy treats those
  as spam.

### Things I would NOT put in the description

- Internal subsystem names ("Pulsar engine", "C++ DSP graph") — meaningless to
  users; save them for marketing pages later if you write any.
- Third-party DSP module names. Trademark issue + irrelevant to the listener.
- Long technical pedigree. The Play Store listing is a sales surface, not a
  GitHub README.
- Roadmap promises with specific timelines. "More creation tools will follow"
  is fine; "Q3 2026 update" boxes you in.

---

## Tags — up to 5

Play uses these for categorization and surfacing in related-apps. The list
Play offers is fixed; you pick from a dropdown.

**Selected:**

- `Music`
- `Music & Audio`

---

## Release notes — what's new in this version

Shown next to the version number. Keep it short — most users glance at the
first 80 chars.

**v1.0.0:**

> v1.0.0 — Orphic DJ — throwing against the wall to see what sticks.

---

## Checklist

- [x] Short description — finalized above
- [x] Full description — finalized above
- [x] Release notes for v1.0.0 — finalized above
- [x] Tags — `Music`, `Music & Audio`
- [x] Primary category — `Music & Audio`
- [x] GitHub repo link — not included
- [x] Screenshots — 7 captioned frames (1080×1920) in `assets/framed/`: by-the-numbers hero (`hero-card.html`), Four knobs, vibes, mix, DJ, timer, widget. Wired into `play/.../phone-screenshots/01–07.png`. Raw captures in `assets/screenshots/`.
