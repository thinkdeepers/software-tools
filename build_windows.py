#!/usr/bin/env python3
"""在 Windows 上把简压打包为单个 exe。

使用方法（Windows）：
    pip install pyinstaller py7zr rarfile pyzipper
    python build_windows.py

生成的可执行文件位于 dist/简压.exe。
双击可打开图形界面；也支持命令行参数（供右键菜单调用）。
"""

import os
import subprocess
import sys
import urllib.request
from typing import Optional


UNRAR_URL = "https://www.rarlab.com/rar/unrarw64.exe"


def _ensure_unrar(root: str) -> Optional[str]:
    """确保 vendor/UnRAR.exe 存在；缺失时尝试下载。"""
    vendor = os.path.join(root, "vendor")
    os.makedirs(vendor, exist_ok=True)
    target = os.path.join(vendor, "UnRAR.exe")
    if os.path.isfile(target) and os.path.getsize(target) > 1000:
        return target

    print(f"下载 UnRAR：{UNRAR_URL}")
    tmp = target + ".partial"
    try:
        urllib.request.urlretrieve(UNRAR_URL, tmp)
        os.replace(tmp, target)
    except Exception as exc:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass
        print(f"警告：无法下载 UnRAR（{exc}）。打包后将无法解压 rar。", file=sys.stderr)
        return None
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

    # 把图标一并打包，供运行时设置窗口图标（与 exe 图标一致）。
    for res in ("app.ico", "app.png"):
        res_path = os.path.join(assets, res)
        if os.path.exists(res_path):
            cmd += ["--add-data", f"{res_path}{os.pathsep}."]

    # 捆绑 UnRAR，供 rarfile 解压 .rar
    if unrar:
        cmd += ["--add-binary", f"{unrar}{os.pathsep}."]
        # 同时放到 vendor 子目录，兼容 resource 查找逻辑。
        cmd += ["--add-binary", f"{unrar}{os.pathsep}vendor"]

    cmd.append(entry)

    print("运行：", " ".join(cmd))
    return subprocess.call(cmd)


if __name__ == "__main__":
    raise SystemExit(main())
