"""DPI 感知的进度窗口与提示对话框。

- 进程级先声明 DPI，避免对话框发虚
- 完成提示只在进度到 100% 且操作结束后再弹出
"""

from __future__ import annotations

import queue
import sys
import threading
from typing import Any, Callable, Optional

from .dpi import configure_tk_scaling, enable_high_dpi
from .resources import resource_path


ProgressFn = Callable[[int, int, str], None]


def _font(size: int, bold: bool = False) -> tuple:
    weight = "bold" if bold else "normal"
    return ("Microsoft YaHei UI", size, weight)


class ProgressDialog:
    """带进度条的进度窗口。

    - 若传入 ``parent``（已有 Tk 应用），使用 Toplevel，避免多 Tk 冲突
    - 否则自行创建 Tk（供右键菜单等无主界面场景）
    """

    def __init__(
        self,
        title: str = "简压",
        status: str = "处理中…",
        parent: Any = None,
    ):
        enable_high_dpi()
        import tkinter as tk
        from tkinter import ttk

        self.tk = tk
        self._events: "queue.Queue[tuple]" = queue.Queue()
        self._closed = False
        self._finished = False
        self._owns_root = parent is None
        self._result_title = ""
        self._result_message = ""
        self._result_error = False
        self._wait_var = None

        if parent is None:
            root = tk.Tk()
            self.scale = configure_tk_scaling(root)
            self.root = root
            self.win = root
        else:
            self.root = parent
            try:
                self.scale = float(parent.winfo_fpixels("1i")) / 96.0
            except Exception:
                self.scale = 1.0
            if self.scale < 1.0:
                self.scale = 1.0
            self.win = tk.Toplevel(parent)
            self._wait_var = tk.BooleanVar(master=parent, value=False)

        win = self.win
        win.title(title)
        win.configure(bg="white")
        w, h = int(420 * self.scale), int(160 * self.scale)
        win.geometry(f"{w}x{h}")
        win.minsize(w, h)
        win.resizable(False, False)
        try:
            win.attributes("-topmost", True)
        except Exception:
            pass

        try:
            ico = resource_path("app.ico")
            if ico:
                win.iconbitmap(default=ico)
        except Exception:
            pass

        try:
            style = ttk.Style(win)
            if "clam" in style.theme_names():
                style.theme_use("clam")
            style.configure("Dlg.TFrame", background="white")
            style.configure("Dlg.TLabel", background="white", foreground="#222222")
            style.configure(
                "DlgTitle.TLabel",
                background="white",
                foreground="#0f766e",
                font=_font(12, True),
            )
            style.configure("DlgStatus.TLabel", background="white", foreground="#555555")
        except Exception:
            pass

        frame = ttk.Frame(win, padding=int(18 * self.scale), style="Dlg.TFrame")
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text=title, style="DlgTitle.TLabel").pack(anchor="w")
        self.status_label = ttk.Label(
            frame, text=status, style="DlgStatus.TLabel", font=_font(9)
        )
        self.status_label.pack(anchor="w", pady=(10, 8))

        self.bar = ttk.Progressbar(frame, mode="determinate", maximum=100)
        self.bar.pack(fill="x")
        self.pct_label = ttk.Label(
            frame, text="0%", style="DlgStatus.TLabel", font=_font(9)
        )
        self.pct_label.pack(anchor="e", pady=(6, 0))

        win.protocol("WM_DELETE_WINDOW", self._on_user_close)
        win.update_idletasks()
        try:
            sw = win.winfo_screenwidth()
            sh = win.winfo_screenheight()
            x = max(0, (sw - w) // 2)
            y = max(0, (sh - h) // 3)
            win.geometry(f"{w}x{h}+{x}+{y}")
        except Exception:
            pass

        win.after(40, self._drain)

    def _on_user_close(self) -> None:
        if not self._finished:
            return
        self.close()

    def progress(self, done: int, total: int, name: str) -> None:
        self._events.put(("progress", done, total, name))

    def finish_ok(self, title: str, message: str) -> None:
        self._events.put(("done", title, message, False))

    def finish_error(self, title: str, message: str) -> None:
        self._events.put(("done", title, message, True))

    def _drain(self) -> None:
        if self._closed:
            return
        try:
            while True:
                event = self._events.get_nowait()
                kind = event[0]
                if kind == "progress":
                    _, done, total, name = event
                    pct = int(done / total * 100) if total else 0
                    pct = max(0, min(100, pct))
                    self.bar.configure(mode="determinate", value=pct)
                    self.pct_label.configure(text=f"{pct}%")
                    shown = name if len(name) <= 48 else ("…" + name[-47:])
                    self.status_label.configure(text=f"正在处理：{shown}")
                elif kind == "done":
                    _, title, message, is_error = event
                    self._finished = True
                    self.bar.configure(value=100)
                    self.pct_label.configure(text="100%")
                    self.status_label.configure(text="失败" if is_error else "已完成")
                    self._result_title = title
                    self._result_message = message
                    self._result_error = is_error
                    self.win.update_idletasks()
                    # 先让用户看到 100%，再提示结果
                    self.win.after(150, self._show_result_and_close)
        except queue.Empty:
            pass
        if not self._closed:
            self.win.after(40, self._drain)

    def _show_result_and_close(self) -> None:
        if self._closed:
            return
        title = self._result_title
        message = self._result_message
        is_error = self._result_error
        self.close()
        show_alert(title, message, error=is_error)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self.win.destroy()
        except Exception:
            pass
        if self._wait_var is not None:
            try:
                self._wait_var.set(True)
            except Exception:
                pass

    def run(self) -> None:
        """阻塞直到进度窗关闭。"""
        if self._owns_root:
            self.root.mainloop()
        else:
            try:
                self.win.transient(self.root)
                self.win.grab_set()
            except Exception:
                pass
            if self._wait_var is not None:
                self.root.wait_variable(self._wait_var)
            else:
                self.root.wait_window(self.win)


def show_alert(title: str, message: str, error: bool = False) -> None:
    """清晰的提示对话框。"""
    enable_high_dpi()

    if sys.platform == "win32":
        try:
            import ctypes

            MB_OK = 0x0
            MB_ICONERROR = 0x10
            MB_ICONINFORMATION = 0x40
            flags = MB_OK | (MB_ICONERROR if error else MB_ICONINFORMATION)
            ctypes.windll.user32.MessageBoxW(None, str(message), str(title), flags)
            return
        except Exception:
            pass

    import tkinter as tk
    from tkinter import ttk

    root = tk.Tk()
    scale = configure_tk_scaling(root)
    root.title(title)
    root.configure(bg="white")
    root.resizable(False, False)
    try:
        ico = resource_path("app.ico")
        if ico:
            root.iconbitmap(default=ico)
    except Exception:
        pass

    frame = ttk.Frame(root, padding=int(20 * scale))
    frame.pack(fill="both", expand=True)
    color = "#b91c1c" if error else "#0f766e"
    ttk.Label(
        frame, text=title, font=_font(12, True), foreground=color, background="white"
    ).pack(anchor="w")
    ttk.Label(
        frame,
        text=message,
        font=_font(10),
        foreground="#333333",
        background="white",
        wraplength=int(360 * scale),
        justify="left",
    ).pack(anchor="w", pady=(12, 16))

    def _ok() -> None:
        root.destroy()

    ttk.Button(frame, text="确定", command=_ok).pack(anchor="e")
    root.bind("<Return>", lambda _e: _ok())
    root.bind("<Escape>", lambda _e: _ok())
    root.update_idletasks()
    w = max(int(320 * scale), root.winfo_reqwidth())
    h = max(int(140 * scale), root.winfo_reqheight())
    sw = root.winfo_screenwidth()
    sh = root.winfo_screenheight()
    root.geometry(f"{w}x{h}+{(sw - w) // 2}+{(sh - h) // 3}")
    root.mainloop()
