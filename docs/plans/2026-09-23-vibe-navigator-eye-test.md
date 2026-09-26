# Vibe Navigator Eye and Ear Test

Branch `feature/vibe-navigator`, DJ app. JVM desktop first, then an Android phone.
Eye and ear tests are run by the user.

Items are grouped by how to treat them:
- **Verify:** should already work, so a failure is a bug.
- **Judge:** a call only you can make.
- **Expect:** known behaviour, flagged so it isn't mistaken for a fault.

## Setup

1. Build the native library first: `./gradlew :apps:djapp:desktopApp:buildDesktopNative`. The
   packaged dylib lags one build without this step.
2. Run from the worktree: `./gradlew :apps:djapp:desktopApp:run`.
3. Test at three window sizes, by resizing the one window:
   - **Phone:** narrow and tall, about 400×800.
   - **Landscape:** about 750×600, like your screenshot.
   - **Dock:** 900dp wide or more.
4. For the "swipe me" wiggle, start from a fresh settings file, or one where you have never swiped
   the dome: once you have, it never comes back.

## What changed

- **Previous and next everywhere:**
  - the phone bar's play slot (swipe the dome);
  - the landscape rail (swipe its dome, as in the phone bar);
  - the dock's centre dome (swipe it), and its ⏮ and ⏭ tiles at the bottom bar's ends;
  - ← and → on the keyboard;
  - media keys.

  Every change goes through one owner, with the default transition (TAPE).
- **The play/pause dome:** oversized, tilts with the drag and rolls on commit. The same dome in the
  phone bar, the rail and the dock:
  - **Roll:** it also rolls with every vibe change it didn't make, right for onward, left for back.
  - **Wiggle:** a beat after it first shows it tips left, then right, peeking each neighbour, to
    show it swipes. Only until your first swipe.
  - **Press and focus:** a press or hover lights a round disc on the ring, and keyboard focus rings
    it with a thin circle. The phone bar's and the rail's square ripple is gone.
- **The ring is an oscilloscope:** the played arc draws the music itself, the master mix's waveform,
  oldest at 12 o'clock and newest at the playhead, in the colours of the loudest tracks:
  - steady tones and chords hold their shape;
  - hits swell it, quiet passages still show;
  - noise with no repeating shape never draws its own squiggle.
- **Long names:** a vibe name too long for its slot scrolls, playing or paused, everywhere but TV.
- **The dock's layout:**
  - **Top row:** Pulsar, Info and Ends (PLAYS or the ending style) at the left, the title centred,
    and the Vibe and Viz pickers at the right. Ends sits at the left at every width. Below about
    930dp wide, the pickers drop their "Vibe: "/"Viz: " prefixes and show just their values.
  - **Song band:** a 36dp row of bars directly under the top bar, full width, outside the glass,
    draws the song so far. TV hardware draws it too, static.
  - **Bottom bar:** ⏮ and the previous vibe at the left end, DJ, Mix, the play/pause dome, Horn and
    Timer in the middle, like the phone bar, and the next vibe and ⏭ at the right end.
- **The dock's play/pause:** the dome in its ring in the bottom bar's centre, raised out of the bar
  with the vibe name under it on the toggles' label line, as in the phone bar. It is 123dp on every
  desktop window, sized so a third of the ring rises above the bar, and 96dp on tablets, the unfolded
  Fold and TV (static there). The top bar's dome and TV's Play/Pause plate are gone.
- **Pulse selectors:** VIBE gets its name first; SCALE is the one that shortens on a narrow row.
- **Pause:** the ring holds its last trace and a highlight "zips" along it.
- **Progress:** moves smoothly between the tracker's once-per-loop updates.
- **Landscape:**
  - the header spans the full width;
  - Pulsar sits at the top;
  - the rail has glass;
  - ENV no longer clips.
- **The dock:** has one vibe picker (in the top bar), and long-pressing it fires the Void Anomaly.

## Verify

- **Swipe:**
  - Swiping right on the dome goes to the next vibe; swiping left goes to the previous one.
  - A short drag springs back.
  - A tap pauses and plays.
