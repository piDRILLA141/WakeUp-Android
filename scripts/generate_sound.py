"""Original three-note alarm; only Python standard library. No external audio assets."""
import math, struct, wave
from pathlib import Path
out = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/dawn.wav'
rate = 22050
with wave.open(str(out), 'wb') as f:
    f.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
    for i in range(rate * 4):
        t = i / rate
        beat = t % 1
        freq = (523.25, 659.25, 783.99, 659.25)[int(t)]
        env = min(beat / .025, 1) * max(0, 1 - beat / .8)
        signal = .65 * env * (math.sin(2*math.pi*freq*t) + .18*math.sin(4*math.pi*freq*t))
        f.writeframesraw(struct.pack('<h', int(24000 * signal)))
