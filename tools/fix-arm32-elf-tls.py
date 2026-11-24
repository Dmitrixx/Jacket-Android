#!/usr/bin/env python3
"""Fix a static ARM32 executable's PT_TLS alignment for Android Bionic.

Android's 32-bit ARM Bionic requires the main executable PT_TLS segment to be
at least 32-byte aligned. NDK r27c currently emits PRoot with p_align=8 even
though the TLS segment virtual address is already 32-byte aligned.
"""

from __future__ import annotations

import argparse
import os
import struct
import tempfile
from pathlib import Path


ELFCLASS32 = 1
ELFDATA2LSB = 1
ELFDATA2MSB = 2
EM_ARM = 40
PT_TLS = 7
ARM_BIONIC_TLS_ALIGNMENT = 32
ELF32_EHDR_SIZE = 52
ELF32_PHDR_SIZE = 32


def inspect_and_fix(data: bytearray) -> tuple[int, int, int]:
    if len(data) < ELF32_EHDR_SIZE or data[:4] != b"\x7fELF":
        raise ValueError("input is not a valid ELF file")
    if data[4] != ELFCLASS32:
        raise ValueError("expected an ELF32 executable")
    if data[5] == ELFDATA2LSB:
        endian = "<"
    elif data[5] == ELFDATA2MSB:
        endian = ">"
    else:
        raise ValueError("unsupported ELF byte order")

    machine = struct.unpack_from(endian + "H", data, 18)[0]
    if machine != EM_ARM:
        raise ValueError(f"expected EM_ARM (40), got e_machine={machine}")

    phoff = struct.unpack_from(endian + "I", data, 28)[0]
    phentsize = struct.unpack_from(endian + "H", data, 42)[0]
    phnum = struct.unpack_from(endian + "H", data, 44)[0]
    if phentsize < ELF32_PHDR_SIZE:
        raise ValueError(f"invalid ELF32 program-header size: {phentsize}")
    if phoff > len(data) or phnum * phentsize > len(data) - phoff:
        raise ValueError("ELF program-header table is outside the file")

    tls_headers: list[tuple[int, int, int, int]] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type = struct.unpack_from(endian + "I", data, offset)[0]
        if p_type != PT_TLS:
            continue
        p_offset, p_vaddr = struct.unpack_from(endian + "II", data, offset + 4)
        p_align = struct.unpack_from(endian + "I", data, offset + 28)[0]
        tls_headers.append((offset, p_offset, p_vaddr, p_align))

    if len(tls_headers) != 1:
        raise ValueError(f"expected exactly one PT_TLS segment, found {len(tls_headers)}")

    phdr_offset, p_offset, p_vaddr, old_align = tls_headers[0]
    if old_align == 0 or old_align & (old_align - 1):
        raise ValueError(f"PT_TLS has invalid non-power-of-two alignment {old_align}")

    # Bionic's newer diagnostic also checks skew (p_vaddr % p_align).
    # Require a zero-skew 32-byte boundary before changing p_align; never
    # mutate an ELF whose TLS layout would remain incompatible.
    if p_vaddr % ARM_BIONIC_TLS_ALIGNMENT != 0:
        raise ValueError(
            "PT_TLS virtual address is not 32-byte aligned "
            f"(p_vaddr=0x{p_vaddr:x}, skew={p_vaddr % ARM_BIONIC_TLS_ALIGNMENT})"
        )
    if p_offset % ARM_BIONIC_TLS_ALIGNMENT != p_vaddr % ARM_BIONIC_TLS_ALIGNMENT:
        raise ValueError(
            "PT_TLS file and virtual addresses are incongruent at 32-byte alignment "
            f"(p_offset=0x{p_offset:x}, p_vaddr=0x{p_vaddr:x})"
        )

    if old_align < ARM_BIONIC_TLS_ALIGNMENT:
        struct.pack_into(endian + "I", data, phdr_offset + 28, ARM_BIONIC_TLS_ALIGNMENT)
        new_align = ARM_BIONIC_TLS_ALIGNMENT
    else:
        new_align = old_align
        if p_vaddr % new_align != 0 or p_offset % new_align != p_vaddr % new_align:
            raise ValueError(
                "PT_TLS alignment is >=32 but has non-zero skew "
                f"(alignment={new_align}, p_vaddr=0x{p_vaddr:x})"
            )

    return old_align, new_align, p_vaddr


def patch_file(path: Path) -> None:
    data = bytearray(path.read_bytes())
    old_align, new_align, vaddr = inspect_and_fix(data)
    if old_align != new_align:
        fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, path.stat().st_mode)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    # Re-read the saved file so the build never packages an unverified patch.
    verified = bytearray(path.read_bytes())
    _, verified_align, verified_vaddr = inspect_and_fix(verified)
    if verified_align < ARM_BIONIC_TLS_ALIGNMENT or verified_vaddr % verified_align != 0:
        raise RuntimeError("PT_TLS alignment verification failed")
    print(
        f"ARM32 Bionic PT_TLS: alignment {old_align} -> {verified_align}, "
        f"skew=0, p_vaddr=0x{verified_vaddr:x}"
    )


def run_self_test() -> None:
    data = bytearray(ELF32_EHDR_SIZE + ELF32_PHDR_SIZE)
    data[:4] = b"\x7fELF"
    data[4] = ELFCLASS32
    data[5] = ELFDATA2LSB
    struct.pack_into("<H", data, 18, EM_ARM)
    struct.pack_into("<I", data, 28, ELF32_EHDR_SIZE)
    struct.pack_into("<H", data, 42, ELF32_PHDR_SIZE)
    struct.pack_into("<H", data, 44, 1)
    phdr = ELF32_EHDR_SIZE
    struct.pack_into("<I", data, phdr, PT_TLS)
    struct.pack_into("<II", data, phdr + 4, 0x71040, 0x89040)
    struct.pack_into("<I", data, phdr + 28, 8)
    old_align, new_align, vaddr = inspect_and_fix(data)
    assert (old_align, new_align, vaddr) == (8, 32, 0x89040)
    assert struct.unpack_from("<I", data, phdr + 28)[0] == 32

    bad = bytearray(data)
    struct.pack_into("<I", bad, phdr + 8, 0x89048)
    try:
        inspect_and_fix(bad)
    except ValueError as error:
        assert "not 32-byte aligned" in str(error)
    else:
        raise AssertionError("misaligned PT_TLS virtual address was not rejected")
    print("ARM32 PT_TLS fixer self-test: PASS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf", nargs="?", type=Path, help="ARM32 ELF executable to patch")
    parser.add_argument("--self-test", action="store_true", help="run the synthetic ELF tests")
    args = parser.parse_args()
    if args.self_test:
        run_self_test()
    if args.elf is not None:
        patch_file(args.elf)
    if not args.self_test and args.elf is None:
        parser.error("provide an ELF file or use --self-test")


if __name__ == "__main__":
    main()
