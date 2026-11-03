#!/usr/bin/env python3
"""Render code-defined PAN lettering for Android adaptive, themed and legacy icons."""
from pathlib import Path
import subprocess
import shutil

if not shutil.which("rsvg-convert"):
    raise SystemExit("Install librsvg2-bin to render the legacy launcher icons")

ROOT = Path(__file__).resolve().parents[2]
PATH = 'M29,66 V42 H36 C44,42 44,54 36,54 H29 M48,66 L55,42 L62,66 M51,57 H59 M69,66 V42 L82,66 V42'
VECTOR = '''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
<path android:pathData="%s" android:strokeColor="#FFFFFF" android:strokeWidth="4" android:strokeLineCap="square" android:strokeLineJoin="round" android:fillColor="#00000000" />
</vector>
''' % PATH
SVG = '''<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108"><rect width="108" height="108" rx="24" fill="#000"/><path d="%s" stroke="white" stroke-width="4" stroke-linecap="square" stroke-linejoin="round" fill="none"/></svg>''' % PATH
for module in ('app','wear'):
    res=ROOT/module/'src/main/res'
    for name in ('ic_launcher_foreground.xml','ic_launcher_monochrome.xml'):
        (res/'drawable'/name).write_text(VECTOR)
    for density,pixels in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
        directory=res/('mipmap-'+density)
        if not directory.is_dir(): continue
        subprocess.run(['rsvg-convert','--width',str(pixels),'--height',str(pixels),'--output',str(directory/'ic_launcher.png')],input=SVG.encode(),check=True)
        for obsolete in directory.glob('ic_launcher_adaptive_*.png'): obsolete.unlink()
(ROOT/'tools/branding/pan.svg').write_text(SVG+'\n')
