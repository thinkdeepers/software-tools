#!/usr/bin/env bash
# 护眼卫士启动脚本
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

export PATH="$HOME/.local/bin:$PATH"

if ! python3 -c "import PyQt6" 2>/dev/null; then
  echo "正在安装依赖..."
  pip install -q -r requirements.txt
fi

exec python3 src/main.py "$@"
