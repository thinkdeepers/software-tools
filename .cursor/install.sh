#!/usr/bin/env bash
# 简压 云端 Agent 环境安装脚本。
#
# 目标：让云端 Agent 开箱即可打包 Windows 安装程序。
#   - 系统：wine / wine32 / wine64 / xvfb / python3-tk
#   - Wine 内：Windows 版 Python 3.12（含 tcl/tk）+ PyInstaller
#   - Wine 内：Inno Setup 6（提供 ISCC.exe）
#   - 主机：pytest（+ Pillow 用于重新生成图标）
#
# 脚本可重复执行（幂等）：已安装的步骤会被跳过。
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export WINEPREFIX="${WINEPREFIX:-$HOME/.wine-jianya}"
export WINEARCH=win64
export WINEDEBUG=-all

PY_VERSION="3.12.7"
PY_URL="https://www.python.org/ftp/python/${PY_VERSION}/python-${PY_VERSION}-amd64.exe"
IS_URL="https://github.com/jrsoftware/issrc/releases/download/is-6_7_3/innosetup-6.7.3.exe"

PYEXE="$WINEPREFIX/drive_c/Python312/python.exe"
ISCC="$WINEPREFIX/drive_c/Program Files (x86)/Inno Setup 6/ISCC.exe"

CACHE="$HOME/.cache/jianya-setup"
mkdir -p "$CACHE"

log() { echo "[jianya-setup] $*"; }

# 带重试的下载
fetch() {
  local url="$1" out="$2" i
  for i in 1 2 3 4; do
    if curl -fsSL -o "$out" "$url"; then return 0; fi
    log "下载失败（第 $i 次），重试：$url"
    sleep $((i * 4))
  done
  log "下载失败：$url"
  return 1
}

# 在伪终端中运行 Windows 命令：Wine 下的 Python 在 stdout 被重定向到管道/文件时
# 会报 init_sys_streams（WinError 6），分配 PTY 可规避该问题。
pty_run() {
  local cmd="$1"
  script -qefc "$cmd" /dev/null
}

# 1) 系统依赖
if ! command -v wine >/dev/null 2>&1 || ! command -v xvfb-run >/dev/null 2>&1; then
  log "安装系统依赖：wine / wine32 / wine64 / xvfb / python3-tk ..."
  sudo dpkg --add-architecture i386
  sudo apt-get update -qq
  sudo apt-get install -y --no-install-recommends \
    wine wine32 wine64 xvfb python3-tk python3-pip ca-certificates curl
else
  log "系统依赖已就绪，跳过。"
fi

# 2) 主机测试依赖
log "安装/更新主机 Python 依赖：pytest, Pillow ..."
pip3 install --user --upgrade pytest Pillow >/dev/null 2>&1 || \
  pip3 install --break-system-packages --upgrade pytest Pillow >/dev/null 2>&1 || true

# 3) 初始化 Wine 前缀
if [ ! -d "$WINEPREFIX/drive_c/windows" ]; then
  log "初始化 Wine 前缀：$WINEPREFIX ..."
  pty_run "xvfb-run -a wineboot --init"
  # 等待 wineserver 落盘
  pty_run "xvfb-run -a wineserver -w" || true
else
  log "Wine 前缀已存在，跳过。"
fi

# 4) 安装 Windows 版 Python 3.12（含 tcl/tk + pip）
if [ ! -f "$PYEXE" ]; then
  log "下载并安装 Windows 版 Python ${PY_VERSION} ..."
  fetch "$PY_URL" "$CACHE/python-win.exe"
  pty_run "xvfb-run -a wine '$CACHE/python-win.exe' /quiet InstallAllUsers=1 PrependPath=1 Include_tcltk=1 Include_pip=1 Include_test=0 TargetDir=C:\\\\Python312"
else
  log "Wine 内 Python 已安装，跳过。"
fi

# 5) 在 Wine 内安装 PyInstaller
if ! pty_run "xvfb-run -a wine '$PYEXE' -c \"import PyInstaller\"" >/dev/null 2>&1; then
  log "在 Wine 内安装 PyInstaller ..."
  pty_run "xvfb-run -a wine '$PYEXE' -m pip install --upgrade pyinstaller"
else
  log "PyInstaller 已安装，跳过。"
fi

# 6) 在 Wine 内安装 Inno Setup 6
if [ ! -f "$ISCC" ]; then
  log "下载并安装 Inno Setup 6 ..."
  fetch "$IS_URL" "$CACHE/innosetup.exe"
  pty_run "xvfb-run -a wine '$CACHE/innosetup.exe' /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /NOICONS"
else
  log "Inno Setup 已安装，跳过。"
fi

log "环境准备完成。"
log "  Wine Python : $PYEXE"
log "  ISCC        : $ISCC"
log "打包命令      : bash scripts/build_windows_wine.sh"
