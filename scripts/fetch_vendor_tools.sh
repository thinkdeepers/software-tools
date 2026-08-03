#!/usr/bin/env bash
# 下载 Windows 版 UnRAR（RAR 解压必需），放到 vendor/UnRAR.exe。
# UnRAR 为 Alexander Roshal 提供的免费解压工具，允许随软件分发。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR="$ROOT/vendor"
mkdir -p "$VENDOR"

UNRAR_URL="${UNRAR_URL:-https://www.rarlab.com/rar/unrarw64.exe}"
TARGET="$VENDOR/UnRAR.exe"

if [ -f "$TARGET" ] && [ "${FORCE_FETCH:-0}" != "1" ]; then
  echo "[vendor] 已存在：$TARGET"
  exit 0
fi

echo "[vendor] 下载 UnRAR：$UNRAR_URL"
tmp="$VENDOR/.UnRAR.exe.partial"
for i in 1 2 3 4; do
  if curl -fsSL -o "$tmp" "$UNRAR_URL"; then
    mv -f "$tmp" "$TARGET"
    echo "[vendor] 已保存：$TARGET ($(wc -c < "$TARGET") bytes)"
    exit 0
  fi
  echo "[vendor] 下载失败（第 $i 次），重试…"
  sleep $((i * 4))
done

echo "[vendor] 下载 UnRAR 失败" >&2
exit 1
