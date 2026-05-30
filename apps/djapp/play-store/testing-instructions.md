# Orphic DJ — Instructions for Testers

Thanks for testing **Orphic DJ**. This doc walks you through every feature, what to do,
and what "working" looks like. Please test on a **phone** (portrait and landscape both),
and report anything that doesn't match the "✅ Confirm" notes below.

> **Heads-up boxes** describe behavior that *looks* surprising but is **intentional** —
> please don't file those as bugs. If something differs from a Heads-up note, that's worth reporting.

---

## 0. Before you start (read this first)

1. Orphic DJ is an **interactive album** that **generates original music** on your phone.
2. **The app opens SILENT.** Nothing plays until you press **Play**. This is by design — don't
   assume it's broken if you hear nothing on launch.
3. **On first launch, Android 13+ may ask to allow notifications — tap *Allow*.** The now-playing
   notification, lock-screen controls, and the home-screen widget's timer all depend on it. If you
   miss the prompt, enable it manually: **Settings → Apps → Orphic DJ → Notifications**.
4. **No account, no internet needed.** The app should fully work in **airplane mode** — there are no
   logins, no streaming, no network prompts.
5. **Listen for audio glitches.** All music is generated live on your device. On older / lower-end
   phones, right after pressing Play, or after a long time in the background, listen for crackle,
   dropouts, or stutter and report what device you heard it on.

---

## Quick smoke test (run this first — ~60 seconds)

Before the detailed sections, do this fast end-to-end check to confirm the build installs and the core
loop works. If any step here fails, **stop and report it first** — the rest of the testing depends on it.

1. **Install & launch** the app. It opens to the main screen and is **silent** (expected).
2. **Play** — tap the purple **Play** button (center of the bottom bar in portrait; end of the side rail
   in landscape). → Music starts and the button flips to **Pause**.
3. **Change Vibe** — on the always-visible sequencer panel, tap the **VIBE** dropdown (top-left of the
   panel), pick a different vibe. → The style of the music changes within a moment.
4. **Tweak a knob** (optional sanity check) — drag the **ENERGY** knob up/down. → You can hear the music
   get busier/sparser.
5. **Stop** — tap the same transport button (now showing **Pause**). → Music goes **silent** and the
   button flips back to **Play**. (To fully clear the session, swipe down the notification shade and tap
   **Stop** there — the notification should disappear.)

**✅ Smoke test passes if:** app launches without crashing, Play produces audible generative music,
switching Vibe audibly changes the music, a knob changes the sound, and Pause silences it cleanly.

---

## 1. What the app is (overview)

Orphic DJ is an **interactive album**. Instead of streaming songs from a library, it **generates
original music live on your phone** — it never repeats, and it plays for as long as you listen.
You don't pick tracks; you **steer the feel** with four big knobs and a set of hands-on tools.

**Features**
- Original Tracks combining song structure with semi-random math
  - dial in Energy/Complexity/Mood/Space to influence sound
- DJ Mixer with 2 turntables
  - push a deck's level fader up to bring it into the mix
  - press and drag a platter to scratch
  - fling a platter for a spin effect
  - with the level past ~50%, hold to pick a drop effect from the bottom strip
- Track Mixer
  - set levels for Drums / Bass / Keys / FX
  - add Gain for grit on bigger speakers
- Dual-Rotor Horn
  - adds a chorus / tremolo swirl
  - very aggressive at high levels
- Timer
  - set a timer to stop the music
  - media session ends when the music stops
- Media Session
  - standard Android media session
  - keeps playing when the app is closed
  - notification in the status bar / shade
  - lock-screen transport controls (Prev / Play / Next)
  - album art
- App Widget
  - 4×1 default size
  - transport controls
  - album art

---

## 2. How to report issues

For each report, please include:
- **Device model + Android version**
- **Tab/feature** and **portrait or landscape**
- **What you did**, **what you expected**, **what happened**
- For audio issues: was it on **Play**, after **backgrounding**, on a **specific Vibe**, or on **older hardware**?
- A **screenshot or screen recording** if you can.

