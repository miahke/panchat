#!/usr/bin/env python3
"""Check the required native engines in an APK; no Android device or personal data required."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

COMPONENTS = ("libarti_android.so", "libbitchat_pt.so", "libbitchat_vk.so")
ARCHITECTURES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62), "x86": (1, 3)}


def verify_elf(data, abi, name):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError(f"{abi}/{name}: invalid ELF")
    bits, machine = ARCHITECTURES[abi]
    if data[4] != bits or struct.unpack_from("<H", data, 18)[0] != machine:
        raise ValueError(f"{abi}/{name}: incorrect architecture")
    if struct.unpack_from("<H", data, 16)[0] != 3:
        raise ValueError(f"{abi}/{name}: must be position independent")
    phoff = struct.unpack_from("<Q" if bits == 2 else "<I", data, 32 if bits == 2 else 28)[0]
    phsize, phcount = struct.unpack_from("<HH", data, 54 if bits == 2 else 42)
    minimum = 56 if bits == 2 else 32
    if phsize < minimum or phcount < 1 or phcount > 256 or phoff + phsize * phcount > len(data):
        raise ValueError(f"{abi}/{name}: invalid program header")
    loads = 0
    for index in range(phcount):
        start = phoff + index * phsize
        kind = struct.unpack_from("<I", data, start)[0]
        if kind != 1:
            continue
        loads += 1
        alignment = struct.unpack_from("<Q" if bits == 2 else "<I", data, start + (48 if bits == 2 else 28))[0]
        if alignment < (16384 if bits == 2 else 4096):
            raise ValueError(f"{abi}/{name}: insufficient page alignment")
    if not loads:
        raise ValueError(f"{abi}/{name}: no loadable segment")
    # trimpath/remap flags must keep workstation paths out of distributed engines.
    if any(prefix in data for prefix in (b"/home/", b"/Users/", b"/workspace/")):
        raise ValueError(f"{abi}/{name}: contains a build-host path")
    return hashlib.sha256(data).hexdigest()


def verify(path, abis):
    result = {}
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        if any(name.endswith("/libbitchat_cups.so") for name in names):raise ValueError("Removed Cups engine found in APK")
        for abi in abis:
            if abi not in ARCHITECTURES:
                raise ValueError("Unsupported ABI")
            result[abi] = {}
            for name in COMPONENTS:
                entry = f"lib/{abi}/{name}"
                if entry not in names:
                    raise ValueError(f"Missing {entry}")
                result[abi][name] = verify_elf(apk.read(entry), abi, name)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", action="append", choices=ARCHITECTURES)
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, args.abi or list(ARCHITECTURES)), indent=2))
