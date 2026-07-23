"""命令行入口。

用法：
    jianya                      # 打开极简图形界面
    jianya --compress FILE...   # 直接把文件/目录压缩为 zip（右键菜单调用）
    jianya --extract ARCHIVE    # 直接解压压缩包（右键菜单调用）
    jianya --install            # 注册 Windows 右键菜单
    jianya --uninstall          # 移除 Windows 右键菜单
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import List, Optional

from . import __version__
from . import core


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="jianya",
        description="简压 —— 免费、简洁、无广告的压缩/解压小工具",
    )
    parser.add_argument("--version", action="version", version=f"简压 {__version__}")

    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "-c", "--compress", nargs="+", metavar="PATH",
        help="将指定文件/目录压缩为 zip",
    )
    group.add_argument(
        "-x", "--extract", metavar="ARCHIVE",
        help="解压指定压缩包",
    )
    group.add_argument(
        "--install", action="store_true", help="注册 Windows 右键菜单",
    )
    group.add_argument(
        "--uninstall", action="store_true", help="移除 Windows 右键菜单",
    )

    parser.add_argument(
        "-o", "--output", metavar="PATH",
        help="指定输出路径（压缩时为 zip 文件，解压时为目标目录）",
    )
    parser.add_argument(
        "--gui", action="store_true", help="强制打开图形界面",
    )
    parser.add_argument(
        "--quiet", action="store_true",
        help="静默模式：不弹出提示框（供安装程序注册/移除右键菜单时使用）",
    )
    return parser


def _has_console() -> bool:
    """打包为 --windowed exe 时 sys.stdout/stderr 为 None。"""
    return sys.stdout is not None


def _console_progress(done: int, total: int, name: str) -> None:
    if not _has_console():
        return
    pct = int(done / total * 100) if total else 100
    sys.stdout.write(f"\r[{pct:3d}%] {name[:60]:<60}")
    sys.stdout.flush()
    if done >= total:
        sys.stdout.write("\n")


def _notify(title: str, message: str, error: bool = False) -> None:
    """有控制台时打印到终端；无控制台（右键菜单调用 exe）时弹窗提示。"""
    if _has_console():
        stream = sys.stderr if error else sys.stdout
        print(message, file=stream)
        return
    try:
        import tkinter as tk
        from tkinter import messagebox

        root = tk.Tk()
        root.withdraw()
        if error:
            messagebox.showerror(title, message)
        else:
            messagebox.showinfo(title, message)
        root.destroy()
    except Exception:
        pass


def _run_compress(paths: List[str], output: Optional[str]) -> int:
    try:
        result = core.compress_to_zip(paths, output=output, progress=_console_progress)
    except core.ArchiveError as exc:
        _notify("简压 - 压缩失败", f"错误：{exc}", error=True)
        return 1
    _notify("简压", f"已压缩到：{result}")
    return 0


def _run_extract(archive: str, output: Optional[str]) -> int:
    try:
        result = core.extract_archive(archive, output_dir=output, progress=_console_progress)
    except core.ArchiveError as exc:
        _notify("简压 - 解压失败", f"错误：{exc}", error=True)
        return 1
    _notify("简压", f"已解压到：{result}")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    if args.install or args.uninstall:
        from . import context_menu

        try:
            if args.install:
                context_menu.install()
                if not args.quiet:
                    _notify("简压", "右键菜单已注册。")
            else:
                context_menu.uninstall()
                if not args.quiet:
                    _notify("简压", "右键菜单已移除。")
            return 0
        except context_menu.ContextMenuError as exc:
            if not args.quiet:
                _notify("简压", f"错误：{exc}", error=True)
            return 1

    if args.compress:
        return _run_compress(args.compress, args.output)

    if args.extract:
        return _run_extract(args.extract, args.output)

    # 默认打开图形界面
    from . import gui

    return gui.launch()


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
