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

# Three original compact mono loops. Fade each beat at both ends to avoid boundary clicks.
for name, notes, beat, decay, overtone, attack_ms in (
    ("morning_bell", (660, 880, 660, 1047), .49, .062, .18, 16),
    ("morning_pulse", (587, 784, 988, 784, 988, 1175), .32, .045, .22, 13),
    ("morning_signal", (880, 1175, 880, 1175, 988, 1319, 988, 1319), .25, .035, .27, 11),
):
    rate = 16000
    frames = bytearray()
    for i in range(round(len(notes) * beat * rate)):
        time = i / rate
        note = min(len(notes) - 1, int(time / beat))
        local = time - note * beat
        envelope = min(1, local / (attack_ms / 1000)) * min(1, (beat - local) / .028) * math.exp(-local / (decay * 3))
        fundamental = math.tau * notes[note] * local
        signal = math.sin(fundamental) + overtone * math.sin(2 * fundamental)
        frames += struct.pack('<h', int(22000 * envelope * signal / (1 + overtone)))
    with wave.open(str(out.with_name(name + ".wav")), 'wb') as file:
        file.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
        file.writeframes(frames)
