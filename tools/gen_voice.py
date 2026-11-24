#!/usr/bin/env python3
"""Synthesises SCP-079's voice: one short sound per English phoneme (ARPAbet, the CMU dictionary's alphabet).

usage: gen_voice.py [--wav-only]

The plugin turns each caption into phonemes and plays these sounds one after another while the caption is typed,
so the voice is a small formant synthesiser in the spirit of 1980s speech chips - which suits a 1978 computer.
Nothing here comes from a recording or a third-party voice: every sound is computed below. ffmpeg (on the PATH)
encodes the OGG Vorbis files Minecraft needs. Re-running overwrites them.

Writes src/main/resources/pack/assets/scp079/sounds/voice/<phoneme>.ogg and the voice part of sounds.json.
"""
import json
import math
import os
import random
import struct
import subprocess
import sys
import tempfile
import wave

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "pack", "assets", "scp079")
OUT = os.path.join(ROOT, "sounds", "voice")
RATE = 22050
F0 = 98.0          # a low, flat machine voice

# formants (Hz) of the vowels - classic male averages (Peterson & Barney), bandwidths below
VOWELS = {
    "IY": (270, 2290, 3010), "IH": (390, 1990, 2550), "EH": (530, 1840, 2480), "AE": (660, 1720, 2410),
    "AH": (600, 1170, 2390), "AA": (730, 1090, 2440), "AO": (570, 840, 2410), "UH": (440, 1020, 2240),
    "UW": (300, 870, 2240), "ER": (490, 1350, 1690),
}
DIPHTHONGS = {"EY": ("EH", "IY"), "AY": ("AA", "IY"), "OY": ("AO", "IY"), "AW": ("AA", "UW"), "OW": ("AO", "UW")}
APPROXIMANTS = {"L": (360, 1000, 2400), "R": (330, 1060, 1380), "W": (300, 640, 2150), "Y": (270, 2070, 2960)}
NASALS = {"M": (260, 1050, 2200), "N": (260, 1600, 2600), "NG": (260, 2200, 2750)}
BANDWIDTHS = (70, 100, 160)

# milliseconds; the plugin's PhonemeTable.java has the same lengths in ticks - change them together
LENGTHS = {
    "vowel": 120, "diphthong": 190, "approximant": 75, "nasal": 80, "fricative": 95, "voiced_fricative": 85,
    "plosive": 65, "affricate": 110, "aspirate": 60,
}


def resonator(signal, freq, bw):
    """Second-order resonator (Klatt): unity gain at DC, a peak at freq."""
    t = 1.0 / RATE
    c = -math.exp(-2 * math.pi * bw * t)
    b = 2 * math.exp(-math.pi * bw * t) * math.cos(2 * math.pi * freq * t)
    a = 1 - b - c
    out, y1, y2 = [], 0.0, 0.0
    for x in signal:
        y = a * x + b * y1 + c * y2
        out.append(y)
        y2, y1 = y1, y
    return out


def glide_resonator(signal, freqs, bw):
    """A resonator whose frequency moves along freqs (one value per sample)."""
    t = 1.0 / RATE
    out, y1, y2 = [], 0.0, 0.0
    c = -math.exp(-2 * math.pi * bw * t)
    for x, f in zip(signal, freqs):
        b = 2 * math.exp(-math.pi * bw * t) * math.cos(2 * math.pi * f * t)
        a = 1 - b - c
        y = a * x + b * y1 + c * y2
        out.append(y)
        y2, y1 = y1, y
    return out


def buzz(n):
    """Glottal pulses: a band-limited sawtooth-like train at F0 with a hint of jitter."""
    rnd = random.Random(7)
    out, phase = [], 0.0
    for i in range(n):
        phase += F0 * (1 + rnd.uniform(-0.004, 0.004)) / RATE
        if phase >= 1:
            phase -= 1
        # Rosenberg-like pulse: rising half-cosine, quick fall, closed phase
        if phase < 0.4:
            v = 0.5 * (1 - math.cos(math.pi * phase / 0.4))
        elif phase < 0.56:
            v = math.cos(math.pi * (phase - 0.4) / 0.32)
        else:
            v = 0.0
        out.append(v)
    # differentiate: the radiated pressure wave
    return [out[i] - (out[i - 1] if i else 0) for i in range(n)]


def noise(n, seed):
    rnd = random.Random(seed)
    return [rnd.uniform(-1, 1) for _ in range(n)]


def samples(ms):
    return int(RATE * ms / 1000)


def voiced(formants, ms, gain=1.0):
    src = buzz(samples(ms))
    out = src
    for f, bw in zip(formants, BANDWIDTHS):
        out = resonator(out, f, bw)
    out = resonator(out, 3500, 250)
    return [gain * v for v in out]


def gliding(start, end, ms, gain=1.0):
    n = samples(ms)
    src = buzz(n)
    out = src
    for k, bw in enumerate(BANDWIDTHS):
        # hold the start a moment, then move: diphthongs sound like two vowels joined, not a smear
        track = []
        for i in range(n):
            t = max(0.0, min(1.0, (i / n - 0.25) / 0.6))
            track.append(start[k] + (end[k] - start[k]) * t)
        out = glide_resonator(out, track, bw)
    out = resonator(out, 3500, 250)
    return [gain * v for v in out]


def hiss(ms, center, bw, gain, seed):
    out = resonator(noise(samples(ms), seed), center, bw)
    return [gain * v for v in out]


