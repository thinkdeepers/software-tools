"""命令行入口。

用法：
    jianya                      # 打开极简图形界面
    jianya ARCHIVE              # 打开压缩包预览（双击关联调用）
    jianya --open ARCHIVE       # 同上
    jianya --compress FILE...   # 直接把文件/目录压缩为 zip（右键菜单调用）
    jianya --extract ARCHIVE    # 直接解压压缩包（右键菜单调用）
    jianya --install            # 注册文件关联与右键菜单（默认用简压打开压缩包）
    jianya --uninstall          # 移除文件关联与右键菜单
"""

from __future__ import annotations

import argparse
import getpass
import sys
from pathlib import Path
from typing import List, Optional

from . import __version__
from . import core
from .dpi import enable_high_dpi
from .win_argv import get_unicode_argv


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
        "--open", metavar="ARCHIVE",
        help="打开压缩包预览界面",
    )
    group.add_argument(
        "--install", action="store_true",
        help="注册文件关联与右键菜单（压缩包显示简压图标并默认由简压打开）",
    )
    group.add_argument(
        "--uninstall", action="store_true",
        help="移除文件关联与右键菜单",
    )

    parser.add_argument(
        "archives",
        nargs="*",
        metavar="ARCHIVE",
        help="双击打开时传入的压缩包路径（进入预览）",
    )
    parser.add_argument(
        "-o", "--output", metavar="PATH",
        help="指定输出路径（压缩时为 zip 文件，解压时为目标目录）",
    )
    parser.add_argument(
        "-p", "--password", metavar="PASSWORD",
        help="压缩/解压时使用的密码",
    )
    parser.add_argument(
        "--gui", action="store_true", help="强制打开图形界面",
    )
    parser.add_argument(
        "--quiet", action="store_true",
        help="静默模式：不弹出提示框（供安装程序注册/移除文件关联时使用）",
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
    """有控制台时打印；无控制台时弹出 DPI 感知的清晰对话框。"""
    if _has_console():
        stream = sys.stderr if error else sys.stdout
        print(message, file=stream)
        return
    from .dialogs import show_alert

    show_alert(title, message, error=error)


def _prompt_password(prompt: str = "请输入密码：") -> Optional[str]:
    if _has_console():
        try:
            return getpass.getpass(prompt)
        except Exception:
            return None
    enable_high_dpi()
    try:
        import tkinter as tk
        from tkinter import simpledialog

        from .dpi import configure_tk_scaling

        root = tk.Tk()
        configure_tk_scaling(root)
        root.withdraw()
        value = simpledialog.askstring("简压", prompt, show="*", parent=root)
        root.destroy()
        return value
    except Exception:
        return None


def _run_compress(paths: List[str], output: Optional[str], password: Optional[str]) -> int:
    if _has_console():
        try:
            result = core.compress_to_zip(
                paths, output=output, progress=_console_progress, password=password
            )
        except core.ArchiveError as exc:
            _notify("简压 - 压缩失败", f"错误：{exc}", error=True)
            return 1
        tip = "（已加密）" if password else ""
        _notify("简压", f"已压缩到：{result}{tip}")
        return 0

    # 无控制台：显示进度条，完成后再提示
    from .dialogs import run_job_with_progress

    def worker(progress, finish_ok, finish_error) -> None:
        try:
            result = core.compress_to_zip(
                paths, output=output, progress=progress, password=password
            )
            tip = "（已加密）" if password else ""
            finish_ok("简压", f"已压缩到：\n{result}{tip}")
        except Exception as exc:
            finish_error("简压 - 压缩失败", str(exc))

    run_job_with_progress("简压 — 正在压缩", "正在压缩…", worker)
    return 0


def _run_extract(archive: str, output: Optional[str], password: Optional[str]) -> int:
    if _has_console():
        pwd = password
        for _attempt in range(3):
            try:
                result = core.extract_archive(
                    archive,
                    output_dir=output,
                    progress=_console_progress,
                    password=pwd,
                )
                _notify("简压", f"已解压到：{result}")
                return 0
            except core.PasswordRequiredError as exc:
                pwd = _prompt_password(f"{Path(archive).name}：{exc}\n请输入密码：")
                if pwd is None:
                    _notify("简压 - 解压失败", "已取消（需要密码）。", error=True)
                    return 1
            except core.ArchiveError as exc:
                _notify("简压 - 解压失败", f"错误：{exc}", error=True)
                return 1
        _notify("简压 - 解压失败", "密码不正确。", error=True)
        return 1

    # 无控制台：进度条（含动画）→ 100% → 再弹清晰的「已解压」
    from .dialogs import run_job_with_progress

    pwd = password
    if pwd is None:
        try:
            if core.archive_is_encrypted(archive):
                pwd = _prompt_password(f"{Path(archive).name} 已加密，请输入密码：")
                if pwd is None:
                    _notify("简压 - 解压失败", "已取消（需要密码）。", error=True)
                    return 1
        except Exception:
            pass

    def worker(progress, finish_ok, finish_error) -> None:
        try:
            result = core.extract_archive(
                archive,
                output_dir=output,
                progress=progress,
                password=pwd,
            )
            finish_ok("简压", f"已解压到：\n{result}")
        except core.PasswordRequiredError:
            finish_error(
                "简压 - 需要密码",
                "压缩包已加密。请右键重新解压，或在预览窗口中输入密码。",
            )
        except Exception as exc:
            finish_error("简压 - 解压失败", str(exc))

    run_job_with_progress("简压 — 正在解压", "正在解压，请稍候…", worker)
    return 0


def _run_open(archives: List[str]) -> int:
    from . import gui

    return gui.launch(open_archives=archives)


def main(argv: Optional[List[str]] = None) -> int:
    # 尽早声明 DPI，避免后续任何弹窗发虚。
    enable_high_dpi()

    # Windows 双击中文路径时，通过 GetCommandLineW 修正 argv 乱码。
    if argv is None:
        parse_argv = get_unicode_argv()[1:]
    else:
        parse_argv = list(argv)

    parser = _build_parser()
    args = parser.parse_args(parse_argv)

    if args.install or args.uninstall:
        from . import context_menu

        try:
            if args.install:
                context_menu.install()
                if not args.quiet:
                    _notify(
                        "简压",
                        "已设为默认打开程序：压缩包将显示简压图标，双击即可预览并解压。",
                    )
            else:
                context_menu.uninstall()
                if not args.quiet:
                    _notify("简压", "已取消默认打开，并移除右键菜单。")
            return 0
        except context_menu.ContextMenuError as exc:
            if not args.quiet:
                _notify("简压", f"错误：{exc}", error=True)
            return 1

    if args.compress:
        return _run_compress(args.compress, args.output, args.password)

    if args.extract:
        return _run_extract(args.extract, args.output, args.password)

    open_targets: List[str] = []
    if args.open:
        open_targets.append(args.open)
    for item in args.archives or []:
        if item not in open_targets:
            open_targets.append(item)

    # 位置参数若是压缩包则进入预览；否则提示。
    if open_targets:
        archives = [p for p in open_targets if core.is_archive(p) and Path(p).is_file()]
        missing = [p for p in open_targets if not Path(p).is_file()]
        unsupported = [
            p for p in open_targets if Path(p).is_file() and not core.is_archive(p)
        ]
        if missing and not archives:
            _notify("简压", f"文件不存在：{missing[0]}", error=True)
            return 1
        if unsupported and not archives:
            _notify("简压", f"不支持的压缩格式：{Path(unsupported[0]).name}", error=True)
            return 1
        if archives:
            return _run_open(archives)

    # 默认打开图形界面
    from . import gui

    return gui.launch()


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
