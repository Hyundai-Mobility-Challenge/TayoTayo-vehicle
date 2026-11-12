#!/usr/bin/env python3
"""Generate an original synthetic test melody. No downloaded/copyrighted song is used.

The generated WAV and this score may be used, modified, and distributed with the PoC.
This is a short test signal, not a nursery-rhyme recording or a human performance.
"""
import math
from pathlib import Path
import struct
import wave

RATE = 22050
SCORE = [(60, .30), (67, .20), (64, .40), (69, .30), (62, .20), (65, .50),
         (0, .20), (67, .20), (71, .30), (65, .40), (64, .20), (62, .30),
         (60, .60), (0, .60)]
target = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/test_melody.wav"
target.parent.mkdir(parents=True, exist_ok=True)
frames = bytearray()
for midi, seconds in SCORE:
    samples = int(RATE * seconds)
    frequency = 440 * 2 ** ((midi - 69) / 12) if midi else 0
    for i in range(samples):
        t = i / RATE
        envelope = min(1, t / .025, (seconds - t) / .045)
        value = (.7 * math.sin(2 * math.pi * frequency * t)
                 + .2 * math.sin(4 * math.pi * frequency * t)) if midi else 0
        frames += struct.pack("<h", int(32767 * .22 * envelope * value))
with wave.open(str(target), "wb") as output:
    output.setnchannels(1)
    output.setsampwidth(2)
    output.setframerate(RATE)
    output.writeframes(frames)
print(f"{target}: {len(frames) // 2 / RATE:.2f}s, mono PCM16 {RATE}Hz")