def mix(*parts):
    n = max(len(p) for p in parts)
    return [sum(p[i] for p in parts if i < len(p)) for i in range(n)]


def concat(*parts):
    out = []
    for p in parts:
        out += p
    return out


def silence(ms):
    return [0.0] * samples(ms)


def envelope(sig, attack_ms=6, release_ms=12):
    a, r = samples(attack_ms), samples(release_ms)
    n = len(sig)
    out = list(sig)
    for i in range(min(a, n)):
        out[i] *= i / a
    for i in range(min(r, n)):
        out[n - 1 - i] *= i / r
    return out


def normalize(sig, peak):
    m = max(1e-9, max(abs(v) for v in sig))
    return [v * peak / m for v in sig]


def phoneme(name):
    if name in VOWELS:
        return normalize(envelope(voiced(VOWELS[name], LENGTHS["vowel"])), 0.85)
    if name in DIPHTHONGS:
        a, b = DIPHTHONGS[name]
        return normalize(envelope(gliding(VOWELS[a], VOWELS[b], LENGTHS["diphthong"])), 0.85)
    if name in APPROXIMANTS:
        return normalize(envelope(voiced(APPROXIMANTS[name], LENGTHS["approximant"])), 0.6)
    if name in NASALS:
        f = NASALS[name]
        sig = voiced(f, LENGTHS["nasal"])
        sig = resonator(sig, 300, 200)  # nasal murmur: most energy low
        return normalize(envelope(sig), 0.5)
    seed = sum(map(ord, name))
    fric = {"S": (6200, 1800, 1.0), "SH": (2900, 900, 1.0), "F": (4800, 4000, 0.45), "TH": (5400, 4000, 0.35)}
    voiced_fric = {"Z": "S", "ZH": "SH", "V": "F", "DH": "TH"}
    if name in fric:
        c, bw, g = fric[name]
        return normalize(envelope(hiss(LENGTHS["fricative"], c, bw, g, seed), 10, 15), 0.45 * g + 0.1)
    if name in voiced_fric:
        c, bw, g = fric[voiced_fric[name]]
        bar = voiced((250, 1200, 2400), LENGTHS["voiced_fricative"], 0.5)
        return normalize(envelope(mix(hiss(LENGTHS["voiced_fricative"], c, bw, g * 0.6, seed), bar)), 0.5)
    if name == "HH":
        return normalize(envelope(mix(*[hiss(LENGTHS["aspirate"], f, 300, 0.5, seed + k) for k, f in enumerate(VOWELS["AH"])]), 4, 15), 0.25)
    bursts = {"P": (900, 1200), "B": (900, 1200), "T": (4200, 2200), "D": (3800, 2200), "K": (2000, 900), "G": (1900, 900)}
    if name in bursts:
        c, bw = bursts[name]
        unvoiced = name in ("P", "T", "K")
        closure = silence(25) if unvoiced else voiced((180, 1000, 2400), 25, 0.35)
        burst = envelope(hiss(12, c, bw, 1.0, seed), 1, 8)
        tail = hiss(28, 1500, 2500, 0.35, seed + 1) if unvoiced else voiced((300, 1200, 2400), 28, 0.5)
        return normalize(envelope(concat(closure, burst, envelope(tail, 2, 15)), 2, 10), 0.6)
    if name == "CH":
        return normalize(envelope(concat(silence(20), hiss(10, 4200, 2200, 0.8, seed), hiss(LENGTHS["affricate"] - 30, 2900, 900, 1.0, seed + 1))), 0.55)
    if name == "JH":
        bar = voiced((250, 1200, 2400), LENGTHS["affricate"] - 20, 0.5)
        return normalize(envelope(concat(voiced((180, 1000, 2400), 20, 0.3), mix(hiss(LENGTHS["affricate"] - 20, 2900, 900, 0.6, seed), bar))), 0.55)
    raise ValueError(name)


ALL = list(VOWELS) + list(DIPHTHONGS) + list(APPROXIMANTS) + list(NASALS) + [
    "S", "SH", "F", "TH", "Z", "ZH", "V", "DH", "HH", "P", "B", "T", "D", "K", "G", "CH", "JH"]


def write_wav(path, sig):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in sig))


def encode(sig, path):
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        wav = tmp.name
    write_wav(wav, sig)
    encoders = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    codec = ["-c:a", "libvorbis", "-ac", "1"] if "libvorbis" in encoders else ["-c:a", "vorbis", "-strict", "-2", "-ac", "2"]
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav, *codec, "-ar", str(RATE), path], check=True)
    os.unlink(wav)


def main():
    os.makedirs(OUT, exist_ok=True)
    wav_only = "--wav-only" in sys.argv
    sounds = {}
    for name in ALL:
        sig = phoneme(name)
        key = name.lower()
        if wav_only:
            write_wav(os.path.join(OUT, key + ".wav"), sig)
        else:
            encode(sig, os.path.join(OUT, key + ".ogg"))
        # stream false, preload: the sounds are tiny and played many times a second
        sounds["voice." + key] = {"sounds": [{"name": "scp079:voice/" + key, "preload": True}]}
    path = os.path.join(ROOT, "sounds.json")
    existing = json.load(open(path)) if os.path.exists(path) else {}
    existing = {k: v for k, v in existing.items() if not k.startswith("voice.")}
    existing.update(sounds)
    with open(path, "w") as f:
        json.dump(dict(sorted(existing.items())), f, indent=2)
        f.write("\n")
    print(len(ALL), "phonemes written")


if __name__ == "__main__":
    main()
