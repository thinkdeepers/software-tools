"""简压 UI 主题：与 README 截图一致的配色与控件。

tkinter 原生按钮难以做出实心圆角，这里用 Canvas 多边形逼近圆角，
避免矩形+椭圆叠加造成的锯齿与接缝失真。
"""

from __future__ import annotations

import math
from typing import Any, Callable, List, Optional, Tuple

from .dpi import pick_ui_font
from .resources import resource_path


# ---------------------------------------------------------------------------
# 设计令牌（对齐 README 截图）
# ---------------------------------------------------------------------------

BG = "#ffffff"
PRIMARY = "#0f766e"
PRIMARY_HOVER = "#0d9488"
PRIMARY_ACTIVE = "#115e59"
PRIMARY_SOFT = "#ecfdf5"
TEXT = "#111827"
TEXT_MUTED = "#6b7280"
TEXT_SUB = "#4b5563"
BORDER = "#e5e7eb"
BORDER_STRONG = "#d1d5db"
TRACK = "#e8eef0"
DANGER = "#b91c1c"
DANGER_HOVER = "#dc2626"


def apply_window_chrome(win: Any) -> None:
    """统一窗口背景与图标。"""
    try:
        win.configure(bg=BG)
    except Exception:
        pass
    try:
        ico = resource_path("app.ico")
        if ico:
            win.iconbitmap(default=ico)
    except Exception:
        pass


