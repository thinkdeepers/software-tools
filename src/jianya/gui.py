"""极简图形界面：只有"压缩"和"解压"两个大按钮。"""

from __future__ import annotations

import os
import queue
import sys
import threading
from pathlib import Path
from typing import List, Optional

from . import __version__
from . import core
from .resources import resource_path


def _enable_high_dpi() -> None:
    """在创建窗口前声明 DPI 感知，避免高分屏下界面被系统位图拉伸而发虚。

    仅 Windows 有效；失败时静默降级。
    """
    if sys.platform != "win32":
        return
    try:
        import ctypes

        # Per-Monitor v2（Windows 10 1703+），效果最佳。
        try:
            ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
            return
        except Exception:
            pass
        # Per-Monitor（Windows 8.1+）
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
            return
        except Exception:
            pass
        # System DPI aware（Vista+）
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass


def launch() -> int:
    _enable_high_dpi()
    try:
        import tkinter as tk
        from tkinter import filedialog, messagebox, ttk
    except Exception as exc:  # pragma: no cover - 缺少 tkinter 时
        print(f"无法启动图形界面（缺少 tkinter）：{exc}", file=sys.stderr)
        print("你也可以使用命令行：jianya --compress <文件> 或 jianya --extract <压缩包>")
        return 1

    app = _App(tk, filedialog, messagebox, ttk)
    app.run()
    return 0


