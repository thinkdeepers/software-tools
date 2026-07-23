#!/usr/bin/env bash
# 在 Linux + Wine 环境下打包简压：生成 dist/简压.exe 与 release/简压安装程序.exe。
# 需先运行 .cursor/install.sh 准备好 Wine + Python + PyInstaller + Inno Setup。
set -euo pipefail

export WINEPREFIX="${WINEPREFIX:-$HOME/.wine-jianya}"
export WINEARCH=win64
export WINEDEBUG=-all
# 让 Wine 下的 Python 用 UTF-8 输出，避免中文触发 charmap 编码错误。
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PYEXE="$WINEPREFIX/drive_c/Python312/python.exe"
ISCC="$WINEPREFIX/drive_c/Program Files (x86)/Inno Setup 6/ISCC.exe"

if [ ! -f "$PYEXE" ]; then
  echo "未找到 Wine 内的 Python，请先运行：bash .cursor/install.sh" >&2
  exit 1
fi

# 分配 PTY，规避 Wine 下 Python 的 stdio 重定向问题。
pty_run() { script -qefc "$1" /dev/null; }

echo "[build] 用 PyInstaller 打包 exe ..."
rm -rf build dist
pty_run "xvfb-run -a wine '$PYEXE' build_windows.py"

if [ ! -f "$ISCC" ]; then
  echo "[build] 未找到 Inno Setup，跳过安装程序制作（仅生成 dist/简压.exe）。" >&2
  exit 0
fi

echo "[build] 用 Inno Setup 制作安装程序 ..."
pty_run "xvfb-run -a wine '$ISCC' installer/jianya.iss"

echo "[build] 完成："
ls -la dist/*.exe release/*.exe 2>/dev/null || true
