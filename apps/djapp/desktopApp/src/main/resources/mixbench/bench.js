// Mix Bench page: renders `state` and `meters` from the app and posts edits to /cmd.
(function () {
  "use strict";

  const KEY = window.BENCH_KEY;
  const TRACKS = ["KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXT", "FX"];
  const SOURCES = [[8, "STORM", "weather"], [9, "STREET", "street"], [10, "SPEECH", "speech"]];
  const MACROS = ["ENERGY", "COMPLEXITY", "MOOD", "SPACE"];
  const FADER_MIN = -30, FADER_MAX = 3.5;
  const METER_FLOOR = -60;

  let state = null;
  let overview = null; // {vibe, data}
  let meters = null;
  const busyUntil = {}; // control id -> ms timestamp while the user is moving it

  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const f2 = (x) => Number(x).toFixed(2);
  const f1 = (x) => Number(x).toFixed(1);
  const silent = (x) => x <= -119;

  // ---- commands ----------------------------------------------------------

  function cmd(op, args) {
    return fetch("cmd?key=" + KEY, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(Object.assign({ op }, args || {})),
    }).then((r) => r.json()).then((j) => {
      if (!j.ok) toast(j.error || "command failed");
      return j;
    }).catch((e) => { toast(String(e)); return { ok: false }; });
  }

  // One request in flight per control, so they land in order; the latest value waits
  // and goes out when the previous one returns, so a drag's final value is always sent.
  const inflight = {};
  function throttled(id, op, args) {
    const f = inflight[id];
    if (f) { f.next = args; return; }
    inflight[id] = { next: null };
    cmd(op, args).finally(() => setTimeout(() => {
      const q = inflight[id];
      delete inflight[id];
      if (q && q.next) throttled(id, op, q.next);
    }, 40));
  }

  function hold(id) { busyUntil[id] = Infinity; }
  function release(id) { busyUntil[id] = Date.now() + 500; }
  // pointerup, pointercancel and a lost capture all end a drag; otherwise the control ignores state forever.
  function onRelease(target, id) {
    for (const ev of ["pointerup", "pointercancel", "lostpointercapture"]) target.addEventListener(ev, () => release(id));
  }
  const busy = (id) => (busyUntil[id] || 0) > Date.now();

  let toastTimer = 0;
  function toast(msg) {
    const t = $("toast");
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 4000);
  }

  // ---- canvas helpers ----------------------------------------------------

  function fit(c) {
    const r = c.getBoundingClientRect();
    const d = window.devicePixelRatio || 1;
    const w = Math.max(1, Math.round(r.width * d)), h = Math.max(1, Math.round(r.height * d));
    if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
    return c.getContext("2d");
  }

  function drawKnob(c, v, changed) {
    const g = fit(c), w = c.width, h = c.height, r = Math.min(w, h) / 2 - 3;
    const a0 = Math.PI * 0.75, a1 = Math.PI * 2.25, a = a0 + (a1 - a0) * v;
    g.clearRect(0, 0, w, h);
    g.lineWidth = 4 * (window.devicePixelRatio || 1);
    g.strokeStyle = "#2a2540";
    g.beginPath(); g.arc(w / 2, h / 2, r, a0, a1); g.stroke();
    g.strokeStyle = changed ? "#ffc24d" : "#7c5cff";
    g.beginPath(); g.arc(w / 2, h / 2, r, a0, a); g.stroke();
    g.strokeStyle = "#cfc8ff";
    g.lineWidth = 2 * (window.devicePixelRatio || 1);
    g.beginPath();
    g.moveTo(w / 2 + Math.cos(a) * r * 0.3, h / 2 + Math.sin(a) * r * 0.3);
    g.lineTo(w / 2 + Math.cos(a) * r * 0.85, h / 2 + Math.sin(a) * r * 0.85);
    g.stroke();
  }

  function drawMeter(c, peakDb) {
    const g = fit(c), w = c.width, h = c.height;
    g.clearRect(0, 0, w, h);
    const v = silent(peakDb) ? 0 : Math.max(0, Math.min(1, (peakDb - METER_FLOOR) / -METER_FLOOR));
    const grad = g.createLinearGradient(0, h, 0, 0);
    grad.addColorStop(0, "#4dd97a"); grad.addColorStop(0.7, "#4dd97a");
    grad.addColorStop(0.85, "#ffc24d"); grad.addColorStop(1, "#ff4d4d");
    g.fillStyle = grad;
    g.fillRect(0, h * (1 - v), w, h * v);
    g.fillStyle = "#3a3456";
    for (const db of [-6, -12, -24, -48]) {
      const y = h * (db / METER_FLOOR);
      g.fillRect(0, y, w, 1);
    }
  }

  // Each strip scales to its own loudest point so quiet tracks still show their shape.
  function drawMini(c, wave, color) {
    const g = fit(c), w = c.width, h = c.height;
    g.clearRect(0, 0, w, h);
    if (!wave || !wave.length) return;
    let peak = 0.02;
    for (let i = 0; i < wave.length; i++) peak = Math.max(peak, Math.abs(wave[i]));
    const bins = wave.length / 2, mid = h / 2, s = (h / 2 - 1) / peak;
    g.fillStyle = color;
    for (let j = 0; j < bins; j++) {
      const x = Math.floor((j / bins) * w), x2 = Math.floor(((j + 1) / bins) * w);
      const y0 = mid - wave[2 * j + 1] * s, y1 = mid - wave[2 * j] * s;
      g.fillRect(x, y0, Math.max(1, x2 - x), Math.max(1, y1 - y0));
    }
  }

  // ---- static layout -----------------------------------------------------

  const strips = [];
  const sources = [];
  const knobs = {};

  function buildKnobs() {
    const box = $("knobs");
    for (const m of MACROS) {
      const k = el("div", "knob");
      const c = el("canvas");
      const lab = el("div", "klab", m);
      const val = el("div", "kval", "-");
      const sub = el("div", "ksub", "");
      k.append(c, lab, val, sub);
      box.append(k);
      const id = "macro-" + m;
      let startY = 0, startV = 0;
      c.addEventListener("pointerdown", (e) => {
        if (!state) return;
        c.setPointerCapture(e.pointerId);
        hold(id);
        startY = e.clientY;
        startV = knobValue(m);
      });
      c.addEventListener("pointermove", (e) => {
        if (!c.hasPointerCapture(e.pointerId)) return;
        const v = Math.max(0, Math.min(1, startV + (startY - e.clientY) / 200));
        knobs[m].shown = v;
        drawKnob(c, v, true);
        val.textContent = f2(v);
        throttled(id, "macro", { m, v });
      });
      onRelease(c, id);
      c.addEventListener("dblclick", () => {
        if (!state) return;
        const mm = state.macros[m];
        // Back to the file: the vibe default, or the section's multiplied default.
        const v = sectionScope() ? Math.min(1, mm.file * mm.mult) : mm.file;
        cmd("macro", { m, v });
      });
      knobs[m] = { c, val, sub, shown: 0 };
    }
  }

  function knobValue(m) {
    const mm = state.macros[m];
    return sectionScope() ? mm.heard : mm.knob;
  }

  function slider(label, min, max, step, id, onInput, onReset) {
    const row = el("div", "ctl");
    const input = el("input");
    input.type = "range"; input.min = min; input.max = max; input.step = step;
    const v = el("span", "v", "");
    row.append(el("span", null, label), input, v);
    input.addEventListener("pointerdown", () => hold(id));
    onRelease(input, id);
    input.addEventListener("input", () => { hold(id); onInput(Number(input.value)); });
    input.addEventListener("change", () => release(id));
    input.addEventListener("dblclick", onReset);
    return { row, input, v };
  }

  function panText(p) {
    const n = Math.round(Math.abs(p) * 100);
    return n === 0 ? "C" : (p < 0 ? "L" : "R") + n;
  }

  function buildStrips() {
    const box = $("strips");
    TRACKS.forEach((name, t) => {
      const s = el("div", "strip");
      const eng = el("div", "eng", "");
      const mini = el("canvas", "mini");
      const pan = slider("pan", -1, 1, 0.01, "pan-" + t, (v) => {
        pan.v.textContent = panText(v);
        throttled("pan-" + t, "pan", { t, v });
      }, () => state && cmd("pan", { t, v: state.tracks[t].filePan }));
      const rev = slider("R", 0, 1, 0.01, "rev-" + t, (v) => {
        rev.v.textContent = f2(v);
        throttled("rev-" + t, "reverb", { t, v });
      }, () => {});
      const del = slider("D", 0, 1, 0.01, "del-" + t, (v) => {
        del.v.textContent = f2(v);
        throttled("del-" + t, "delay", { t, v });
      }, () => {});
      const wrap = el("div", "fadwrap");
      const meter = el("canvas", "meter");
      const fader = el("div", "fader");
      const zero = el("i", "zero");
      zero.style.bottom = faderPos(0) * 100 + "%";
      const thumb = el("b");
      fader.append(zero, thumb);
      wrap.append(meter, fader);
      const db = el("div", "db", "0.0 dB");
      const lu = el("div", "lu", "-");
      const ms = el("div", "ms");
      const m = el("button", "m", "M");
      const so = el("button", "s", "S");
      m.addEventListener("click", () => cmd("mute", { t }));
      so.addEventListener("click", () => cmd("solo", { t }));
      ms.append(m, so);
      s.append(el("div", "nm", name), eng, mini, pan.row, rev.row, del.row, wrap, db, lu, ms);
      box.append(s);

      const id = "fader-" + t;
      const setFromY = (e) => {
        const r = fader.getBoundingClientRect();
        const pos = Math.max(0, Math.min(1, (r.bottom - e.clientY) / r.height));
        const v = Math.round((FADER_MIN + pos * (FADER_MAX - FADER_MIN)) * 10) / 10;
        showFader(t, v, true);
        throttled(id, "level", { t, db: v });
      };
      fader.addEventListener("pointerdown", (e) => { fader.setPointerCapture(e.pointerId); hold(id); setFromY(e); });
      fader.addEventListener("pointermove", (e) => { if (fader.hasPointerCapture(e.pointerId)) setFromY(e); });
      onRelease(fader, id);
      fader.addEventListener("dblclick", () => cmd("level", { t, db: 0 }));
      strips.push({ s, eng, mini, pan, rev, del, meter, fader, thumb, db, lu, m, so });
    });
    for (const [ch, name, what] of SOURCES) {
      const s = el("div", "strip src");
      const mini = el("canvas", "mini");
      const wrap = el("div", "fadwrap");
      const meter = el("canvas", "meter");
      wrap.append(meter);
      const lu = el("div", "lu", "-");
      s.append(el("div", "nm", name), el("div", "eng", what), mini, wrap, el("div", "db", "meter only"), lu);
      box.append(s);
      sources.push({ ch, mini, meter, lu });
    }
  }

  function faderPos(db) {
    return Math.max(0, Math.min(1, (db - FADER_MIN) / (FADER_MAX - FADER_MIN)));
  }

  function showFader(t, db, changed) {
    const st = strips[t];
    st.thumb.style.bottom = faderPos(db) * 100 + "%";
    st.db.textContent = (db > 0 ? "+" : "") + f1(db) + " dB";
    st.db.classList.toggle("ch", changed);
    st.fader.classList.toggle("ch", changed);
  }

  // ---- state rendering ---------------------------------------------------

  const sectionScope = () => state && state.scope === "SECTION" && state.sectionScopeAvailable && state.section >= 0;
  const sectionName = (i) => (state.sections[i] || {}).name || ("section " + i);

  function render() {
    if (!state) return;
    renderTop();
    renderChips();
    renderStrips();
    renderSide();
    drawOverview();
    healOverview();
  }

  function renderTop() {
    const sel = $("vibe");
    if (document.activeElement !== sel) sel.value = state.vibe;
    $("play").textContent = state.playing ? "Pause" : "Play";
    const loop = $("loop");
    loop.classList.toggle("on", state.loop != null);
    loop.textContent = state.loop != null ? "Looping " + sectionName(state.loop) : "Loop section";
    for (const m of MACROS) {
      const k = knobs[m], mm = state.macros[m];
      if (busy("macro-" + m)) continue;
      const v = knobValue(m);
      const changed = sectionScope() ? Math.abs(mm.heard - Math.min(1, mm.file * mm.mult)) > 1e-3
        : Math.abs(mm.knob - mm.file) > 1e-3;
      drawKnob(k.c, v, changed);
      k.val.textContent = changed ? f2(v) + " ← " + f2(sectionScope() ? Math.min(1, mm.file * mm.mult) : mm.file) : f2(v);
      k.val.classList.toggle("ch", changed);
      k.sub.textContent = sectionScope() ? "heard here" : (Math.abs(mm.mult - 1) > 1e-3 ? "heard " + f2(mm.heard) + " (×" + f2(mm.mult) + ")" : "");
    }
    const stale = $("stale");
    stale.hidden = !state.stale;
    stale.textContent = "The vibe file" + (state.source ? " (" + state.source + ")" : "") +
      " changed since the bench loaded it, so these values and captures are stale. Relaunch the bench to mix from the new file.";
  }

  function renderChips() {
    const box = $("chips");
    box.replaceChildren();
    state.sections.forEach((s) => {
      const c = el("button", "chip", s.name);
      c.classList.toggle("on", s.index === state.section);
      c.classList.toggle("loop", s.index === state.loop);
      c.title = "Jump to " + s.name + " at the next bar line";
      c.addEventListener("click", () => cmd("jump", { section: s.index }));
      box.append(c);
    });
  }

  function renderStrips() {
    state.tracks.forEach((tr, t) => {
      const st = strips[t];
      st.eng.textContent = tr.engine;
      st.eng.title = "EDM / Space engines";
      if (!busy("pan-" + t)) { st.pan.input.value = tr.pan; st.pan.v.textContent = panText(tr.pan); }
      if (!busy("rev-" + t)) { st.rev.input.value = tr.reverb; st.rev.v.textContent = f2(tr.reverb); }
      if (!busy("del-" + t)) { st.del.input.value = tr.delay; st.del.v.textContent = f2(tr.delay); }
      if (!busy("fader-" + t)) showFader(t, tr.levelDb, Math.abs(tr.levelDb) > 0.05);
      st.m.classList.toggle("on", tr.muted);
      st.so.classList.toggle("on", tr.soloed);
      const anySolo = state.tracks.some((x) => x.soloed);
      st.s.classList.toggle("off", tr.muted || (anySolo && !tr.soloed));
    });
  }

  const FIELD = {
    VOLUME: "volume (edm)", VOLUME_SPACE: "volume (space)", PAN: "pan",
    REVERB_SEND: "reverbSend", REVERB_SEND_SPACE: "reverbSend (space)",
    DELAY_SEND: "delaySend", DELAY_SEND_SPACE: "delaySend (space)",
    MACRO: "", MACRO_RAMP: "",
  };

  function fieldName(r) {
    if (r.field === "MACRO") return r.macro.toLowerCase() + (r.scope === "SECTION" ? " multiplier" : "");
    if (r.field === "MACRO_RAMP") return r.macro.toLowerCase() + " rampTo";
    return FIELD[r.field] || r.field;
  }

  function renderSide() {
    $("srcname").textContent = state.source ? "vs " + state.source : "(source not found)";
    const sv = $("scopeVibe"), ss = $("scopeSection");
    sv.classList.toggle("on", state.scope === "VIBE" || !state.sectionScopeAvailable);
    ss.classList.toggle("on", state.scope === "SECTION" && state.sectionScopeAvailable);
    ss.disabled = !state.sectionScopeAvailable;
    ss.textContent = state.section >= 0 && state.sectionScopeAvailable ? sectionName(state.section) : "section";
    ss.title = state.sectionScopeAvailable ? "Edits land in the playing section only" : "This vibe has fewer than two sections";
    $("scopenote").textContent = sectionScope()
      ? "Pan has no per-section value: pan moves still land in the whole vibe."
      : "";
    const box = $("rows");
    box.replaceChildren();
    if (!state.changes.length) {
      box.append(el("div", "empty", "No changes. Move a fader, pan, send or knob."));
      return;
    }
    let last = "";
    let spaceWarn = false;
    for (const r of state.changes) {
      const where = r.scope === "VIBE" ? "vibe" : "section \"" + r.sectionName + "\"";
      const head = "// " + where + (r.track != null ? " · " + TRACKS[r.track] : "");
      if (head !== last) { box.append(el("div", "hd", head)); last = head; }
      const f = fieldName(r);
      box.append(el("div", "del", "- " + f + " = " + r.old.toFixed(3)));
      let add = "+ " + f + " = " + r.new.toFixed(3);
      if ((r.field === "VOLUME" || r.field === "VOLUME_SPACE") && r.old > 0 && r.new > 0) {
        const d = 20 * Math.log10(r.new / r.old);
        add += "  (" + (d > 0 ? "+" : "") + f1(d) + " dB)";
      }
      box.append(el("div", "add", add));
      if (r.clampedDb > 0) {
        box.append(el("div", "flag", "clamped: written value is " + f1(r.clampedDb) +
          " dB quieter than heard; cut the other tracks by that much instead"));
      }
      if (r.scope === "SECTION" && r.macro === "SPACE") spaceWarn = true;
    }
    if (spaceWarn) box.append(el("div", "flag", "A section Space change also scales that section's sends (Kotlin applies Space to sends)."));
  }

  // ---- overview ----------------------------------------------------------

  function ovSections() {
    if (!overview || !state || overview.vibe !== state.vibe) return null;
    const d = overview.data;
    return d.sections.slice().sort((a, b) => a.start_s - b.start_s).map((s, i, all) => ({
      index: s.index, name: s.name, start: s.start_s, end: i + 1 < all.length ? all[i + 1].start_s : d.seconds,
    }));
  }

  // A missed `overview` event (reconnect, re-pick) heals by asking for it.
  let ovFetching = null;
  function healOverview() {
    if (!state || state.overview !== "ready" || ovFetching === state.vibe) return;
    if (overview && overview.vibe === state.vibe) return;
    const vibe = (ovFetching = state.vibe);
    fetch("overview?key=" + KEY + "&vibe=" + encodeURIComponent(vibe))
      .then((r) => (r.ok ? r.json() : null))
      .then((o) => { if (o && state && o.vibe === state.vibe) { overview = o; drawOverview(); } })
      .catch(() => {})
      .finally(() => { if (ovFetching === vibe) ovFetching = null; });
  }

  function drawOverview() {
    const c = $("overview"), g = fit(c), w = c.width, h = c.height, d = window.devicePixelRatio || 1;
    g.clearRect(0, 0, w, h);
    const msg = $("ovmsg");
    const secs = ovSections();
    if (!secs) {
      const st = state ? state.overview : "none";
      msg.textContent = st === "rendering" ? "rendering overview… (a headless render; can take a few minutes)"
        : st === "failed" ? "overview render failed (see the app log)"
        : st === "none" ? "no overview (vibe source not found)" : "";
      return;
    }
    msg.textContent = "";
    const data = overview.data, env = data.env, mid = h / 2;
    let peak = 0.05;
    for (const p of env) peak = Math.max(peak, Math.abs(p[0]), Math.abs(p[1]));
    g.fillStyle = "#7c5cff";
    for (let i = 0; i < env.length; i++) {
      const x = Math.floor((i / env.length) * w), x2 = Math.floor(((i + 1) / env.length) * w);
      const y0 = mid - (env[i][1] / peak) * (mid - 2), y1 = mid - (env[i][0] / peak) * (mid - 2);
      g.fillRect(x, y0, Math.max(1, x2 - x), Math.max(1, y1 - y0));
    }
    g.font = 10 * d + "px ui-sans-serif, system-ui";
    for (const s of secs) {
      const x = (s.start / data.seconds) * w;
      g.fillStyle = s.index === state.section ? "rgba(124,92,255,.18)" : "rgba(0,0,0,0)";
      g.fillRect(x, 0, ((s.end - s.start) / data.seconds) * w, h);
      g.fillStyle = "#3a3456";
      g.fillRect(x, 0, d, h);
      g.fillStyle = "#8a83b0";
      g.fillText(s.name, x + 4 * d, 11 * d);
    }
    // Playhead: the playing section plus its bar, not a sample-accurate time.
    const cur = secs.find((s) => s.index === (meters ? meters.section : state.section));
    if (cur && meters && meters.bars > 0) {
      const t = cur.start + (cur.end - cur.start) * Math.min(1, meters.bar / meters.bars);
      g.fillStyle = "#ff5c8a";
      g.fillRect((t / data.seconds) * w, 0, 2 * d, h);
    }
  }

  $("overview").addEventListener("click", (e) => {
    const secs = ovSections();
    if (!secs) return;
    const r = e.currentTarget.getBoundingClientRect();
    const t = ((e.clientX - r.left) / r.width) * overview.data.seconds;
    const s = secs.find((x) => t >= x.start && t < x.end) || secs[secs.length - 1];
    cmd("jump", { section: s.index });
  });

  // ---- meters ------------------------------------------------------------

  function renderMeters() {
    const m = meters;
    if (!m) return;
    const lu = (x) => silent(x) ? "-" : f1(x);
    const b = (text, cls) => el("b", cls, text);
    $("bus").replaceChildren(
      "LUFS short ", b(lu(m.lufsS)), el("span", "live", "live"),
      " · integrated ", b(lu(m.lufsI)), el("span", "live", "live"), el("br"),
      "pre-clip ", b(lu(m.peak) + " dBFS", m.peak > -1 ? "hot" : null),
      " · past knee ", b(f1(m.knee) + "%"), " · corr ", b(f2(m.corr)));
    strips.forEach((st, t) => {
      const ch = m.ch[t];
      if (!ch) return;
      drawMeter(st.meter, ch.pk);
      drawMini(st.mini, ch.w, "#7c5cff");
      st.lu.textContent = silent(ch.lu) ? "- LU" : f1(ch.lu) + " LU";
      st.lu.title = "Loudness against the dry mix; peak " + lu(ch.pk) + " dBFS";
    });
    for (const s of sources) {
      const ch = m.ch[s.ch];
      if (!ch) continue;
      drawMeter(s.meter, ch.pk);
      drawMini(s.mini, ch.w, "#8a83b0");
      s.lu.textContent = silent(ch.lu) ? "- LU" : f1(ch.lu) + " LU";
    }
    drawOverview();
  }

  // ---- wiring ------------------------------------------------------------

  $("play").addEventListener("click", () => cmd(state && state.playing ? "pause" : "play"));
  $("loop").addEventListener("click", () => cmd("loop", { on: !(state && state.loop != null) }));
  $("vibe").addEventListener("change", (e) => { overview = null; cmd("vibe", { name: e.target.value }); });
  $("scopeVibe").addEventListener("click", () => cmd("scope", { s: "VIBE" }));
  $("scopeSection").addEventListener("click", () => cmd("scope", { s: "SECTION" }));
  $("reset").addEventListener("click", () => cmd("reset"));
  $("capture").addEventListener("click", () => {
    cmd("capture").then((j) => {
      if (!j.ok) return;
      const res = $("capres");
      res.textContent = "Wrote " + j.rows.length + " rows to " + j.file + (j.stale ? " (STALE: the vibe file changed since load)" : "");
      res.style.color = j.stale ? "#ff6b6b" : "#4dd97a";
    });
  });
  window.addEventListener("resize", () => { render(); renderMeters(); });

  buildKnobs();
  buildStrips();

  function connect() {
    const es = new EventSource("events?key=" + KEY);
    es.addEventListener("snapshot", (e) => {
      $("conn").hidden = true;
      const s = JSON.parse(e.data);
      const sel = $("vibe");
      sel.replaceChildren(...s.vibes.map((n) => { const o = el("option", null, n); o.value = n; return o; }));
      state = s;
      render();
    });
    es.addEventListener("state", (e) => { state = JSON.parse(e.data); render(); });
    es.addEventListener("overview", (e) => { overview = JSON.parse(e.data); drawOverview(); });
    es.addEventListener("meters", (e) => { meters = JSON.parse(e.data); renderMeters(); });
    es.onerror = () => { $("conn").hidden = false; };
  }
  connect();
})();
