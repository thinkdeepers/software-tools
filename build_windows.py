#!/usr/bin/env python3
"""在 Windows 上把简压打包为单个 exe。

使用方法（Windows）：
    pip install pyinstaller py7zr rarfile pyzipper
    python build_windows.py

生成的可执行文件位于 dist/简压.exe。
双击可打开图形界面；也支持命令行参数（供右键菜单调用）。
"""

from __future__ import annotations

import os
import struct
import subprocess
import sys
import tempfile
import urllib.request
from typing import Optional


UNRAR_SFX_URL = "https://www.rarlab.com/rar/unrarw64.exe"


def _pe_subsystem(path: str) -> Optional[int]:
    """返回 PE Subsystem（3=控制台，2=GUI）；非 PE 返回 None。"""
    try:
        with open(path, "rb") as fh:
            header = fh.read(4096)
        if header[:2] != b"MZ" or len(header) < 0x40:
            return None
        e_lfanew = struct.unpack_from("<I", header, 0x3C)[0]
        need = e_lfanew + 24 + 70
        if need > len(header):
            with open(path, "rb") as fh:
                header = fh.read(need)
        magic = struct.unpack_from("<H", header, e_lfanew + 24)[0]
        if magic not in (0x10B, 0x20B):
            return None
        return struct.unpack_from("<H", header, e_lfanew + 24 + 68)[0]
    except Exception:
        return None


def _is_console_unrar(path: str) -> bool:
    return os.path.isfile(path) and _pe_subsystem(path) == 3


def _extract_unrar_from_sfx(sfx_path: str, target: str) -> bool:
    """从 rarlab 的 unrarw64.exe（SFX）中解出真正的控制台 UnRAR.exe。"""
    # 1) 优先用系统 unrar
    for tool in ("unrar", "UnRAR.exe", "unrar.exe"):
        try:
            with tempfile.TemporaryDirectory(prefix="jianya-unrar-") as tmp:
                rc = subprocess.call(
                    [tool, "x", "-y", "-idq", sfx_path, tmp + os.sep],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                if rc != 0:
                    continue
                for root, _dirs, files in os.walk(tmp):
                    for name in files:
                        if name.lower() == "unrar.exe":
                            src = os.path.join(root, name)
                            if _is_console_unrar(src):
                                os.replace(src, target)
                                return True
        except FileNotFoundError:
            continue
        except Exception:
            continue

    # 2) 回退：用已安装的 rarfile + 系统工具（若 SFX 可被识别）
    try:
        import rarfile  # type: ignore

        with tempfile.TemporaryDirectory(prefix="jianya-unrar-") as tmp:
            with rarfile.RarFile(sfx_path) as rf:
                rf.extractall(tmp)
            for root, _dirs, files in os.walk(tmp):
                for name in files:
                    if name.lower() == "unrar.exe":
                        src = os.path.join(root, name)
                        if _is_console_unrar(src):
                            os.replace(src, target)
                            return True
    except Exception:
        pass

    return False


def _ensure_unrar(root: str) -> Optional[str]:
    """确保 vendor/UnRAR.exe 是控制台版；若是 SFX 则自动解包替换。"""
    vendor = os.path.join(root, "vendor")
    os.makedirs(vendor, exist_ok=True)
    target = os.path.join(vendor, "UnRAR.exe")

    if _is_console_unrar(target):
        return target

    if os.path.isfile(target):
        print("检测到 vendor/UnRAR.exe 不是控制台版（可能是 SFX），重新获取…")

    print(f"下载 UnRAR SFX：{UNRAR_SFX_URL}")
    sfx = os.path.join(vendor, ".unrarw64.sfx")
    try:
        urllib.request.urlretrieve(UNRAR_SFX_URL, sfx)
    except Exception as exc:
        print(f"警告：无法下载 UnRAR（{exc}）。打包后将无法解压 rar。", file=sys.stderr)
        return target if os.path.isfile(target) else None

    ok = _extract_unrar_from_sfx(sfx, target)
    try:
        os.remove(sfx)
    except OSError:
        pass

    if not ok or not _is_console_unrar(target):
        print(
            "警告：未能从 SFX 解出控制台 UnRAR.exe。"
            "请在 Linux 上运行：bash scripts/fetch_vendor_tools.sh",
            file=sys.stderr,
        )
        return None

    print(f"已准备控制台 UnRAR：{target} ({os.path.getsize(target)} bytes)")
    return target


def main() -> int:
    root = os.path.dirname(os.path.abspath(__file__))
    entry = os.path.join(root, "main.py")

    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        print("未检测到 PyInstaller，请先运行：pip install pyinstaller", file=sys.stderr)
        return 1

    # 可选依赖：打包进 exe，避免用户环境缺少 7z/rar/加密支持。
    for mod in ("py7zr", "rarfile", "pyzipper"):
        try:
            __import__(mod)
        except ImportError:
            print(
                f"未检测到 {mod}，请先运行：pip install py7zr rarfile pyzipper",
                file=sys.stderr,
            )
            return 1

    unrar = _ensure_unrar(root)
    if not unrar:
        return 1

    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onefile",
        "--windowed",          # 不显示控制台窗口
        "--name", "简压",
        "--paths", os.path.join(root, "src"),
        "--hidden-import", "py7zr",
        "--hidden-import", "rarfile",
        "--hidden-import", "pyzipper",
        "--collect-all", "py7zr",
        "--collect-all", "pyzipper",
    ]

    assets = os.path.join(root, "assets")
    icon = os.path.join(assets, "app.ico")
    if os.path.exists(icon):
        cmd += ["--icon", icon]

    # DPI 感知清单：避免高分屏下文字/对话框被系统拉伸发虚
    manifest = os.path.join(assets, "app.manifest")
    if os.path.exists(manifest):
        cmd += ["--manifest", manifest]

    # 把图标一并打包，供运行时设置窗口图标（与 exe 图标一致）。
    for res in ("app.ico", "app.png"):
        res_path = os.path.join(assets, res)
        if os.path.exists(res_path):
            cmd += ["--add-data", f"{res_path}{os.pathsep}."]

    # 捆绑控制台 UnRAR，供 rarfile 解压 .rar（避免误用 SFX）
    cmd += ["--add-binary", f"{unrar}{os.pathsep}."]
    cmd += ["--add-binary", f"{unrar}{os.pathsep}vendor"]

    cmd.append(entry)

    print("运行：", " ".join(cmd))
    return subprocess.call(cmd)


if __name__ == "__main__":
    raise SystemExit(main())
