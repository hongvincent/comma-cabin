#!/usr/bin/env python3
"""Generates the app's own rest sound and end chime (no recordings, no downloads).

Python standard library only, fixed seed, so the same files come out every run:
    python3 -I tools/gen_rest_audio.py app/src/main/res/raw

rest_loop.wav       20 s, 22050 Hz, mono, 16-bit PCM. Soft brown/pink noise with a slow swell.
                    The last second is cross-faded into the start, so the loop point is continuous.
announce_chime.wav  1.8 s, 44100 Hz, mono, 16-bit PCM. Two soft bell tones (G5, E5).
"""
import math
import random
import struct
import sys
import wave
from pathlib import Path

SEED = 20261112


def write_wav(path, rate, samples):
    frames = b"".join(struct.pack("<h", max(-32767, min(32767, round(s * 32767)))) for s in samples)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(frames)


def db(x):
    return 20 * math.log10(x) if x > 0 else float("-inf")


def stats(name, rate, s):
    peak = max(abs(v) for v in s)
    rms = math.sqrt(sum(v * v for v in s) / len(s))
    steps = sorted(abs(s[i] - s[i - 1]) for i in range(1, len(s)))
    p999 = steps[int(len(steps) * 0.999)]
    print(f"{name}: {len(s) / rate:.2f} s, {rate} Hz, peak {db(peak):.1f} dBFS, rms {db(rms):.1f} dBFS, "
          f"first {s[0]:+.4f}, last {s[-1]:+.4f}, loop step {abs(s[0] - s[-1]):.4f}, "
          f"max step {steps[-1]:.4f}, 99.9% step {p999:.4f}")


def rest_loop(rate=22050, seconds=20, fade_seconds=1.0, swells=2):
    rnd = random.Random(SEED)
    n = rate * seconds
    x = int(rate * fade_seconds)
    total = n + x
    brown = 0.0
    b0 = b1 = b2 = 0.0  # pink noise filter (Paul Kellet, economy version)
    hp_prev_in = hp_prev_out = 0.0
    lp = 0.0
    hp_a = math.exp(-2 * math.pi * 30 / rate)    # remove drift below ~30 Hz
    lp_a = math.exp(-2 * math.pi * 2500 / rate)  # soften the top end
    raw = []
    for _ in range(total):
        w = rnd.uniform(-1.0, 1.0)
        brown = 0.998 * brown + 0.02 * w
        b0 = 0.99765 * b0 + w * 0.0990460
        b1 = 0.96300 * b1 + w * 0.2965164
        b2 = 0.57000 * b2 + w * 1.0526913
        pink = (b0 + b1 + b2 + w * 0.1848) * 0.05
        v = 0.85 * brown + 0.15 * pink
        hp = hp_a * (hp_prev_out + v - hp_prev_in)
        hp_prev_in, hp_prev_out = v, hp
        lp = (1 - lp_a) * hp + lp_a * lp
        raw.append(lp)
    out = raw[:n]
    for i in range(x):  # equal-power cross-fade of the tail into the head: out[n-1] -> out[0] is continuous
        t = (i + 0.5) / x * math.pi / 2
        out[i] = raw[i] * math.sin(t) + raw[n + i] * math.cos(t)
    mean = sum(out) / n
    out = [v - mean for v in out]
    for i in range(n):  # slow periodic swell, exactly `swells` cycles per loop
        out[i] *= 1.0 - 0.3 * (0.5 - 0.5 * math.cos(2 * math.pi * swells * i / n))
    peak = max(abs(v) for v in out)
    gain = 10 ** (-9 / 20) / peak  # peak at -9 dBFS
    return rate, [v * gain for v in out]


def chime(rate=44100, seconds=1.8):
    n = int(rate * seconds)
    out = [0.0] * n
    for start, freq in ((0.0, 783.99), (0.45, 659.25)):
        s0 = int(start * rate)
        for i in range(s0, n):
            t = (i - s0) / rate
            attack = 0.5 - 0.5 * math.cos(math.pi * min(t / 0.015, 1.0))
            env = attack * math.exp(-t / 0.45)
            out[i] += env * (math.sin(2 * math.pi * freq * t)
                             + 0.25 * math.sin(2 * math.pi * 2 * freq * t)
                             + 0.08 * math.sin(2 * math.pi * 3 * freq * t))
    tail = int(0.05 * rate)
    for i in range(tail):  # end exactly at silence
        out[n - tail + i] *= 1 - (i + 1) / tail
    peak = max(abs(v) for v in out)
    gain = 10 ** (-9 / 20) / peak
    return rate, [v * gain for v in out]


def main():
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw")
    target.mkdir(parents=True, exist_ok=True)
    for name, (rate, samples) in (("rest_loop.wav", rest_loop()), ("announce_chime.wav", chime())):
        write_wav(target / name, rate, samples)
        stats(name, rate, samples)


if __name__ == "__main__":
    main()
