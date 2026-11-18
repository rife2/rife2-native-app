#!/usr/bin/env python3
# Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
# Licensed under the Apache License, Version 2.0 (the "License")
"""Rewrites the platform that object files say they were built for, in place, to make stand-ins
for iOS of static libraries that only exist for macOS, and for visionOS of the ones for iOS. All
of them run the same processor instructions.

    retag.py ios|iossim|vision|visionsim file.o ...
"""
import struct, sys
# what LC_BUILD_VERSION holds: the platform, the oldest system and the SDK, which are iOS 17.0
# with 27.1 and visionOS 26.0 with 27.0
PLATFORM, MINOS, SDK = {'ios': (2, 0x00110000, 0x001B0100), 'iossim': (7, 0x00110000, 0x001B0100),
                        'vision': (11, 0x001A0000, 0x001B0000), 'visionsim': (12, 0x001A0000, 0x001B0000)}[sys.argv[1]]
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
