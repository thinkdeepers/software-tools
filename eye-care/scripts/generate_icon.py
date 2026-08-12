#!/usr/bin/env python3
"""生成 assets/icon.ico 供 Windows EXE 使用"""

from __future__ import annotations

import sys
from pathlib import Path

# 无显示器环境下使用 offscreen 平台
import os
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
sys.path.insert(0, str(SRC))

from PyQt6.QtGui import QImage  # noqa: E402
from PyQt6.QtWidgets import QApplication  # noqa: E402

from icon import ICON_ICO_PATH, render_app_pixmap  # noqa: E402


def main() -> None:
    app = QApplication(sys.argv)
    ICON_ICO_PATH.parent.mkdir(parents=True, exist_ok=True)

    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = []
    for size in sizes:
        pix = render_app_pixmap(size)
        img = pix.toImage().convertToFormat(QImage.Format.Format_RGBA8888)
        images.append(img)

    # 使用 Pillow 合并为多尺寸 ICO
    try:
        from PIL import Image
    except ImportError:
        import subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "pillow"])
        from PIL import Image

    pil_images = []
    for img, size in zip(images, sizes):
        ptr = img.bits()
        ptr.setsize(img.sizeInBytes())
        data = bytes(ptr)
        pil_images.append(Image.frombytes("RGBA", (size, size), data))

    pil_images[0].save(
        ICON_ICO_PATH,
        format="ICO",
        sizes=[(s, s) for s in sizes],
        append_images=pil_images[1:],
    )

    png_path = ICON_ICO_PATH.parent / "icon.png"
    pil_images[-1].save(png_path, format="PNG")
    print(f"Generated: {ICON_ICO_PATH}")
    print(f"Generated: {png_path}")


if __name__ == "__main__":
    main()