def load_app_photo(tk_mod: Any, master: Any, max_side: int = 72) -> Optional[Any]:
    """加载 app.png 并缩放到约 max_side 像素。失败返回 None。"""
    png = resource_path("app.png")
    if not png:
        return None
    try:
        img = tk_mod.PhotoImage(master=master, file=png)
    except Exception:
        return None
    try:
        w, h = int(img.width()), int(img.height())
        if w <= 0 or h <= 0:
            return img
        # PhotoImage 只能整数缩小
        factor = max(1, max(w, h) // max(1, max_side))
        if factor > 1:
            img = img.subsample(factor, factor)
        return img
    except Exception:
        return img


def _rounded_rect_points(
    x1: float, y1: float, x2: float, y2: float, radius: float, segments: int = 10
) -> List[float]:
    """生成圆角矩形多边形顶点（顺时针，扁平 [x,y,...]）。"""
    if x2 <= x1 or y2 <= y1:
        return [x1, y1, x2, y1, x2, y2, x1, y2]
    r = max(0.0, min(float(radius), (x2 - x1) / 2.0, (y2 - y1) / 2.0))
    if r < 0.5:
        return [x1, y1, x2, y1, x2, y2, x1, y2]
    segs = max(4, int(segments))
    corners = (
        (x2 - r, y1 + r, -90, 0),  # 右上
        (x2 - r, y2 - r, 0, 90),  # 右下
        (x1 + r, y2 - r, 90, 180),  # 左下
        (x1 + r, y1 + r, 180, 270),  # 左上
    )
    pts: List[float] = []
    for cx, cy, a0, a1 in corners:
        for i in range(segs + 1):
            ang = math.radians(a0 + (a1 - a0) * i / segs)
            pts.append(cx + r * math.cos(ang))
            pts.append(cy + r * math.sin(ang))
    return pts


def draw_rounded_rect(
    canvas: Any,
    x1: float,
    y1: float,
    x2: float,
    y2: float,
    radius: float,
    fill: str,
    outline: str = "",
    width: int = 0,
) -> Tuple[Any, ...]:
    """用单一多边形绘制圆角矩形，避免叠加接缝失真。"""
    pts = _rounded_rect_points(x1, y1, x2, y2, radius)
    ow = max(0, int(width))
    if outline and ow > 0:
        item = canvas.create_polygon(
            pts,
            fill=fill,
            outline=outline,
            width=ow,
            joinstyle="round",
            smooth=False,
        )
    else:
        # 无描边时 outline 与 fill 相同，可消除多边形边缘毛刺
        item = canvas.create_polygon(
            pts,
            fill=fill,
            outline=fill,
            width=1,
            joinstyle="round",
            smooth=False,
        )
    return (item,)


def make_rounded_button(
    parent: Any,
    tk_mod: Any,
    text: str,
    command: Optional[Callable[[], None]] = None,
    *,
    variant: str = "primary",
    font_size: int = 12,
    bold: bool = True,
    height: int = 44,
    pad_x: int = 22,
    radius: int = 8,
    min_width: int = 0,
    expand_width: bool = False,
) -> Any:
    """创建圆角按钮，返回 Canvas（带 .configure_state / .set_text 方法）。"""

    if variant == "primary":
        fill, fill_h, fill_a = PRIMARY, PRIMARY_HOVER, PRIMARY_ACTIVE
        fg = "#ffffff"
        outline, outline_w = "", 0
    elif variant == "outline":
        fill, fill_h, fill_a = BG, PRIMARY_SOFT, PRIMARY_SOFT
        fg = PRIMARY
        outline, outline_w = PRIMARY, 2
    else:  # ghost
        fill, fill_h, fill_a = BG, "#f3f4f6", "#e5e7eb"
        fg = TEXT
        outline, outline_w = BORDER_STRONG, 1

    font = pick_ui_font(parent, font_size, bold)
    # 先测文字宽度
    probe = tk_mod.Label(parent, text=text, font=font)
    probe.update_idletasks()
    tw = int(probe.winfo_reqwidth())
    probe.destroy()
    width = max(min_width, tw + pad_x * 2)
    h = max(32, int(height))
    # 圆角不超过高度一半，避免过度弯曲产生视觉失真
    radius = max(4, min(int(radius), h // 2 - 1))

    canvas = tk_mod.Canvas(
        parent,
        width=width,
        height=h,
        bg=BG,
        highlightthickness=0,
        bd=0,
        cursor="hand2",
    )
    if expand_width:
        try:
            canvas.configure(width=1)
        except Exception:
            pass
    canvas._jy_text = text
    canvas._jy_command = command
    canvas._jy_enabled = True
    canvas._jy_variant = variant
    canvas._jy_fill = fill
    canvas._jy_fill_h = fill_h
    canvas._jy_fill_a = fill_a
    canvas._jy_fg = fg
    canvas._jy_outline = outline
    canvas._jy_outline_w = outline_w
    canvas._jy_radius = radius
    canvas._jy_font = font
    canvas._jy_h = h
    canvas._jy_min_width = min_width
    canvas._jy_pad_x = pad_x
    canvas._jy_expand = expand_width
    canvas._jy_items = ()
    canvas._jy_text_id = None
    canvas._jy_paint_job = None

    def _paint(color: str) -> None:
        canvas.delete("all")
        w = max(int(canvas.winfo_width() or width), min_width or width, 40)
        hh = int(canvas.winfo_height() or h)
        inset = max(1.0, (canvas._jy_outline_w / 2.0) + 0.5)
        x1, y1, x2, y2 = inset, inset, w - inset, hh - inset
        ow = canvas._jy_outline_w if canvas._jy_enabled else 1
        ol = canvas._jy_outline if canvas._jy_enabled else BORDER
        fc = color if canvas._jy_enabled else "#f3f4f6"
        tc = canvas._jy_fg if canvas._jy_enabled else "#9ca3af"
        r = min(canvas._jy_radius, (x2 - x1) / 2, (y2 - y1) / 2)
        draw_rounded_rect(canvas, x1, y1, x2, y2, r, fc, ol, ow)
        canvas._jy_text_id = canvas.create_text(
            w / 2,
            hh / 2,
            text=canvas._jy_text,
            fill=tc,
            font=canvas._jy_font,
        )

    def _schedule_paint(color: str) -> None:
        job = canvas._jy_paint_job
        if job is not None:
            try:
                canvas.after_cancel(job)
            except Exception:
                pass
        canvas._jy_paint_job = canvas.after(1, lambda: _paint(color))

    def _on_enter(_e=None) -> None:
        if canvas._jy_enabled:
            _paint(canvas._jy_fill_h)

    def _on_leave(_e=None) -> None:
        if canvas._jy_enabled:
            _paint(canvas._jy_fill)

    def _on_press(_e=None) -> None:
        if canvas._jy_enabled:
            _paint(canvas._jy_fill_a)

    def _on_release(_e=None) -> None:
        if not canvas._jy_enabled:
            return
        _paint(canvas._jy_fill_h)
        if canvas._jy_command:
            canvas._jy_command()

    def configure_state(state: str) -> None:
        canvas._jy_enabled = state != "disabled"
        canvas.configure(cursor="hand2" if canvas._jy_enabled else "arrow")
        _paint(canvas._jy_fill if canvas._jy_enabled else "#f3f4f6")

    def set_text(new_text: str) -> None:
        canvas._jy_text = new_text
        probe2 = tk_mod.Label(parent, text=new_text, font=canvas._jy_font)
        probe2.update_idletasks()
        tw2 = int(probe2.winfo_reqwidth())
        probe2.destroy()
        new_w = max(canvas._jy_min_width, tw2 + canvas._jy_pad_x * 2)
        if not canvas._jy_expand:
            canvas.configure(width=new_w)
        _paint(canvas._jy_fill if canvas._jy_enabled else "#f3f4f6")

    def set_command(cmd: Optional[Callable[[], None]]) -> None:
        canvas._jy_command = cmd

    def _on_configure(_e=None) -> None:
        _schedule_paint(canvas._jy_fill if canvas._jy_enabled else "#f3f4f6")

    canvas.configure_state = configure_state  # type: ignore[attr-defined]
    canvas.set_text = set_text  # type: ignore[attr-defined]
    canvas.set_command = set_command  # type: ignore[attr-defined]
    canvas.bind("<Enter>", _on_enter)
    canvas.bind("<Leave>", _on_leave)
    canvas.bind("<ButtonPress-1>", _on_press)
    canvas.bind("<ButtonRelease-1>", _on_release)
    canvas.bind("<Configure>", _on_configure)
    _paint(fill)
    return canvas


class RoundedProgressBar:
    """圆角进度条（Canvas）。"""

    def __init__(
        self,
        parent: Any,
        tk_mod: Any,
        *,
        height: int = 12,
        radius: int = 6,
        bg: str = BG,
    ):
        self.tk = tk_mod
        self.height = max(8, int(height))
        self.radius = radius
        self._value = 0.0
        self.canvas = tk_mod.Canvas(
            parent,
            height=self.height,
            bg=bg,
            highlightthickness=0,
            bd=0,
        )
        self.canvas.bind("<Configure>", lambda _e: self._draw())

    def pack(self, **kwargs) -> None:
        self.canvas.pack(**kwargs)

    def grid(self, **kwargs) -> None:
        self.canvas.grid(**kwargs)

    def configure(self, **kwargs) -> None:
        if "value" in kwargs:
            self.set_value(float(kwargs["value"]))

    def set_value(self, pct: float) -> None:
        self._value = max(0.0, min(100.0, float(pct)))
        self._draw()

    def _draw(self) -> None:
        c = self.canvas
        try:
            w = max(int(c.winfo_width()), 40)
            h = max(int(c.winfo_height()), self.height)
        except Exception:
            return
        c.delete("all")
        r = min(self.radius, h / 2)
        draw_rounded_rect(c, 0, 0, w, h, r, TRACK)
        fill_w = int(w * self._value / 100.0)
        if fill_w > 1:
            if self._value >= 99.5:
                fw = w
                fr = r
            else:
                fw = max(fill_w, 2)
                fr = min(r, fw / 2)
            draw_rounded_rect(c, 0, 0, fw, h, fr, PRIMARY)


def draw_zip_badge(canvas: Any, size: int = 56, color: str = PRIMARY) -> None:
    """在已有 Canvas 上画简约 ZIP 文件夹徽章（截图风格）。"""
    canvas.delete("all")
    s = size
    canvas.configure(width=s, height=s)
    m = s * 0.12
    draw_rounded_rect(canvas, m, s * 0.22, s - m, s - m * 0.85, s * 0.1, color)
    canvas.create_rectangle(m, s * 0.28, s * 0.42, s * 0.38, fill=color, outline="")
    zx = s * 0.48
    canvas.create_rectangle(zx, s * 0.32, zx + s * 0.08, s * 0.78, fill="#ffffff", outline="")
    for i in range(5):
        yy = s * 0.38 + i * s * 0.07
        canvas.create_oval(
            zx - s * 0.03, yy, zx + s * 0.11, yy + s * 0.05, fill="#ffffff", outline=""
        )
    canvas.create_text(
        s * 0.72,
        s * 0.62,
        text="ZIP",
        fill="#ffffff",
        font=("Segoe UI", max(7, int(s * 0.16)), "bold"),
    )


def make_zip_icon(parent: Any, tk_mod: Any, size: int = 64) -> Any:
    """优先用 app.png；失败则 Canvas 绘制 ZIP 徽章。返回 (widget, photo_ref)。"""
    photo = load_app_photo(tk_mod, parent, max_side=size)
    if photo is not None:
        lbl = tk_mod.Label(parent, image=photo, bg=BG, bd=0, highlightthickness=0)
        return lbl, photo
    c = tk_mod.Canvas(parent, width=size, height=size, bg=BG, highlightthickness=0, bd=0)
    draw_zip_badge(c, size=size)
    return c, None
