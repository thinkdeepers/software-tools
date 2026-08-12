#!/usr/bin/env python3
"""生成 Windows 兼容的标准 BMP 格式 icon.ico（供 EXE 文件图标嵌入）"""

from __future__ import annotations

import os
import struct
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
sys.path.insert(0, str(SRC))

from PyQt6.QtGui import QImage  # noqa: E402
from PyQt6.QtWidgets import QApplication  # noqa: E402

from icon import ICON_ICO_PATH, render_app_pixmap  # noqa: E402

ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]


def _pixmap_rgba_bytes(size: int) -> bytes:
    pix = render_app_pixmap(size)
    img = pix.toImage().convertToFormat(QImage.Format.Format_RGBA8888)
    ptr = img.bits()
    ptr.setsize(img.sizeInBytes())
    return bytes(ptr)


def _rgba_to_bgra_bottom_up(rgba: bytes, w: int, h: int) -> tuple[bytes, bytes]:
    """返回 (XOR BGRA bottom-up, AND mask)"""
    row_src = w * 4
    xor = bytearray(w * h * 4)
    and_row = ((w + 31) // 32) * 4
    and_mask = bytearray(and_row * h)

    for y in range(h):
        for x in range(w):
            si = y * row_src + x * 4
            r, g, b, a = rgba[si], rgba[si + 1], rgba[si + 2], rgba[si + 3]
            # bottom-up
            di = (h - 1 - y) * row_src + x * 4
            xor[di] = b
            xor[di + 1] = g
            xor[di + 2] = r
            xor[di + 3] = a
            # AND mask: 1 = 透明
            if a < 128:
                byte_index = (h - 1 - y) * and_row + (x // 8)
                and_mask[byte_index] |= 0x80 >> (x % 8)

    return bytes(xor), bytes(and_mask)


def write_bmp_ico(path: Path, sizes: list[int]) -> None:
    images: list[tuple[int, int, bytes]] = []
    for size in sizes:
        rgba = _pixmap_rgba_bytes(size)
        xor, and_mask = _rgba_to_bgra_bottom_up(rgba, size, size)
        header = struct.pack(
            "<IIIHHIIIIII",
            40,
            size,
            size * 2,
            1,
            32,
            0,
            len(xor),
            0,
            0,
            0,
            0,
        )
        images.append((size, size, header + xor + and_mask))

    count = len(images)
    offset = 6 + 16 * count
    with open(path, "wb") as f:
        f.write(struct.pack("<HHH", 0, 1, count))
        for w, h, data in images:
            f.write(
                struct.pack(
                    "<BBBBHHII",
                    0 if w >= 256 else w,
                    0 if h >= 256 else h,
                    0,
                    0,
                    1,
                    32,
                    len(data),
                    offset,
                )
            )
            offset += len(data)
        for _, _, data in images:
            f.write(data)


def write_check_icons(assets: Path) -> None:
    from PIL import Image, ImageDraw

    def make(checked: bool, size: int = 40) -> Image.Image:
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        m = 2
        border = (34, 197, 94, 255) if checked else (148, 163, 184, 255)
        draw.rounded_rectangle(
            [m, m, size - m - 1, size - m - 1],
            radius=7,
            outline=border,
            width=2,
            fill=(255, 255, 255, 255),
        )
        if checked:
            points = [
                (size * 0.22, size * 0.52),
                (size * 0.42, size * 0.72),
                (size * 0.80, size * 0.28),
            ]
            draw.line(points, fill=(22, 163, 74, 255), width=max(3, size // 12), joint="curve")
        return img

    make(False).save(assets / "checkbox_off.png")
    make(True).save(assets / "checkbox_on.png")


def main() -> None:
    app = QApplication(sys.argv)
    assets = ICON_ICO_PATH.parent
    assets.mkdir(parents=True, exist_ok=True)

    write_bmp_ico(ICON_ICO_PATH, ICO_SIZES)

    try:
        from PIL import Image
    except ImportError:
        import subprocess

        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "pillow"])
        from PIL import Image

    Image.frombytes("RGBA", (256, 256), _pixmap_rgba_bytes(256)).save(assets / "icon.png")
    write_check_icons(assets)

    with open(ICON_ICO_PATH, "rb") as f:
        count = struct.unpack("<HHH", f.read(6))[2]
    print(
        f"Generated: {ICON_ICO_PATH} ({count} BMP frames, {ICON_ICO_PATH.stat().st_size} bytes)"
    )


if __name__ == "__main__":
    main()
