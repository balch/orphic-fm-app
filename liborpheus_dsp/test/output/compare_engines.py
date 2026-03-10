import wave, struct, math, os

ENGINES = [
    "virtual_analog",
    "waveshaping",
    "fm",
    "grain",
    "additive",
    "wavetable",
    "chord",
    "speech",
    "swarm",
    "noise",
    "string",
    "modal",
    "particle",
]

DISPLAY = {
    "virtual_analog": "Virtual Analog",
    "waveshaping":    "Waveshaping",
    "fm":             "FM",
    "grain":          "Grain",
    "additive":       "Additive",
    "wavetable":      "Wavetable",
    "chord":          "Chord",
    "speech":         "Speech",
    "swarm":          "Swarm",
    "noise":          "Noise/Particle",
    "string":         "String",
    "modal":          "Modal",
    "particle":       "Particle",
}

def read_wav(path):
    with wave.open(path, 'r') as w:
        nch = w.getnchannels()
        sw  = w.getsampwidth()
        nf  = w.getnframes()
        raw = w.readframes(nf)
    fmt = {1: 'b', 2: 'h', 4: 'i'}[sw]
    samples = struct.unpack(f'{nf*nch}{fmt}', raw)
    if nch > 1:
        samples = samples[::nch]
    scale = 2 ** (sw * 8 - 1)
    return [s / scale for s in samples]

def metrics(samples):
    n = len(samples)
    if n == 0:
        return 0, 0, 0, 0
    rms   = math.sqrt(sum(s * s for s in samples) / n)
    peak  = max(abs(s) for s in samples)
    zcr   = sum(1 for i in range(1, n) if samples[i-1] * samples[i] < 0) / n
    crest = peak / rms if rms > 0 else 0
    return rms, peak, zcr, crest

def print_table(title, cpp_prefix, jsyn_prefix):
    print(f"\n{'─'*67}")
    print(f"  {title}")
    print(f"{'─'*67}")
    print(f"{'Engine':<20} {'':>4}  {'RMS':>8}  {'Peak':>8}  {'ZCR':>8}  {'Crest':>7}")
    print(f"{'='*67}")
    for key in ENGINES:
        name   = DISPLAY[key]
        cpp_f  = f"{cpp_prefix}{key}.wav"
        jsyn_f = f"{jsyn_prefix}{key}.wav"
        cpp_ok  = os.path.exists(cpp_f)
        jsyn_ok = os.path.exists(jsyn_f)

        for tag, fname, ok in [("C++", cpp_f, cpp_ok), ("JSyn", jsyn_f, jsyn_ok)]:
            label = name if tag == "C++" else ""
            if ok:
                s = read_wav(fname)
                rms, peak, zcr, crest = metrics(s)
                print(f"{label:<20} {tag:>4}  {rms:>8.4f}  {peak:>8.4f}  {zcr:>8.4f}  {crest:>7.2f}")
            else:
                print(f"{label:<20} {tag:>4}  {'MISSING'}")

        if cpp_ok and jsyn_ok:
            cr, cp, cz, cc = metrics(read_wav(cpp_f))
            jr, jp, jz, jc = metrics(read_wav(jsyn_f))
            print(f"{'':20} {'Δ':>4}  {jr-cr:>+8.4f}  {jp-cp:>+8.4f}  {jz-cz:>+8.4f}  {jc-cc:>+7.2f}")
        print()

RINGS_MODES = [
    "modal", "sympathetic", "string",
]

RINGS_DISPLAY = {
    "modal":                  "Modal (Bar)",
    "sympathetic":            "Sympathetic (Sitar)",
    "string":                 "String",
}

def print_rings_table(title, cpp_prefix, jsyn_prefix):
    print(f"\n{'─'*67}")
    print(f"  {title}")
    print(f"{'─'*67}")
    print(f"{'Mode':<24} {'':>4}  {'RMS':>8}  {'Peak':>8}  {'ZCR':>8}  {'Crest':>7}")
    print(f"{'='*67}")
    for key in RINGS_MODES:
        name   = RINGS_DISPLAY[key]
        cpp_f  = f"{cpp_prefix}{key}.wav"
        jsyn_f = f"{jsyn_prefix}{key}.wav"
        cpp_ok  = os.path.exists(cpp_f)
        jsyn_ok = os.path.exists(jsyn_f)

        for tag, fname, ok in [("C++", cpp_f, cpp_ok), ("JSyn", jsyn_f, jsyn_ok)]:
            label = name if tag == "C++" else ""
            if ok:
                s = read_wav(fname)
                rms, peak, zcr, crest = metrics(s)
                print(f"{label:<24} {tag:>4}  {rms:>8.4f}  {peak:>8.4f}  {zcr:>8.4f}  {crest:>7.2f}")
            else:
                print(f"{label:<24} {tag:>4}  {'MISSING'}")

        if cpp_ok and jsyn_ok:
            cr, cp, cz, cc = metrics(read_wav(cpp_f))
            jr, jp, jz, jc = metrics(read_wav(jsyn_f))
            print(f"{'':24} {'Δ':>4}  {jr-cr:>+8.4f}  {jp-cp:>+8.4f}  {jz-cz:>+8.4f}  {jc-cc:>+7.2f}")
        print()

print_table("Full voice render  (cpp_engine_* vs jsyn_engine_*)",
            "cpp_engine_", "jsyn_engine_")
print_table("Raw engine render  (cpp_raw_*    vs jsyn_raw_*)",
            "cpp_raw_",    "jsyn_raw_")
print_rings_table("Resonator modes  (cpp_rings_mode_* vs jsyn_rings_mode_*)",
                  "cpp_rings_mode_", "jsyn_rings_mode_")
