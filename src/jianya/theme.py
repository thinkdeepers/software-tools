"""简压 UI 主题：与 README 截图一致的配色与控件。

tkinter 原生按钮难以做出实心圆角，这里用 Canvas 自绘主按钮 / 描边按钮，
以及圆角进度条，保证 Windows 打包后观感接近设计稿。
"""

from __future__ import annotations

from typing import Any, Callable, Optional, Tuple

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
    """在 Canvas 上画圆角矩形，返回创建的 item id。"""
    r = max(0, min(radius, (x2 - x1) / 2, (y2 - y1) / 2))
    items = []
    # 中心 + 上下条 + 左右条 + 四角扇形
    items.append(canvas.create_rectangle(x1 + r, y1, x2 - r, y2, fill=fill, outline=""))
    items.append(canvas.create_rectangle(x1, y1 + r, x2, y2 - r, fill=fill, outline=""))
    items.append(canvas.create_oval(x1, y1, x1 + 2 * r, y1 + 2 * r, fill=fill, outline=""))
    items.append(canvas.create_oval(x2 - 2 * r, y1, x2, y1 + 2 * r, fill=fill, outline=""))
    items.append(canvas.create_oval(x1, y2 - 2 * r, x1 + 2 * r, y2, fill=fill, outline=""))
    items.append(canvas.create_oval(x2 - 2 * r, y2 - 2 * r, x2, y2, fill=fill, outline=""))
    if outline and width > 0:
        # 简化描边：再画一层略大的空心圆角较难，改用多边形近似
        items.append(
            canvas.create_arc(
                x1, y1, x1 + 2 * r, y1 + 2 * r, start=90, extent=90, style="arc", outline=outline, width=width
            )
        )
        items.append(
            canvas.create_arc(
                x2 - 2 * r, y1, x2, y1 + 2 * r, start=0, extent=90, style="arc", outline=outline, width=width
            )
        )
        items.append(
            canvas.create_arc(
                x1, y2 - 2 * r, x1 + 2 * r, y2, start=180, extent=90, style="arc", outline=outline, width=width
            )
        )
        items.append(
            canvas.create_arc(
                x2 - 2 * r, y2 - 2 * r, x2, y2, start=270, extent=90, style="arc", outline=outline, width=width
            )
        )
        items.append(canvas.create_line(x1 + r, y1, x2 - r, y1, fill=outline, width=width))
        items.append(canvas.create_line(x1 + r, y2, x2 - r, y2, fill=outline, width=width))
        items.append(canvas.create_line(x1, y1 + r, x1, y2 - r, fill=outline, width=width))
        items.append(canvas.create_line(x2, y1 + r, x2, y2 - r, fill=outline, width=width))
    return tuple(items)


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
    radius: int = 10,
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
        # 允许被 grid/pack 拉宽
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

    def _paint(color: str) -> None:
        canvas.delete("all")
        w = max(int(canvas.winfo_width() or width), min_width or width, 40)
        hh = int(canvas.winfo_height() or h)
        # 留 1px 给描边
        x1, y1, x2, y2 = 1, 1, w - 1, hh - 1
        ow = canvas._jy_outline_w if canvas._jy_enabled else 1
        ol = canvas._jy_outline if canvas._jy_enabled else BORDER
        fc = color if canvas._jy_enabled else "#f3f4f6"
        tc = canvas._jy_fg if canvas._jy_enabled else "#9ca3af"
        draw_rounded_rect(canvas, x1, y1, x2, y2, canvas._jy_radius, fc, ol, ow)
        canvas._jy_text_id = canvas.create_text(
            w / 2,
            hh / 2,
            text=canvas._jy_text,
            fill=tc,
            font=canvas._jy_font,
        )

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
        # 必要时加宽
        probe2 = tk_mod.Label(parent, text=new_text, font=canvas._jy_font)
        probe2.update_idletasks()
        tw2 = int(probe2.winfo_reqwidth())
        probe2.destroy()
        new_w = max(canvas._jy_min_width, tw2 + canvas._jy_pad_x * 2)
        if not canvas._jy_expand:
            canvas.configure(width=new_w)
        _paint(canvas._jy_fill if canvas._jy_enabled else "#f3f4f6")

    def _on_configure(_e=None) -> None:
        _paint(canvas._jy_fill if canvas._jy_enabled else "#f3f4f6")

    canvas.configure_state = configure_state  # type: ignore[attr-defined]
    canvas.set_text = set_text  # type: ignore[attr-defined]
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
        draw_rounded_rect(c, 0, 0, w, h, self.radius, TRACK)
        fill_w = int(w * self._value / 100.0)
        if fill_w > 0:
            # 至少画到圆角直径，避免极窄时难看
            fw = max(fill_w, min(w, self.radius * 2))
            if self._value < 100 and fill_w < self.radius * 2:
                fw = fill_w
            if fw > 2:
                draw_rounded_rect(c, 0, 0, fw, h, self.radius, PRIMARY)


def draw_zip_badge(canvas: Any, size: int = 56, color: str = PRIMARY) -> None:
    """在已有 Canvas 上画简约 ZIP 文件夹徽章（截图风格）。"""
    canvas.delete("all")
    s = size
    canvas.configure(width=s, height=s)
    # 文件夹外框
    m = s * 0.12
    draw_rounded_rect(canvas, m, s * 0.22, s - m, s - m * 0.85, s * 0.1, color)
    # 顶盖
    canvas.create_rectangle(m, s * 0.28, s * 0.42, s * 0.38, fill=color, outline="")
    # 拉链
    zx = s * 0.48
    canvas.create_rectangle(zx, s * 0.32, zx + s * 0.08, s * 0.78, fill="#ffffff", outline="")
    for i in range(5):
        yy = s * 0.38 + i * s * 0.07
        canvas.create_oval(zx - s * 0.03, yy, zx + s * 0.11, yy + s * 0.05, fill="#ffffff", outline="")
    # ZIP 小标
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
