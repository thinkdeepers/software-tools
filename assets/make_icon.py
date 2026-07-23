#!/usr/bin/env python3
"""生成简压的应用图标 assets/app.ico。

设计：圆角渐变底 + 白色压缩包/拉链，简洁清爽。
需要 Pillow：pip install Pillow
"""

import os

from PIL import Image, ImageDraw


def _rounded_rect(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def render(size: int) -> Image.Image:
    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 渐变背景（自上而下：青绿 -> 蓝绿）
    top = (34, 197, 94)      # green-500
    bottom = (13, 148, 136)  # teal-600
    for y in range(s):
        t = y / s
        r = int(top[0] * (1 - t) + bottom[0] * t)
        g = int(top[1] * (1 - t) + bottom[1] * t)
        b = int(top[2] * (1 - t) + bottom[2] * t)
        d.line([(0, y), (s, y)], fill=(r, g, b, 255))

    # 圆角遮罩
    mask = Image.new("L", (s, s), 0)
    md = ImageDraw.Draw(mask)
    _rounded_rect(md, (0, 0, s - 1, s - 1), radius=int(s * 0.22), fill=255)
    img.putalpha(mask)

    d = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)

    # 压缩包主体
    box_w, box_h = int(s * 0.5), int(s * 0.56)
    bx = (s - box_w) // 2
    by = int(s * 0.24)
    _rounded_rect(d, (bx, by, bx + box_w, by + box_h), radius=int(s * 0.05), fill=white)

    # 拉链（竖向锯齿）
    zip_x = s // 2
    zip_top = by + int(box_h * 0.06)
    zip_bottom = by + int(box_h * 0.62)
    seg = max(2, int(s * 0.028))
    accent = (13, 148, 136, 255)
    y = zip_top
    toggle = False
    while y < zip_bottom:
        if toggle:
            d.rectangle((zip_x - seg, y, zip_x, y + seg), fill=accent)
        else:
            d.rectangle((zip_x, y, zip_x + seg, y + seg), fill=accent)
        toggle = not toggle
        y += seg

    # 拉链头（小滑块）
    pull_w, pull_h = int(s * 0.11), int(s * 0.14)
    px = zip_x - pull_w // 2
    py = zip_bottom
    _rounded_rect(d, (px, py, px + pull_w, py + pull_h), radius=int(s * 0.02), fill=accent)

    return img.resize((size, size), Image.LANCZOS)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = [render(sz) for sz in sizes]
    out = os.path.join(here, "app.ico")
    images[-1].save(out, format="ICO", sizes=[(sz, sz) for sz in sizes])
    # 附带一个 PNG 预览
    images[-1].save(os.path.join(here, "app.png"), format="PNG")
    print("已生成:", out)


if __name__ == "__main__":
    main()
