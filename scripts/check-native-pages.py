#!/usr/bin/env python3
"""Inspect shipped arm64 ELF PT_LOAD alignment; runtime 16 KB testing remains required."""
import json
import struct
import sys
import zipfile

path = sys.argv[1]
results = []
with zipfile.ZipFile(path) as archive:
    for name in archive.namelist():
        if '/arm64-v8a/' not in '/' + name or not name.endswith('.so'):
            continue
        data = archive.read(name)
        if data[:6] != b'\x7fELF\x02\x01':
            raise SystemExit('Unexpected arm64 ELF format')
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        loads = []
        for index in range(count):
            entry = struct.unpack_from('<IIQQQQQQ', data, offset + index * size)
            if entry[0] == 1:
                alignment = entry[7]
                if alignment < 16384 or entry[2] % 16384 != entry[3] % 16384:
                    raise SystemExit('Arm64 native library does not satisfy 16 KB load alignment: ' + name)
                loads.append(alignment)
        if not loads:
            raise SystemExit('Missing ELF load segments: ' + name)
        results.append({'library': name, 'loadAlignments': loads})
if not results:
    raise SystemExit('No arm64 native libraries found')
print(json.dumps({'artifact': path, 'arm64Elf16KB': results}, indent=2))
