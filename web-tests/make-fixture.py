#!/usr/bin/env python3
"""Génère des paquets Opus bruts (20 ms, 48 kHz stéréo) à partir d'un signal test via ffmpeg/libopus.
Sortie : fixtures/sine.opuspk = suite de [uint16 BE longueur][paquet]. Nécessite ffmpeg."""
import os, struct, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
out_dir = os.path.join(here, "fixtures"); os.makedirs(out_dir, exist_ok=True)
ogg = os.path.join(out_dir, "sine.ogg")
dur = sys.argv[1] if len(sys.argv) > 1 else "6"
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={dur}",
                "-f", "lavfi", "-i", f"sine=frequency=660:sample_rate=48000:duration={dur}",
                "-filter_complex", "[0][1]amerge=inputs=2", "-ac", "2", "-c:a", "libopus", "-b:a", "128k",
                "-frame_duration", "20", ogg], check=True)
data = open(ogg, "rb").read()
packets, cur, pos = [], b"", 0
while pos < len(data):
    assert data[pos:pos+4] == b"OggS", "page Ogg invalide"
    nseg = data[pos+26]; segs = data[pos+27:pos+27+nseg]; body = pos + 27 + nseg
    for s in segs:
        cur += data[body:body+s]; body += s
        if s < 255: packets.append(cur); cur = b""
    pos = body
audio = packets[2:]  # saute OpusHead et OpusTags
pre_skip = struct.unpack("<H", packets[0][10:12])[0]
with open(os.path.join(out_dir, "sine.opuspk"), "wb") as f:
    for p in audio: f.write(struct.pack(">H", len(p)) + p)
print(f"{len(audio)} paquets, pre-skip {pre_skip}")