class _App:
    """封装 tkinter 主窗口，避免在模块顶层导入 tkinter。"""

    def __init__(self, tk, filedialog, messagebox, ttk):
        self.tk = tk
        self.filedialog = filedialog
        self.messagebox = messagebox
        self.ttk = ttk

        self._events: "queue.Queue[tuple]" = queue.Queue()
        self._busy = False

        root = tk.Tk()
        self.root = root
        root.title(f"简压 {__version__} — 简洁 · 免费 · 无广告")

        # 按屏幕真实 DPI 缩放窗口尺寸（96 dpi 为 100%）。声明 DPI 感知后，
        # 文字/图标由系统清晰绘制，窗口尺寸也随缩放等比放大以容纳内容。
        try:
            scale = root.winfo_fpixels("1i") / 96.0
        except Exception:
            scale = 1.0
        if scale < 1.0:
            scale = 1.0
        self._scale = scale
        base_w, base_h = 480, 500
        win_w, win_h = int(base_w * scale), int(base_h * scale)
        root.geometry(f"{win_w}x{win_h}")
        # 固定最小尺寸，保证底部按钮与说明文字不会因窗口缩小被遮挡。
        root.minsize(win_w, win_h)
        root.configure(bg="white")

        self._set_window_icon()
        self._build_ui()
        root.after(80, self._drain_events)

    def _set_window_icon(self) -> None:
        """把窗口图标设置为与 exe 一致的应用图标。"""
        tk = self.tk
        # Windows 下优先使用 .ico（标题栏 / 任务栏一致）。
        ico = resource_path("app.ico")
        if ico:
            try:
                self.root.iconbitmap(default=ico)
            except Exception:
                pass
        # 跨平台回退：用 PNG 作为窗口图标。
        png = resource_path("app.png")
        if png:
            try:
                self._icon_image = tk.PhotoImage(file=png)
                self.root.iconphoto(True, self._icon_image)
            except Exception:
                pass

    # ------------------------------------------------------------------
    # 界面
    # ------------------------------------------------------------------
    def _build_ui(self) -> None:
        tk, ttk = self.tk, self.ttk

        WHITE = "#ffffff"
        try:
            style = ttk.Style()
            if "clam" in style.theme_names():
                style.theme_use("clam")
            # 统一白色背景，去掉难看的灰底。
            style.configure("White.TFrame", background=WHITE)
            style.configure("White.TLabel", background=WHITE)
            style.configure("Title.TLabel", background=WHITE, foreground="#0f766e")
            style.configure("Sub.TLabel", background=WHITE, foreground="#555555")
            style.configure("Status.TLabel", background=WHITE, foreground="#555555")
            style.configure("Big.TButton", font=("Microsoft YaHei", 16, "bold"), padding=18)
            style.configure("Link.TButton", font=("Microsoft YaHei", 9), padding=6)
            style.configure("Footer.TLabel", background=WHITE, foreground="#9aa0a6")
        except Exception:
            pass

        container = ttk.Frame(self.root, padding=24, style="White.TFrame")
        container.pack(fill="both", expand=True)

        # 顶部标题
        header = ttk.Frame(container, style="White.TFrame")
        header.pack(side="top", fill="x")
        ttk.Label(
            header, text="简压", style="Title.TLabel",
            font=("Microsoft YaHei", 22, "bold"),
        ).pack(pady=(0, 4))
        ttk.Label(
            header, text="压缩统一为 ZIP · 解压支持常见格式",
            style="Sub.TLabel", font=("Microsoft YaHei", 10),
        ).pack(pady=(0, 18))

        # 主操作按钮
        buttons = ttk.Frame(container, style="White.TFrame")
        buttons.pack(side="top", fill="x")
        buttons.columnconfigure(0, weight=1)
        buttons.columnconfigure(1, weight=1)

        self.btn_compress = ttk.Button(
            buttons, text="压缩", style="Big.TButton", command=self._on_compress
        )
        self.btn_compress.grid(row=0, column=0, sticky="ew", padx=(0, 8))

        self.btn_extract = ttk.Button(
            buttons, text="解压", style="Big.TButton", command=self._on_extract
        )
        self.btn_extract.grid(row=0, column=1, sticky="ew", padx=(8, 0))

        # 最底部：软件说明（先 pack，贴在窗口最底端）
        footer = ttk.Frame(container, style="White.TFrame")
        footer.pack(side="bottom", fill="x", pady=(10, 0))
        ttk.Separator(footer, orient="horizontal").pack(fill="x", pady=(0, 8))
        for text in (
            "本软件由 Opus 4.8 模型自动生成",
            "致力于造福全人类，共建简单、和平、美好的生态",
            "—— 洞穴理论工作室 出品",
        ):
            ttk.Label(
                footer, text=text, style="Footer.TLabel",
                font=("Microsoft YaHei", 8), anchor="center", justify="center",
            ).pack(fill="x")

        # 底部固定区域（文件关联 / 右键菜单），位于说明文字上方，保证不被遮挡。
        bottom = ttk.Frame(container, style="White.TFrame")
        bottom.pack(side="bottom", fill="x", pady=(14, 0))
        inner = ttk.Frame(bottom, style="White.TFrame")
        inner.pack(anchor="center")
        ttk.Button(
            inner, text="设为默认打开", style="Link.TButton",
            command=self._on_install_menu,
        ).pack(side="left")
        ttk.Button(
            inner, text="取消默认打开", style="Link.TButton",
            command=self._on_uninstall_menu,
        ).pack(side="left", padx=(8, 0))

        # 中间区域填充剩余空间（进度与状态）
        middle = ttk.Frame(container, style="White.TFrame")
        middle.pack(side="top", fill="both", expand=True)

        self.progress = ttk.Progressbar(middle, mode="determinate")
        self.progress.pack(fill="x", pady=(22, 6))

        self.status = ttk.Label(
            middle, text="选择文件开始压缩，或选择压缩包进行解压",
            style="Status.TLabel", font=("Microsoft YaHei", 9),
        )
        self.status.pack(fill="x")

    # ------------------------------------------------------------------
    # 事件处理
    # ------------------------------------------------------------------
    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        state = "disabled" if busy else "normal"
        self.btn_compress.configure(state=state)
        self.btn_extract.configure(state=state)

    def _on_compress(self) -> None:
        if self._busy:
            return
        # 先尝试选择文件；用户可取消后改为选择文件夹。
        files = self.filedialog.askopenfilenames(title="选择要压缩的文件（可多选）")
        paths: List[str] = list(files)
        if not paths:
            folder = self.filedialog.askdirectory(title="或选择要压缩的文件夹")
            if folder:
                paths = [folder]
        if not paths:
            return

        output = self.filedialog.asksaveasfilename(
            title="保存 ZIP 为",
            defaultextension=".zip",
            initialfile=core.default_zip_output([Path(p) for p in paths]).name,
            filetypes=[("ZIP 压缩包", "*.zip")],
        )
        if not output:
            return

        self._start_task(self._do_compress, paths, output)

    def _on_extract(self) -> None:
        if self._busy:
            return
        archive = self.filedialog.askopenfilename(
            title="选择要解压的压缩包",
            filetypes=[
                ("压缩包", "*.zip *.tar *.gz *.tgz *.bz2 *.xz *.7z *.rar"),
                ("所有文件", "*.*"),
            ],
        )
        if not archive:
            return
        output = self.filedialog.askdirectory(title="选择解压到的目录（取消则解压到同名目录）")
        self._start_task(self._do_extract, archive, output or None)

    def _start_task(self, target, *args) -> None:
        self._set_busy(True)
        self.progress.configure(value=0)
        self.status.configure(text="处理中…")
        thread = threading.Thread(target=target, args=args, daemon=True)
        thread.start()

    def _progress(self, done: int, total: int, name: str) -> None:
        self._events.put(("progress", done, total, name))

    def _do_compress(self, paths: List[str], output: str) -> None:
        try:
            result = core.compress_to_zip(paths, output=output, progress=self._progress)
            self._events.put(("done", "压缩完成", str(result)))
        except Exception as exc:
            self._events.put(("error", str(exc)))

    def _do_extract(self, archive: str, output: Optional[str]) -> None:
        try:
            result = core.extract_archive(archive, output_dir=output, progress=self._progress)
            self._events.put(("done", "解压完成", str(result)))
        except Exception as exc:
            self._events.put(("error", str(exc)))

    def _on_install_menu(self) -> None:
        self._manage_menu(install=True)

    def _on_uninstall_menu(self) -> None:
        self._manage_menu(install=False)

    def _manage_menu(self, install: bool) -> None:
        from . import context_menu

        try:
            if install:
                context_menu.install()
                self.messagebox.showinfo(
                    "简压",
                    "已设为默认打开程序。\n"
                    "· zip/7z/rar 等压缩包将显示简压图标\n"
                    "· 双击压缩包即可用简压解压\n"
                    "· 右键文件可压缩，右键压缩包可解压",
                )
            else:
                context_menu.uninstall()
                self.messagebox.showinfo("简压", "已取消默认打开，并移除右键菜单。")
        except context_menu.ContextMenuError as exc:
            self.messagebox.showerror("简压", str(exc))

    # ------------------------------------------------------------------
    # 主循环辅助
    # ------------------------------------------------------------------
    def _drain_events(self) -> None:
        try:
            while True:
                event = self._events.get_nowait()
                kind = event[0]
                if kind == "progress":
                    _, done, total, name = event
                    pct = int(done / total * 100) if total else 100
                    self.progress.configure(value=pct)
                    self.status.configure(text=f"[{pct}%] {name}")
                elif kind == "done":
                    _, title, path = event
                    self.progress.configure(value=100)
                    self.status.configure(text=f"{title}：{path}")
                    self._set_busy(False)
                    self.messagebox.showinfo("简压", f"{title}\n{path}")
                elif kind == "error":
                    _, msg = event
                    self._set_busy(False)
                    self.status.configure(text="操作失败")
                    self.messagebox.showerror("简压", msg)
        except queue.Empty:
            pass
        self.root.after(80, self._drain_events)

    def run(self) -> None:
        self.root.mainloop()
