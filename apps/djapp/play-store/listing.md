# Orphic DJ — Play Store listing draft

This is the source-of-truth document for everything that goes into the Play Console
"Main store listing" form. Fill in each section, then paste from here into the
Console UI. Keeping it under version control means you can iterate, get review
comments, and recover the text if you need to re-submit.

---

## Decided already

| Field | Value |
|-------|-------|
| App name | `Orphic DJ: Generative Music` *(Play store title, 27 chars — keyword-bearing; on-device launcher label stays `Orphic DJ`)* |
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

**Generative ambient music for focus, sleep & parties. Four knobs, endless mixes.**

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
Orphic DJ is an interactive album — original generative music that composes
itself, endlessly, every time you press play.

There's no streaming, no library, no playlists. The music is created live on
your device by generative algorithms, so no two listens are ever the same.
Shape the mood with four simple knobs and let it run for minutes or for hours.

WHAT YOU CONTROL
Four knobs capture the essence of any track:
• Energy — from calm ambient drift to a driving beat
• Complexity — sparse and minimal, or rich and layered
• Mood — bright and uplifting, or dark and moody
• Space — intimate and dry, or vast and reverberant

MADE FOR EVERY MOMENT
• Focus & study — steady, distraction-free instrumental music for deep work
• Relax & sleep — wind down with a built-in timer that fades you off to sleep
• House parties — open the DJ module, layer effects, and drop changes on the fly
• Long drives & commutes — music that never repeats and never needs a signal

FEATURES
• Generative beats and ambient soundscapes — original compositions that never loop
• Four-knob interactive control that reflects your current headspace
• A growing collection of vibes, from upbeat grooves to deep ambient drones
• DJ module — tweak, layer, and mix in real time
• A home-screen widget — see the current vibe and control playback at a glance
• Sleep / focus timer for bedtime, study blocks, or a long drive
• Always offline — no account, no network, no ads, no telemetry

Your music never leaves your device. No sign-up, no tracking, no subscription.

Enjoy the vibes.
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
- [x] Screenshots — 6 captioned frames (1080×1920) in `assets/framed/`: hero, vibes, mix, DJ, timer, widget. Raw captures kept in `assets/screenshots/`.
