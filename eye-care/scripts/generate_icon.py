#!/usr/bin/env python3
"""生成 assets/icon.ico 供 Windows EXE 使用"""

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


def _qimage_to_pil(img: QImage):
    from PIL import Image

    img = img.convertToFormat(QImage.Format.Format_RGBA8888)
    ptr = img.bits()
    ptr.setsize(img.sizeInBytes())
    return Image.frombytes("RGBA", (img.width(), img.height()), bytes(ptr))


def _count_ico_images(path: Path) -> int:
    with open(path, "rb") as f:
        return struct.unpack("<HHH", f.read(6))[2]


def main() -> None:
    QApplication(sys.argv)
    ICON_ICO_PATH.parent.mkdir(parents=True, exist_ok=True)

    try:
        from PIL import Image
    except ImportError:
        import subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "pillow"])
        from PIL import Image

    # 从 256px 主图生成包含全部标准尺寸的 ICO
    master = _qimage_to_pil(render_app_pixmap(256).toImage()).convert("RGBA")
    master.save(ICON_ICO_PATH, format="ICO", sizes=[(s, s) for s in ICO_SIZES])

    count = _count_ico_images(ICON_ICO_PATH)
    if count < len(ICO_SIZES):
        raise RuntimeError(f"ICO 生成失败，仅包含 {count} 个尺寸")

    master.save(ICON_ICO_PATH.parent / "icon.png", format="PNG")
    print(f"Generated: {ICON_ICO_PATH} ({count} sizes)")


if __name__ == "__main__":
    main()
