#!/usr/bin/env python3
"""在 Windows 上把简压打包为单个 exe。

使用方法（Windows）：
    pip install pyinstaller
    python build_windows.py

生成的可执行文件位于 dist/简压.exe。
双击可打开图形界面；也支持命令行参数（供右键菜单调用）。
"""

import os
import subprocess
import sys


def main() -> int:
    root = os.path.dirname(os.path.abspath(__file__))
    entry = os.path.join(root, "main.py")

    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        print("未检测到 PyInstaller，请先运行：pip install pyinstaller", file=sys.stderr)
        return 1

    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onefile",
        "--windowed",          # 不显示控制台窗口
        "--name", "简压",
        "--paths", os.path.join(root, "src"),
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

    cmd.append(entry)

    print("运行：", " ".join(cmd))
    return subprocess.call(cmd)


if __name__ == "__main__":
    raise SystemExit(main())