Thanks for helping shape the album. **Enjoy the vibes.**

---

## 3. Detailed testing instructions

### 1. Screen layout
- A **generative sequencer panel** is always on screen — across the **top** in portrait, on the
  **left** in landscape. It holds the four main knobs and the style ("Vibe") picker.
- The rest of the screen shows whichever **tab** you've selected.
- A header strip shows the title **Orphic DJ** and a **"Viz:"** dropdown (for the background visuals).
- A bottom bar (portrait) / side rail (landscape) has **4 tabs** plus a **Play/Pause** button:
  - 🅰 **DJ** (album icon) — the turntables
  - 🎚 **Mix** (sliders icon) — reverb + the mixing board
  - 🔊 **Horn** (surround-sound icon) — the swirl / chorus effect
  - ⏱ **Timer** (clock icon) — the sleep timer

**Try this:** Tap **Play** (the purple play button — center of the bottom bar in portrait, end of the
side rail in landscape). Music starts. Tap each tab and confirm only the lower/right area changes —
the four-knob panel stays put. Rotate the phone and confirm the layout re-flows without stopping audio.

**✅ Confirm**
- [ ] App starts silent; tapping Play starts music and the button flips to **Pause**.
- [ ] Pausing then resuming is **instant** (no restart) — it just mutes/unmutes the live music.
- [ ] The selected tab highlights **cyan**; the Play/Pause button is **purple** and is never "selected".
- [ ] The four-knob panel is visible on **every** tab.
- [ ] Rotating to landscape keeps playback going and moves Play/Pause to the end of the side rail.

**Heads-up**
- There is **no song list, skip, or seek bar** in the app — the album is generated in real time, so
  "no playlist" is intentional. (Skip-to-next *does* exist on the lock screen / Bluetooth / widget — see *Plays like a normal music app* below.)
- Play/Pause only mutes/unmutes; it never rewinds.
- The app runs full-screen (status/nav bars hidden); swipe from an edge to bring them back.

---

### 2. The four main knobs (Energy · Complexity · Mood · Space)

These four big knobs reshape the generated music **in real time**. They live in the bottom row of the
always-visible sequencer panel, left→right: **ENERGY, COMPLEXITY, MOOD, SPACE** (a smaller **MIX** knob
sits to their right).

**Try this:** With music playing, **press a knob and drag UP to increase, DOWN to decrease** (it's a
vertical drag, *not* a circular twist). The colored arc fills as you raise it. Sweep each one slowly and
listen:
- **ENERGY** — up = louder, busier, brighter/more aggressive; down = sparser, softer, mellower/darker.
- **COMPLEXITY** — up = more swing, fills, and variation; down = a tight, steady, repetitive groove.
- **MOOD** — up = brighter, richer tone; down = darker, more subdued.
- **SPACE** — up = more reverb/echo and a wider stereo image; down = dry and up-front.

**✅ Confirm**
- [ ] Knobs are labeled ENERGY, COMPLEXITY, MOOD, SPACE (in that order), adjusted by vertical drag.
- [ ] Each knob produces a **distinct** audible change while you drag (no need to stop/restart).
- [ ] Changing the **VIBE** (top-left dropdown of the panel) switches the style; the knobs still shape it.

**Heads-up**
- These four knobs have **no number readout** — that's intentional.
- **SPACE** works together with the small **DEEP** knob: if DEEP is all the way down, raising SPACE may
  add little reverb (it still widens the stereo/decay). Not a bug.
- The small **MIX** knob (right of SPACE) is the sequencer's output level, not a "feel" knob — if the
  beat seems quiet, check that MIX is up.

---

### 3. Plays like a normal music app (notification · lock screen · background)

