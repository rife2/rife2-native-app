#!/usr/bin/env python3
"""Rewrites the platform that object files say they were built for, in place, to make stand-ins
for iOS of static libraries that only exist for macOS. Both run the same processor instructions.

    retag.py ios|iossim file.o ...
"""
import struct, sys
PLATFORM = {'ios': 2, 'iossim': 7}[sys.argv[1]]
MINOS, SDK = 0x00110000, 0x001B0100
# what LC_BUILD_VERSION holds: iOS 17.0 as the oldest system and 27.1 as the SDK
done = 0
for path in sys.argv[2:]:
    data = bytearray(open(path, 'rb').read())
    if len(data) < 32 or struct.unpack_from('<I', data, 0)[0] != 0xfeedfacf:
        continue
    ncmds = struct.unpack_from('<I', data, 16)[0]
    offset = 32
    for _ in range(ncmds):
        cmd, size = struct.unpack_from('<II', data, offset)
        if cmd == 0x32:
            struct.pack_into('<III', data, offset + 8, PLATFORM, MINOS, SDK)
            done += 1
        offset += size
    open(path, 'wb').write(data)
print(done)