- **Peek:** dragging right shows "Next name ›" and dragging left shows "‹ Previous name". If ◀ would
  restart, the left drag shows the current name.
- **Roll:** the dome rolls once, and settles, for each change it didn't make:
  - right: → and media Next, the widget's Next, ⏭ and the dock's right tile, a pick from a VIBE
    list, and the song's end moving on;
  - left: ← and media Previous, the widget's Previous, ⏮ and the dock's left tile, and ◀ late in a
    song, which restarts it;
  - its own swipe rolls once, as the finger lets go, and not again when the vibe changes;
  - a vibe the AI applies does not roll it.
- **Wiggle ("swipe me"):**
  - about 1.5 s after the dome first shows, it tips left peeking the previous vibe, then right
    peeking the next, and springs home, about 1.2 s in all;
  - your first committed swipe stops it for good, on every later launch too;
  - a touch or a key on the dome before it starts skips it for this launch; one during it stops it
    and springs it home;
  - on the dock's dome, which holds the keyboard focus at launch, any key cancels it, an arrow
    included;
  - it comes once a launch, whichever dome shows it, and never on TV.
- **Press and focus:** in the phone bar, the rail and the dock, a press lights a round disc on the
  ring, never a square over the name; Tab to the dome and a thin circle rings it, gone after a click
  and back on the next key.
- **Oscilloscope ring:**
  - playing, the arc moves with the music: a held note or pad holds a shape, a kick swells it, a
    breakdown draws small, gentle waves;
  - paused, the last trace holds still and the zip runs along it; resumed, it picks the music up again;
  - noise, or a hit that hasn't repeated yet, swells or shrinks the last shape rather than drawing a
    squiggle; with no repeating shape for a quarter second, it fades toward the plain arc;
  - early in a song (under about 7%) the arc is plain, and the trace grows in by about 18%.
- **◀ and restart:**
  - More than 5 s into a song, ◀ restarts it: the song starts over and progress snaps back.
  - Under 5 s, ◀ goes to the previous vibe.
- **Arrow keys:** ← and → skip vibes when nothing is focused. While typing in the AI prompt, the
  arrows only move the caret, including at either end of the text.
- **VIBE dropdown:** a pick now transitions instead of cutting with a click. This also applies to
  the dock's top-bar Vibe picker.
- **Rapid presses:**
  - ▶▶ fast lands two vibes on.
  - ◀ then ▶ inside one transition comes back to where you started.
  - The audio never ends up silent or stuck quiet.
- **During an ending:** pressing ▶ while an auto-advance is running (after a song ending) makes your
  choice win, with no double transition.
- **Media keys:** macOS Now Playing ⏮ and ⏭ use the same transition. ⏮ past 5 s restarts the song.
- **Minimised:** minimise the window while playing and the music plays on; bring it back and the
  ring traces the music again at once.
- **Landscape (about 750×600):**
  - the header spans the full width;
  - Pulsar's selectors start under the header;
  - the rail has its tabs at the top and the dome at the bottom, with no ◀ ▶ of its own;
  - the vibe name stays inside the rail, scrolling if it's too long;
  - ENV reads fully.
