"""Bake 4 pinned Inter / InterDisplay font slots into v3 PocketJS font atlas blobs.
No image generation; GTA pictures remain original Android assets.
"""
import base64
import json
import math
import os
from pathlib import Path
import struct
import PIL
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'pocket-ui/dependencies.lock.json').read_text())
assert PIL.__version__ == LOCK['pythonPillow'], 'Use Pillow==' + LOCK['pythonPillow']
source = Path(os.environ.get('POCKET_SOURCE', ROOT / '.pocket-build/sources/pocketjs'))
fonts_dir = source / 'assets/fonts'

# slot, filename, logical_px, is_bold, baseline_frac, line_pad
SLOTS = [
    (0, 'Inter-Regular.ttf', 11, 0),
    (1, 'Inter-Bold.ttf', 14, 1),
    (2, 'InterDisplay-Regular.ttf', 21, 0),
    (3, 'InterDisplay-Bold.ttf', 25, 1),
]
DENSITY = 2
CHARS = list(range(32, 127)) + [0xFFFD]
# gid 0 must be tofu, cmap itself must be sorted by Unicode scalar.
GLYPHS = [0xFFFD] + list(range(32, 127))


def bake_atlas(slot: int, font_file: str, logical_px: int, bold_flag: int) -> bytes:
    font = ImageFont.truetype(str(fonts_dir / font_file), logical_px * DENSITY)
    advances = {cp: max(1, round(font.getlength(chr(cp)) / DENSITY)) for cp in CHARS}
    cw = max(advances.values()) + 2
    ch = int(math.ceil(logical_px * 1.45))
    baseline = int(math.ceil(logical_px * 1.08))
    line = ch
    header = struct.pack('<IHH8B', 0x41464344, 3, len(CHARS), cw, ch, baseline, line, slot, bold_flag, DENSITY, 0)
    cmap, pixels = bytearray(), bytearray()
    for cp in CHARS:
        gid = GLYPHS.index(cp)
        cmap.extend(struct.pack('<IHBB', cp, gid, min(255, advances[cp]), 0))
    for cp in GLYPHS:
        image = Image.new('L', (cw * DENSITY, ch * DENSITY))
        ImageDraw.Draw(image).text((0, baseline * DENSITY), chr(cp), font=font, fill=255, anchor='ls')
        pixels.extend(image.tobytes())
    return bytes(header + cmap + pixels)


out = ROOT / '.pocket-build/assets/pocket'
out.mkdir(parents=True, exist_ok=True)
atlases = [base64.b64encode(bake_atlas(slot, fn, px, bold)).decode('ascii') for slot, fn, px, bold in SLOTS]
bundle = (
    '// Generated from pinned v0.12.0 host contract; no DOM or WebView.\n'
    '(function(){var k="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";\n'
    'function b64(s){var n=s.length,p=s[n-1]==="="?(s[n-2]==="="?2:1):0,o=new Uint8Array((n*3)/4-p),j=0;\n'
    'for(var i=0;i<n;i+=4){var a=k.indexOf(s[i]),b=k.indexOf(s[i+1]),c=k.indexOf(s[i+2]),d=k.indexOf(s[i+3]);\n'
    'var v=(a<<18)|(b<<12)|((c<0?0:c)<<6)|(d<0?0:d);o[j++]=(v>>16)&255;if(c>=0)o[j++]=(v>>8)&255;if(d>=0)o[j++]=v&255;}\n'
    'return o.buffer;}\n'
    + ''.join(f'ui.loadFontAtlas(b64("{encoded}"));\n' for encoded in atlases)
    + '})();\n'
)
bundle += (ROOT / 'pocket-ui/src/top.js').read_text()
(out / 'top.js').write_text(bundle)
print(f'Built {out / "top.js"}: {len(bundle)} bytes')
