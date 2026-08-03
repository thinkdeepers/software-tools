#!/usr/bin/env bash
# 下载并解包 Windows 控制台版 UnRAR，放到 vendor/UnRAR.exe。
#
# 注意：rarlab 的 unrarw64.exe 是「自解压包」(SFX)，直接当 UnRAR 调用会弹出
# “WinRAR self-extracting archive”窗口。必须先解包得到真正的控制台 UnRAR.exe。
#
# UnRAR 为 Alexander Roshal 提供的免费解压工具，允许随软件分发。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR="$ROOT/vendor"
mkdir -p "$VENDOR"

UNRAR_URL="${UNRAR_URL:-https://www.rarlab.com/rar/unrarw64.exe}"
TARGET="$VENDOR/UnRAR.exe"

is_console_unrar() {
  local f="$1"
  python3 - "$f" <<'PY'
import struct, sys
path = sys.argv[1]
try:
    with open(path, "rb") as fh:
        data = fh.read(4096)
    if data[:2] != b"MZ" or len(data) < 0x40:
        raise SystemExit(1)
    e_lfanew = struct.unpack_from("<I", data, 0x3C)[0]
    if e_lfanew + 24 + 70 > len(data):
        with open(path, "rb") as fh:
            data = fh.read(e_lfanew + 256)
    magic = struct.unpack_from("<H", data, e_lfanew + 24)[0]
    # PE32 / PE32+ 的 Subsystem 均在 optional header 偏移 68
    subsystem = struct.unpack_from("<H", data, e_lfanew + 24 + 68)[0]
    # 3 = IMAGE_SUBSYSTEM_WINDOWS_CUI（控制台）；2 = GUI（常见于 SFX）
    raise SystemExit(0 if magic in (0x10B, 0x20B) and subsystem == 3 else 1)
except Exception:
    raise SystemExit(1)
PY
}

if [ -f "$TARGET" ] && [ "${FORCE_FETCH:-0}" != "1" ] && is_console_unrar "$TARGET"; then
  echo "[vendor] 已存在控制台 UnRAR：$TARGET"
  exit 0
fi

echo "[vendor] 下载 UnRAR SFX：$UNRAR_URL"
sfx="$VENDOR/.unrarw64.sfx"
for i in 1 2 3 4; do
  if curl -fsSL -o "$sfx" "$UNRAR_URL"; then
    break
  fi
  echo "[vendor] 下载失败（第 $i 次），重试…"
  sleep $((i * 4))
  if [ "$i" -eq 4 ]; then
    echo "[vendor] 下载 UnRAR 失败" >&2
    exit 1
  fi
done

tmpdir="$VENDOR/.unrar_extract"
rm -rf "$tmpdir"
mkdir -p "$tmpdir"

echo "[vendor] 从 SFX 解包真正的控制台 UnRAR.exe …"
if command -v unrar >/dev/null 2>&1; then
  unrar x -y -idq "$sfx" "$tmpdir/" >/dev/null
elif command -v unrar-nonfree >/dev/null 2>&1; then
  unrar-nonfree x -y -idq "$sfx" "$tmpdir/" >/dev/null
else
  echo "[vendor] 需要系统 unrar 才能解包 SFX（apt install unrar）" >&2
  exit 1
fi

extracted="$(find "$tmpdir" -iname 'UnRAR.exe' -o -iname 'unrar.exe' | head -n 1 || true)"
if [ -z "$extracted" ] || [ ! -f "$extracted" ]; then
  echo "[vendor] SFX 中未找到 UnRAR.exe" >&2
  exit 1
fi

if ! is_console_unrar "$extracted"; then
  echo "[vendor] 解包结果不是控制台 UnRAR，拒绝使用" >&2
  exit 1
fi

cp -f "$extracted" "$TARGET"
# 一并保存许可声明（若有）
lic="$(find "$tmpdir" -iname 'license.txt' | head -n 1 || true)"
if [ -n "$lic" ]; then
  cp -f "$lic" "$VENDOR/unrar_license.txt"
fi

rm -rf "$tmpdir" "$sfx"
echo "[vendor] 已保存控制台 UnRAR：$TARGET ($(wc -c < "$TARGET") bytes)"
