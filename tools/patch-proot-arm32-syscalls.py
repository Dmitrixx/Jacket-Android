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
                "\tcase PR_fchmodat2: {\n"
                "\t\tword_t flags = peek_reg(tracee, CURRENT, SYSARG_4);\n"
                "\t\tdirfd = peek_reg(tracee, CURRENT, SYSARG_1);\n"
                "\t\tstatus = get_sysarg_path(tracee, path, SYSARG_2);\n"
                "\t\tif (status < 0) break;\n"
                "\t\t/* AT_EMPTY_PATH acts on dirfd itself, not on a pathname.\n"
                "\t\t * Leave the empty string untouched so the kernel or the\n"
                "\t\t * seccomp fallback can apply the descriptor semantics. */\n"
                "\t\tif ((flags & AT_EMPTY_PATH) != 0 && path[0] == '\\0') break;\n"
                "\t\tstatus = translate_path2(tracee, dirfd, path, SYSARG_2,\n"
                "\t\t\t(flags & AT_SYMLINK_NOFOLLOW) ? SYMLINK : REGULAR);\n"
                "\t\tbreak;\n"
                "\t}\n"
                "\n"
                "\tcase PR_fchmodat:\n\tcase PR_faccessat:",
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
                "#if defined(ARCH_ARM64)\n\t\tif (get_abi(tracee) == ABI_DEFAULT && kernel_num == SYSNUM_RSEQ) {",
                "#if defined(ARCH_ARM64) || defined(ARCH_ARM_EABI)\n\t\tif (get_abi(tracee, CURRENT) == ABI_DEFAULT && kernel_num == SYSNUM_RSEQ) {",
            ),
            (
                "\tcase PR_set_robust_list:\n\t\tset_result_after_seccomp(tracee, -ENOSYS);",
                "\tcase PR_fchmodat2: {\n"
                "\t\tword_t flags = peek_reg(tracee, ORIGINAL, SYSARG_4);\n"
                "\t\tif (flags == AT_EMPTY_PATH) {\n"
                "\t\t\tchar path_first_byte = 1;\n"
                "\t\t\tret = read_data(tracee, &path_first_byte,\n"
                "\t\t\t\tpeek_reg(tracee, ORIGINAL, SYSARG_2), 1);\n"
                "\t\t\tif (ret < 0) {\n"
                "\t\t\t\tset_result_after_seccomp(tracee, ret);\n"
                "\t\t\t\tbreak;\n"
                "\t\t\t}\n"
                "\t\t\tif (path_first_byte == '\\0') {\n"
                "\t\t\t\tint dirfd = (int)peek_reg(tracee, ORIGINAL, SYSARG_1);\n"
                "\t\t\t\tchar proc_path[64];\n"
                "\t\t\t\tif (dirfd == AT_FDCWD)\n"
                "\t\t\t\t\tsnprintf(proc_path, sizeof(proc_path), \".\");\n"
                "\t\t\t\telse if (dirfd < 0) {\n"
                "\t\t\t\t\tset_result_after_seccomp(tracee, -EBADF);\n"
                "\t\t\t\t\tbreak;\n"
                "\t\t\t\t}\n"
                "\t\t\t\telse\n"
                "\t\t\t\t\tsnprintf(proc_path, sizeof(proc_path), \"/proc/self/fd/%d\", dirfd);\n"
                "\t\t\t\tword_t guest_path = alloc_mem(tracee, strlen(proc_path) + 1);\n"
                "\t\t\t\tif (guest_path == 0) {\n"
                "\t\t\t\t\tset_result_after_seccomp(tracee, -ENOMEM);\n"
                "\t\t\t\t\tbreak;\n"
                "\t\t\t\t}\n"
                "\t\t\t\tret = write_data(tracee, guest_path, proc_path, strlen(proc_path) + 1);\n"
                "\t\t\t\tif (ret < 0) {\n"
                "\t\t\t\t\tset_result_after_seccomp(tracee, ret);\n"
                "\t\t\t\t\tbreak;\n"
                "\t\t\t\t}\n"
                "\t\t\t\tset_sysnum(tracee, PR_fchmodat);\n"
                "\t\t\t\tpoke_reg(tracee, SYSARG_1, AT_FDCWD);\n"
                "\t\t\t\tpoke_reg(tracee, SYSARG_2, guest_path);\n"
                "\t\t\t\tpoke_reg(tracee, SYSARG_4, 0);\n"
                "\t\t\t\trestart_syscall_after_seccomp(tracee);\n"
                "\t\t\t\tbreak;\n"
                "\t\t\t}\n"
                "\t\t\t/* AT_EMPTY_PATH is ignored for non-empty pathnames. */\n"
                "\t\t} else if (flags != 0) {\n"
                "\t\t\tfprintf(stderr, \"PRoot: fchmodat2 flags=0x%lx not emulated; returning ENOSYS\\n\",\n"
                "\t\t\t\t(unsigned long)flags);\n"
                "\t\t\tset_result_after_seccomp(tracee, -ENOSYS);\n"
                "\t\t\tbreak;\n"
                "\t\t}\n"
                "\t\tset_sysnum(tracee, PR_fchmodat);\n"
                "\t\tpoke_reg(tracee, SYSARG_4, 0);\n"
                "\t\trestart_syscall_after_seccomp(tracee);\n"
                "\t\tbreak;\n"
                "\t}\n"
                "\n"
                "\tcase PR_set_robust_list:\n\t\tset_result_after_seccomp(tracee, -ENOSYS);",
            ),
        ],
    )

    print("ARM32 PRoot syscall compatibility patches applied and verified.")


if __name__ == "__main__":
    main()
