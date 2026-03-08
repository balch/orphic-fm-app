import wave, struct, math, os

ENGINE_NAMES = [
    "Virtual Analog", "Waveshaping", "FM", "Grain",
    "Additive", "Wavetable", "Chord", "Speech",
    "Swarm", "Noise/Particle", "String/Modal", "Bass",
    "C-Major", "Resonator", "Modal/String2", "Drone"
]

def read_wav(path):
    with wave.open(path, 'r') as w:
        nch = w.getnchannels()
        sw = w.getsampwidth()
        nf = w.getnframes()
        raw = w.readframes(nf)
    fmt = {1: 'b', 2: 'h', 4: 'i'}[sw]
    samples = struct.unpack(f'{nf*nch}{fmt}', raw)
    if nch > 1:
        samples = samples[::nch]
    scale = 2**(sw*8-1)
    return [s/scale for s in samples]

def metrics(samples):
    n = len(samples)
    if n == 0:
        return 0, 0, 0, 0
    rms  = math.sqrt(sum(s*s for s in samples)/n)
    peak = max(abs(s) for s in samples)
    zcr  = sum(1 for i in range(1,n) if samples[i-1]*samples[i] < 0) / n
    crest = peak/rms if rms > 0 else 0
    return rms, peak, zcr, crest

print(f"{'Engine':<22} {'':>4}  {'RMS':>8}  {'Peak':>8}  {'ZCR':>8}  {'Crest':>7}")
print("=" * 67)

for i in range(13):
    name = ENGINE_NAMES[i]
    cpp_f  = f"cpp_engine_{i:02d}.wav"
    jsyn_f = f"jsyn_engine_{i:02d}.wav"

    cpp_ok  = os.path.exists(cpp_f)
    jsyn_ok = os.path.exists(jsyn_f)

    for tag, fname, ok in [("C++", cpp_f, cpp_ok), ("JSyn", jsyn_f, jsyn_ok)]:
        label = name if tag == "C++" else ""
        if ok:
            s = read_wav(fname)
            rms, peak, zcr, crest = metrics(s)
            print(f"{label:<22} {tag:>4}  {rms:>8.4f}  {peak:>8.4f}  {zcr:>8.4f}  {crest:>7.2f}")
        else:
            print(f"{label:<22} {tag:>4}  {'MISSING':>8}")

    if cpp_ok and jsyn_ok:
        cr, cp, cz, cc = metrics(read_wav(cpp_f))
        jr, jp, jz, jc = metrics(read_wav(jsyn_f))
        print(f"{'':22} {'Δ':>4}  {jr-cr:>+8.4f}  {jp-cp:>+8.4f}  {jz-cz:>+8.4f}  {jc-cc:>+7.2f}")
    print()

print()
print("--- Raw engine renders (bypassing voice mixing) ---")
print(f"{'Engine':<22} {'':>4}  {'RMS':>8}  {'Peak':>8}  {'ZCR':>8}  {'Crest':>7}")
print("=" * 67)

for i in range(13):
    name = ENGINE_NAMES[i]
    cpp_f  = f"cpp_raw_{i:02d}.wav"
    jsyn_f = f"jsyn_raw_{i:02d}.wav"

    cpp_ok  = os.path.exists(cpp_f)
    jsyn_ok = os.path.exists(jsyn_f)

    for tag, fname, ok in [("C++", cpp_f, cpp_ok), ("JSyn", jsyn_f, jsyn_ok)]:
        label = name if tag == "C++" else ""
        if ok:
            s = read_wav(fname)
            rms, peak, zcr, crest = metrics(s)
            print(f"{label:<22} {tag:>4}  {rms:>8.4f}  {peak:>8.4f}  {zcr:>8.4f}  {crest:>7.2f}")
        else:
            print(f"{label:<22} {tag:>4}  {'MISSING':>8}")

    if cpp_ok and jsyn_ok:
        cr, cp, cz, cc = metrics(read_wav(cpp_f))
        jr, jp, jz, jc = metrics(read_wav(jsyn_f))
        print(f"{'':22} {'Δ':>4}  {jr-cr:>+8.4f}  {jp-cp:>+8.4f}  {jz-cz:>+8.4f}  {jc-cc:>+7.2f}")
    print()