- **Pulse selectors (phone at 360dp, the Fold's cover screen):** a long vibe such as "Techno Wobble"
  shows as much of its name as fits ("Techno W…"), and SCALE shortens first ("Pent…"); ROOT and ENV
  always read in full, and nothing wraps. Wider, every value reads whole.
- **Dock (900dp wide or more):**
  - **Top row:** Pulsar, Info and Ends sit at the left, always in that order, and each docks and
    undocks its panel, lit when docked, as the bottom bar's toggles are; nothing ever covers the
    "Orphic DJ" title;
  - at 900dp (the dock's floor), just under the pickers' own ~930dp threshold, the pickers read
    just "Preview ▾" and "Off ▾", both whole; wider, from about 1000dp on (an iPad in portrait
    included), they read "Vibe: Preview ▾" and "Viz: Off ▾";
  - Ends reads PLAYS, or the ending style, and wears a purple ring while a song ending is under way;
  - long-pressing the top-bar Vibe picker fires the anomaly, and there is no VIBE chip in the docked
    Pulsar panel;
  - **Song band:** directly under the top bar, full width, on a soft shade rather than the glass:
    bars up to a dot, then a dim line to the end of the song. The docked panels start under it, never
    behind it, and it never fades;
  - **Bottom bar:** DJ, Mix, the dome, Horn and Timer sit centred; the dome's vibe name sits on the
    toggles' label line and stays clear of a docked neighbour's plate, scrolling if it's too long;
  - the tiles are one line, "⏮ previous" at the left end and "next ⏭" at the right, their names on
    the toggles' label line and each arrow centred on it; past 5 s the left one shows a restart
    arrow and the current vibe; a focused tile's plate still lines up with the toggles';
  - the dome: a tap plays and pauses, a drag right skips on and a drag left goes back, the dome
    tilting and rolling and its name peeking the neighbour, as in the phone bar;
  - right after the dock opens, Space and Enter play and pause, and ← and → still skip;
  - at launch a thin circle rings the dome while it holds focus, fading after 5 s without a key and
    coming back on the next one; a click hides it until the next key;
  - **Dome size:** on desktop, 123dp in every window, fullscreen or not, on the laptop screen and on
    an external monitor alike, with a third of the ring above the bar's top edge; 96dp and raised on
    tablets, the unfolded Fold and TV. A tap or drag on the raised part works, and a tap just beside
    the raised ring reaches the docked panel under it, whatever the vibe's name;
  - **iPad with a keyboard:** Tab to the dome and its focus circle shows; a touch hides it;
  - **TV (Android TV remote):** the D-pad's select on the dome plays and pauses; D-pad ← → ↑ from
    the dome reach Mix, Horn, the stage and the top row.
- **Pause:**
  - the ring keeps its last trace and a soft highlight sweeps along it, then rests;
  - the song band's bars hold and the highlight sweeps along them too.
- **Progress:**
  - moves smoothly, with no multi-second jumps;
  - stays smooth across a section change and when you arm the ending (long-press ENDING);
  - never runs backwards except on a restart or a new vibe.
- **Dock story:** the band grows bar by bar as the song plays. A breakdown reads as a run of low
  bars and a drop as tall ones. Each bar takes the colour of that stretch's lead track, and the
  last few bars bounce with the beat in the live colour.

## Judge

- **Dome size:** is the oversized dome right against the tabs? In the phone bar the vibe name now
  shares the tab labels' line, and the dome rises about 16dp out of the bar.
- **Dock dome:** 123dp on desktop with a third of it above the bar (96dp on tablets, the Fold and TV),
  between Mix and Horn. Is a third the right rise? It is one constant; 40% would be about 137dp. In a
  1280×720 window it comes within 22dp of the docked Pulsar panel. Holding focus it wears a
  thin circle in the bar's accent, just outside the ring, which fades with the dock's other focus
  marks after 5 s without a key. Does it read as focus, not as part of the trace?
- **SCALE's floor:** on a narrow row SCALE shortens to 84dp, about 3-4 letters ("Pent…"), so VIBE can
  show more. Enough of the scale still reads?
- **Dock dome name:** 28sp, a step up from the toggles' 24sp labels, in the ring's purple. Too loud,
  or right as the title of what's playing?
- **Where the pickers drop their prefix:** now that Ends always sits at the left and the pickers
  have the whole right side to themselves, it switches at a 342.5dp side budget, a bar of about
  930dp, where the prefixed pair ("Vibe: Preview", "Viz: Off") first fits whole — well under the
  old ~1200dp. Right switch point? At 900dp (the dock's floor) a long ending style (CROSSFADE) is
  what shortens instead, now in the left group beside Pulsar and Info, to "CRO…"; is that acceptable?
- **Swipe feel:**
  - the commit distance (32dp, or a quick 12dp flick);
  - the dome's tilt;
  - the roll speed.
- **Wiggle:** enough to say "swipe me" without nagging? It waits 1.5 s, and never returns once you've
  swiped.
- **Press light:** the round disc on the phone bar's and the rail's dome, in place of the square
  ripple; and their focus circle, in the ring's cyan.
- **Oscilloscope ring:**
  - does it read as the music at a glance, or as noise?
  - the played trace is a thinner line than the unplayed track after it (2.5dp against 4dp on the
    phone's 64dp ring; both scale up with the dock's 96–123dp dome); does the thin line hold up on
    the phone?
  - on busy mixes the track colours often blend to a muted mauve, which shows more on a thin line.
- **Song story:** do the dock band's heights and colours tell you something useful?
  - The bars are 3dp wide with 2dp gaps: about 248 across a 1280dp window and 184 at 960dp.
  - The band is 36dp tall now, the bars up to 24dp. Is that the "tad bigger" you meant?
  - The shade under the band keeps the colours readable over a bright viz. Too dark, or not enough?
- **Zip:** subtle enough? About 1.6 s per pass, then 0.8 s of rest.
- **Paused long names:** they scroll forever off TV, about 5 s a pass and 1.6 s of rest, and the
  paused zip runs forever too. Your choice; worth it for the battery on a phone left paused?
- **Restart threshold:** 5 s. This rule is yours: `shouldRestartOnPrevious` in
  `features/pulsar/.../playback/RestartRule.kt`.
- **▶ during an ending:** ▶ during an auto-advance lands on the Up next vibe the bar names, while
  your own rapid presses still move one further each. ◀ during it replays the song that was ending.
  Is that what you want?
- **Transition:** TAPE (the global default) for button and swipe skips. Right feel, or would CUT or
  GAP suit buttons better?
- **Rail contrast:** the vibe name in the rail sits over a bright visualizer.
- **Header:** the landscape header and Pulsar's top alignment in your usual window.
- Deleting the old beat-wave code waits on this scope check.

## Expect

- **Oscilloscope timing:** a hit shows one or two frames late. Its first windows haven't repeated
  yet, so the ring swells the last shape before the hit's own waveform appears.
- **Dock dome taps:** only the ring's width takes taps and drags, the name's middle included. The
  outer letters of a long name, wider than the ring, do nothing when tapped or dragged.
- **iPad:** the dome holds no focus at launch (iPad starts in touch mode), so Space and Enter need a
  Tab to the dome first.
- **Dock story recording:**
  - the band starts empty for each song, just the dim line with the dot at its start;
  - it only records while the app is visible;
  - a stretch spent in the background draws as a row of dimmed dots;
  - the band takes 36dp from the top of the stage, TV included;
  - the centre dome's ring runs on the same song progress as the band.
- **AI vibes:** vibes the AI applies still switch without a transition or a roll, by design.
- **TV:** the ring is flat, no dome rolls or wiggles, and nothing else animates on TV hardware. The
  band redraws only as the playhead reaches its next bar, so the newest bar can lag a beat or two.
- **Early restart:** a restart inside the first loop of a song may not snap the progress back until
  the next loop boundary.

## Android phone

- **Swipe feel on a real touchscreen:** right for next, left for previous; the dome's tilt and roll.
- **Wiggle:** rotate the phone, or fold and unfold, after the wiggle has run: it does not come
  again until the next launch.
- **TalkBack:** on the dome it reads "Pause, <vibe>" (or "Play, …") and offers "Next vibe" and
  "Previous vibe" actions. Past 5 s the second reads "Restart vibe". Using either counts as having
  learned the swipe: the wiggle stops for good.
- **Oscilloscope ring:** the thin played line against the thicker unplayed track at phone size.
- **Home-screen widget:** ⏮ past 5 s restarts and refreshes quickly.
- **Font size:** at a large system font size, check whether the nav bar grows taller.
- **Switching apps:** after a short switch, the dock's song band on a tablet may sit up to one loop off
  until the next boundary.
