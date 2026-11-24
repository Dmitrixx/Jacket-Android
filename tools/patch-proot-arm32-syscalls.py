#!/usr/bin/env python3
"""Patch the pinned PRoot sources for Android ARM EABI seccomp compatibility."""

from __future__ import annotations

import sys
from pathlib import Path


def patch_once(path: Path, edits: list[tuple[str, str]]) -> None:
    source = path.read_text(encoding="utf-8")
    original = source
    for old, new in edits:
        if new in source:
            continue
        matches = source.count(old)
        if matches != 1:
            raise SystemExit(
                f"{path}: expected one patch anchor, found {matches}: {old!r}"
            )
        source = source.replace(old, new, 1)
    if source != original:
        path.write_text(source, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch-proot-arm32-syscalls.py <oonid-pr-root>")
    root = Path(sys.argv[1])
    source = root / "src/proot/src"

    patch_once(
        source / "syscall/sysnums.list",
        [
            (
                "SYSNUM(fchmodat)\nSYSNUM(fchown)",
                "SYSNUM(fchmodat)\nSYSNUM(fchmodat2)\nSYSNUM(fchown)",
            )
        ],
    )

    patch_once(
        source / "syscall/sysnums-arm.h",
        [
            (
                "\t[ 439 ] = PR_faccessat2,\n\t[ 435 ] = PR_clone3,",
                "\t[ 435 ] = PR_clone3,\n\t[ 439 ] = PR_faccessat2,\n\t[ 452 ] = PR_fchmodat2,",
            )
        ],
    )

    patch_once(
        source / "syscall/sysnums-arm64.h",
        [
            (
                "\t[ 439 ] = PR_faccessat2,\n\t[ 440 ] = PR_process_madvise,",
                "\t[ 439 ] = PR_faccessat2,\n\t[ 440 ] = PR_process_madvise,\n\t[ 452 ] = PR_fchmodat2,",
            )
        ],
    )

    patch_once(
        source / "syscall/enter.c",
        [
            (
                "\tcase PR_fchmodat:\n\tcase PR_faccessat:",
                "\tcase PR_fchmodat:\n\tcase PR_fchmodat2:\n\tcase PR_faccessat:",
            )
        ],
    )

    patch_once(
        source / "tracee/seccomp.c",
        [
            (
                "#if defined(ARCH_ARM64)\n#define SYSNUM_RSEQ 293\n#endif",
                "#if defined(ARCH_ARM64)\n#define SYSNUM_RSEQ 293\n#elif defined(ARCH_ARM_EABI)\n#define SYSNUM_RSEQ 398\n#endif",
            ),
            (
                "\tcase PR_set_robust_list:\n\t\tset_result_after_seccomp(tracee, -ENOSYS);",
                "\tcase PR_fchmodat2:\n"
                "\t\t/* fchmodat2(dirfd, path, mode, 0) is equivalent to the\n"
                "\t\t * older fchmodat syscall. Non-zero flags need semantics the\n"
                "\t\t * older syscall cannot represent, so fail safely with ENOSYS. */\n"
                "\t\tif (peek_reg(tracee, ORIGINAL, SYSARG_4) != 0) {\n"
                "\t\t\tset_result_after_seccomp(tracee, -ENOSYS);\n"
                "\t\t\tbreak;\n"
                "\t\t}\n"
                "\t\tset_sysnum(tracee, PR_fchmodat);\n"
                "\t\trestart_syscall_after_seccomp(tracee);\n"
                "\t\tbreak;\n"
                "\n"
                "\tcase PR_set_robust_list:\n\t\tset_result_after_seccomp(tracee, -ENOSYS);",
            ),
        ],
    )

    print("ARM32 PRoot syscall compatibility patches applied and verified.")


if __name__ == "__main__":
    main()