While playing, Orphic DJ behaves like any music player: it shows a **now-playing notification** and
**lock-screen controls** with the current **Vibe name** (title), **album** (subtitle), **artwork**, and
transport buttons — and it keeps playing in the background via a **foreground service**.

**Try this:**
1. Start playback, then **swipe down the notification shade** — confirm the media notification with
   artwork, vibe name, album, and play/pause/skip buttons.
2. **Lock the phone** — confirm the same controls on the lock screen and that audio keeps playing.
3. Press **Home** / switch apps — confirm music **keeps playing** and the notification stays.
4. Tap the notification body — confirm it **reopens the app**.
5. Use **play/pause and skip-next/previous** from the notification or lock screen — confirm audio
   responds and the vibe changes on skip (it advances through vibes, it does **not** seek within a song).
6. Tap **Stop** in the notification — audio stops and the notification clears (it shouldn't pop back).
7. **Swipe the app away from Recents** while playing — audio should keep going (service is independent).

**Interruption checklist (do all of these while playing):**
- [ ] **Phone call** → music **pauses**, then **auto-resumes** when the call ends.
- [ ] **Another music/video app** starts → Orphic DJ **pauses** (it yields audio, doesn't play on top).
- [ ] **Short interruption** (a Maps voice prompt, a notification chime) → volume briefly **dips (ducks)**
      rather than fully pausing.
- [ ] If you **manually pause during a call**, it **stays paused** after the call ends.

**Background-survival checklist:**
- [ ] Lock the screen and leave it for **several minutes** → playback survives (watch for the device's
      battery optimizer killing it — report the phone model if it stops).
- [ ] Bluetooth headset / car stereo / Android Auto → title, artwork, and play/pause/skip all work.

**✅ Confirm**
- [ ] Notification shows correct **vibe name + album + artwork**, and stays in sync with the in-app state.
- [ ] Backgrounding, locking, and Recents-swipe (while playing) do **not** stop audio.

**Heads-up**
- The notification appears **only after** you press Play (it's not there on launch) and is **silent /
  low-priority** (no sound/badge) by design.
- While playing, the notification **can't be swiped away** (it's "ongoing"); pause first to dismiss it.
- **Known limitation:** unplugging **wired headphones may NOT auto-pause** — sound may continue from the
  phone speaker. Please note it if you see it, but it's a known gap for now.
- A very long interruption (≈10+ min) is treated as a full stop — it won't auto-resume after that.

---

### 4. Sleep / off Timer (Timer tab ⏱)

A built-in timer that, when it reaches zero, **fades the music out over ~15 seconds and stops all
playback** — for falling asleep without the music running all night.

**Try this:**
1. Tap the **Timer** tab. Default time shows **42 minutes**.
2. Set the duration with the **HR** knob (0–4 hours) and the **M** knob (minutes), or **drag the big
   clock digits** up/down (hours and minutes edit separately). Editing only works while stopped.
3. Tap **Play** (triangle) to start the countdown — the colon pulses, status reads **RUNNING**.
4. Tap **Stop** (square) to cancel — **music keeps playing**.
5. Tap **Reset** (circular arrow) to return to your set duration.
6. Let it run to **00:00** and watch the fade-out.

**✅ Confirm**
- [ ] Default is **42 min**; max is **4h 20m** (when HR=4, minutes cap at 20); min is **1 min**.
- [ ] During the **final minute** the display switches to big seconds-only digits and the glow warms
      from cool blue toward amber.
- [ ] At 00:00 the music **fades out (~15s) and stops**; the app **stays open** (it does not close) and
      the clock resets to your duration (status **FINISHED**).
- [ ] **Stop** ends the timer but leaves music playing; **Reset** returns to the set time.
- [ ] While a timer runs, the **Timer tab's icon turns into a live countdown** ("42m", then "1:07",
      then "42s"), and the notification subtitle reads **"Sleep Timer: N min remaining."**
- [ ] The countdown keeps running with the app backgrounded / screen off.

**Heads-up**
- On a phone in **portrait**, the panel's main button is **Play when idle / Stop when running** — there
  is **no pause button** in the compact panel (pause appears in the larger landscape full-screen view).
- The completion fade always stops **all** audio (it's a sleep timer) and the ~15s fade isn't adjustable.
- The notification countdown shows **whole minutes**; the on-screen clock is the second-by-second one.

---

### 5. DJ Turntables (DJ tab 🅰)

Two circular decks (**A** and **B**) capture the live sound and let you **scratch, spin, mix, and trigger
momentary "drop" effects** by touch. This is the hands-on heart of the app. Layout (left→right):
**Deck A platter · fader A · two send knobs (DLY/RVB) · fader B · Deck B platter**; a small **source
selector** sits above each platter.

#### 5a. Change sources
**Try this:** Tap the small pill above a platter and pick a source. The five sources are
**Synth, Drums, Bass, Feedback, 8-Track**. (Deck A defaults to **Synth**, Deck B to **Bass**.)
- [ ] Each deck can hold a **different** source; the pill label updates and the deck's sound changes.

#### 5b. Mix in a deck's level
**Try this:** Drag the tall vertical fader next to a deck **up** to bring it in, **down** to take it out.
- [ ] On a fresh start both faders are at the **bottom** (decks silent); pushing a fader up makes it
      audible, and it **stays where you leave it** (no spring-back).

#### 5c. Fling to spin / scratch
**Try this:** Press on a platter and drag/**flick** up or down, then lift.
- [ ] You hear an audible **scratch & pitch-bend**; up vs down reverses direction; a harder flick spins
      faster. After lifting, the platter **keeps momentum and coasts back** to normal speed. The number
      under the platter shows the spin velocity.

#### 5d. Press-and-hold "drop" effect
**Try this:** With a deck's fader pushed up **past about half-way**, press and **HOLD** on its platter
and keep dragging. After ~⅓ second a strip of **four colored effect cells** slides up. Without lifting,
slide down into a cell and **hold** briefly — a ring fills, then the effect **locks on** (cell glows, shows
its name + an A/B tag). For most effects the strip then becomes a **left/right velocity fader** (push the
audio forward/reverse). **Lift your finger** to release the drop.
- The eight possible drops: **FILTER, BRAKE, STUTTER, FREEZE, OCTAVE, PHASER, ECHO, RING.**
- [ ] The strip stays **hidden** until you hold a sustained drag on a loud-enough deck (a quick flick
      never reveals it).
- [ ] The locked effect is clearly audible (e.g. **BRAKE** stops the record, **ECHO** adds delay).
- [ ] **Lifting** ends the drop. Only **one** drop can be locked at a time across both decks.

**Also worth a try:** Press-and-hold the **center hub (spindle)** of a platter — it toggles a **freeze**
(the ring turns icy blue and pulses). Hold again to release. (This is a different gesture from the drop strip.)

**Heads-up**
- The four offered drops are **randomly drawn from the pool of eight and reshuffle each drag**, so you'll
  see a different set each time — that's intended. **BRAKE** has no left/right fader (it just stops the record).
- There is **no horizontal crossfader** — deck balance is set only with the two vertical faders.
- The four main knobs (Energy/etc.) are **not** on this tab; they're on the panel above/beside it.

---

### 6. Mix tab 🎚 — Reverb + Mixing board

The Mix tab stacks two things: **Reverb** (top) and the **5-channel mixer** (bottom).

#### 6a. Reverb (top section — VERB / "Echo")
Four round knobs: **DAMP, DIFF, TIME, Mix.** Adjust by **vertical drag** (up = more).
**Try this:** The **Mix** knob defaults to **0 (off)** — turn it up first to hear reverb, then sweep
**TIME** (tail length) and **DAMP** (tail brightness).
- [ ] Raising **Mix** adds an audible tail; **TIME** lengthens/shortens it; **DAMP** darkens/brightens it.
- Heads-up: reverb is **silent until you raise Mix** — not a broken effect. (Fine-tune by dragging the
  number text under a knob — that drag is ~10× slower.)

#### 6b. Mixer (bottom section)
A single row of five vertical faders: **PERC** (pink), **BASS** (cyan), **KEYS** (green), **FX** (amber),
and **GAIN** (grey = overdrive/grit). Each fader doubles as a **live level meter**.
**Try this:** Touch a fader's track — the cap **jumps to your finger** instantly — then slide up/down.
- [ ] Dragging up/down changes that group's loudness; bottom = silent.
- [ ] Each instrument fader has a **unity detent** (~¾ up, readout **1.00×**); readout color goes
      yellow (cut) → green (unity) → red (boosted, up to ~+6 dB).
- [ ] While playing, the **LED ladder** inside each fader bounces with that group's level; meters fall
      to zero when playback stops.
- [ ] **GAIN** (grey) adds audible **overdrive/grit** as you raise it (it is *not* a master volume).
- [ ] Switching the **Vibe** auto-updates the four instrument faders to that preset's levels.

**Heads-up**
- Faders are **not** mute/solo buttons. A group may show **dimmed** if its tracks were muted from the
  sequencer panel — that's a read-only reflection, you can't mute from the mixer.
- **PERC** controls three drum tracks together and **FX** controls three effect tracks together (shown as
  their average); **BASS** and **KEYS** are single tracks.

---

### 7. Chorus / swirl effect (Horn tab 🔊)

This is the app's **"chorus" effect** — a **rotating-speaker** effect that makes the mix swirl and pulse
(a blend of pitch wobble + side-to-side volume sweep). There is **no separate chorus control** elsewhere;
the store listing's "chorus" = this **Horn** tab. Controls: **SPEED, RATIO, DEPTH, MIX**, plus a **BRAKE**
button. Two animated rotor displays spin at the top.

**Try this:**
1. Tap the **Horn** tab. The effect starts **bypassed (MIX = 0)** — drag **MIX** up to dial it in.
2. With MIX up, sweep **SPEED** (slow lazy sweep ↔ fast shimmer), **DEPTH** (subtle ↔ intense), and
   **RATIO** (how the two rotors' speeds relate).
3. Tap **BRAKE** (RUN ↔ STOP) and watch/hear the rotors **coast to a stop**, then spin back up.

**✅ Confirm**
- [ ] With MIX at 0 the sound is unchanged; raising MIX blends in the swirl.
- [ ] Speed changes are **not instant** — rotors ramp up (~1s) and coast down (~3s) like a real motor.
- [ ] **BRAKE** flips RUN→STOP, glows red, and the rotors visibly slow to a halt.
- [ ] On **headphones** the sound clearly pans/swirls left↔right.

**Heads-up**
- Knobs are vertical-drag with **no number readout**. If you only move SPEED/DEPTH/RATIO without raising
  **MIX**, you'll hear **nothing** — raise MIX first. The motor-inertia lag is intended, not stuck controls.

---

### 8. "Song ending" bottom sheet (the ENDING control)

**Important expectation reset:** **Nothing pops up when a song ends.** Songs hand off **automatically**.
The bottom sheet here is a **settings editor** for *how* one song transitions into the next.

**Where:** On the sequencer panel, find the small **"ENDING"** label and the **pill button** beneath it
(near the BPM/DEEP knobs). The pill shows the current style ("FADE", "TAPE", "RANDOM", or "PLAYS").

**Try this:**
1. **Tap** the ENDING pill — a bottom sheet rises with an **END STYLE** chip grid and a **HANDOFF** slider.
2. Pick a style: **PLAYS** (loop forever, never auto-advance), **CUT, GAP, FADE, XFADE, TAPE, SCRATCH,
   FILTER,** or **RANDOM** (a different style each time).
3. Drag **HANDOFF** to set the transition length (~100 ms–2 s). It's **dimmed for CUT and RANDOM**.
4. To force a song to end **now**, **press-and-hold** the ENDING pill — it tints **purple** to confirm,
   and the song transitions into the next one shortly after.

**✅ Confirm**
- [ ] Tapping the pill opens the **END STYLE** sheet; the pill label matches the selected style.
- [ ] **PLAYS** makes the song loop forever; any real style re-enables auto-advance.
- [ ] The sheet **auto-dismisses after ~5 seconds** of no interaction (also closes on swipe-down / tap-outside).
- [ ] **Long-press** the pill arms an immediate transition (purple tint), and the song moves to the next one.

**Heads-up**
- This is a **settings** sheet — there is **no** "song over / rate / what next?" prompt. That's expected.
- The exact moment a song auto-ends is partly **random** within a window, so two runs may differ — use
  **long-press** to force an ending for testing.

---

### 9. Home-screen widget

A resizable Android **home-screen widget** that mirrors the current vibe + artwork + sleep-timer and gives
you **play/pause and skip** without opening the app.

**Try this:**
1. **Long-press** an empty home-screen spot → **Widgets** → find **Orphic DJ** → drag it on (defaults to
   ~4×1 cells; long-press to resize — taller switches to a two-row layout).
2. With the app generating sound, confirm the widget shows the **vibe name + album** over the **artwork**.
3. Tap **▶/❚❚** to toggle playback; tap **◀◀ / ▶▶** to change vibe.
4. Change vibe / play-pause **inside the app**, return home, confirm the widget **updated**.
5. Start the **sleep timer** in the app → a **countdown** + a round **✕** appear on the widget; tap **✕**
   to stop the timer.
6. Tap the **title/album text** (not a button) to open the app.

**✅ Confirm**
- [ ] Widget is found under **Orphic DJ** in the picker and renders (artwork + text + transport), not blank.
- [ ] Now-playing and play/pause state **sync both directions** (app ↔ widget).
- [ ] Widget transport works even with the **app backgrounded**; the timer countdown ticks on the widget.

**Heads-up**
- Widget art is **downscaled** (looks a touch lower-res than in-app) and falls back to the app icon if
  artwork isn't ready. Right after a reboot it may briefly show an idle placeholder (a dash) until the app
  initializes. The timer countdown ticks on its own and only fully redraws on start/pause/stop.

---

### 10. Music-reactive visualizations ("Viz:" dropdown)

A selectable **full-screen animated background** that reacts to the music — it pulses on the beat, brightens
with loudness, and shifts color with the instruments. It renders **behind everything** and tints the
frosted-glass panels.

**Try this:**
1. With music playing, tap the **"Viz:"** pill in the header.
2. Scenes: **Off, Aquarium, Black Hole Sun, Bugs, Fireworks, Galaxy, Heartbeat, Lava Lamp, Mt. Hood,
   Orphoscope, Shader Lamp, Swirly** — plus **Random** at the top.
3. Pick a scene and watch the background change; turn the **macro knobs** and watch the visuals follow
   the music. Try **Random** (auto-picks a new scene each time the music changes vibe/section).

**✅ Confirm**
- [ ] Selecting a scene changes the background immediately and the pill updates.
- [ ] With music playing the visuals **pulse on the beat / grow with loudness**; when quiet/stopped they
      go mostly still (e.g. **Heartbeat** stops spawning, **Galaxy** dims).
- [ ] **Orphoscope** shows live oscilloscope traces that wiggle with the waveform; **Off** is a plain dark
      background.
- [ ] **Random** re-rolls on each vibe/section change; tapping a named scene exits Random.
- [ ] The chosen scene persists across **force-close + relaunch** and runs behind all four tabs.

**Heads-up**
- Visuals react to the **audio**, not directly to knob positions — a knob change only moves the visuals as
  much as it changes what you hear. When the music is silent, scenes look nearly empty (not frozen/broken).
- The first launch defaults to **Off** until you pick a scene.
