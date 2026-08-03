"""DPI 感知的进度窗口与提示对话框。"""

from __future__ import annotations

import queue
import sys
import threading
from typing import Any, Callable, Optional

from .dpi import configure_tk_scaling, enable_high_dpi, scaled_font
from .resources import resource_path


ProgressFn = Callable[[int, int, str], None]


class ProgressDialog:
    """始终置顶可见的进度窗口。

    - 开始时使用 indeterminate 动画，避免「长时间停在 0% 像没进度条」
    - 收到真实进度后切换为确定进度
    - 到 100% 并完成后再弹出结果
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
        self._indeterminate = True
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
                self.scale = max(1.0, float(parent.winfo_fpixels("1i")) / 96.0)
            except Exception:
                self.scale = 1.0
            # 即使 parent 被 withdraw，也创建独立可见的 Toplevel
            self.win = tk.Toplevel(parent)
            self._wait_var = tk.BooleanVar(master=parent, value=False)

        win = self.win
        win.title(title)
        win.configure(bg="#ffffff")
        w, h = int(460 * self.scale), int(180 * self.scale)
        win.geometry(f"{w}x{h}")
        win.minsize(w, h)
        win.resizable(False, False)

        try:
            win.attributes("-topmost", True)
        except Exception:
            pass
        try:
            # 确保不被隐藏的 parent 拖累
            win.deiconify()
            win.lift()
            win.focus_force()
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
            style.configure("P.TFrame", background="#ffffff")
            style.configure(
                "PTitle.TLabel",
                background="#ffffff",
                foreground="#0f766e",
                font=scaled_font(13, True, self.scale),
            )
            style.configure(
                "PStatus.TLabel",
                background="#ffffff",
                foreground="#333333",
                font=scaled_font(10, False, self.scale),
            )
            style.configure(
                "PPct.TLabel",
                background="#ffffff",
                foreground="#0f766e",
                font=scaled_font(11, True, self.scale),
            )
            style.configure(
                "P.Horizontal.TProgressbar",
                troughcolor="#e5e7eb",
                background="#0f766e",
                thickness=int(18 * self.scale),
            )
        except Exception:
            pass

        pad = int(20 * self.scale)
        frame = ttk.Frame(win, padding=pad, style="P.TFrame")
        frame.pack(fill="both", expand=True)

        self.title_label = ttk.Label(frame, text=title, style="PTitle.TLabel")
        self.title_label.pack(anchor="w")

        self.status_label = ttk.Label(frame, text=status, style="PStatus.TLabel")
        self.status_label.pack(anchor="w", pady=(12, 10))

        self.bar = ttk.Progressbar(
            frame,
            mode="indeterminate",
            style="P.Horizontal.TProgressbar",
            maximum=100,
        )
        self.bar.pack(fill="x", ipady=int(2 * self.scale))
        self.bar.start(12)

        self.pct_label = ttk.Label(frame, text="请稍候…", style="PPct.TLabel")
        self.pct_label.pack(anchor="e", pady=(8, 0))

        win.protocol("WM_DELETE_WINDOW", self._on_user_close)

        # 强制立刻绘制，再开始后台任务，避免「看不见进度条」
        try:
            sw = win.winfo_screenwidth()
            sh = win.winfo_screenheight()
            x = max(0, (sw - w) // 2)
            y = max(0, (sh - h) // 3)
            win.geometry(f"{w}x{h}+{x}+{y}")
        except Exception:
            pass
        win.update_idletasks()
        win.update()

        win.after(30, self._drain)

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

    def _set_determinate(self, pct: int) -> None:
        if self._indeterminate:
            try:
                self.bar.stop()
            except Exception:
                pass
            self.bar.configure(mode="determinate", maximum=100)
            self._indeterminate = False
        self.bar.configure(value=pct)
        self.pct_label.configure(text=f"{pct}%")

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
                    if pct > 0:
                        self._set_determinate(pct)
                    shown = name if len(name) <= 42 else ("…" + name[-41:])
                    self.status_label.configure(text=f"正在解压：{shown}")
                    try:
                        self.win.update_idletasks()
                    except Exception:
                        pass
                elif kind == "done":
                    _, title, message, is_error = event
                    self._finished = True
                    self._set_determinate(100)
                    self.status_label.configure(
                        text=("操作失败" if is_error else "解压完成")
                    )
                    self._result_title = title
                    self._result_message = message
                    self._result_error = is_error
                    try:
                        self.win.update_idletasks()
                        self.win.update()
                    except Exception:
                        pass
                    # 让用户清楚看到 100%，再弹结果
                    self.win.after(280, self._show_result_and_close)
        except queue.Empty:
            pass
        if not self._closed:
            self.win.after(30, self._drain)

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
            if self._indeterminate:
                self.bar.stop()
        except Exception:
            pass
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
        if self._owns_root:
            self.root.mainloop()
        else:
            try:
                self.win.lift()
                self.win.grab_set()
            except Exception:
                pass
            if self._wait_var is not None:
                self.root.wait_variable(self._wait_var)
            else:
                self.root.wait_window(self.win)


def show_alert(title: str, message: str, error: bool = False) -> None:
    """清晰的自定义完成/错误对话框（不使用易发虚的系统 MessageBox）。"""
    enable_high_dpi()
    import tkinter as tk
    from tkinter import ttk

    root = tk.Tk()
    scale = configure_tk_scaling(root)
    root.title(title)
    root.configure(bg="#ffffff")
    root.resizable(False, False)
    try:
        root.attributes("-topmost", True)
    except Exception:
        pass
    try:
        ico = resource_path("app.ico")
        if ico:
            root.iconbitmap(default=ico)
    except Exception:
        pass

    try:
        style = ttk.Style(root)
        if "clam" in style.theme_names():
            style.theme_use("clam")
        style.configure("A.TFrame", background="#ffffff")
        style.configure(
            "AOk.TButton",
            font=scaled_font(11, True, scale),
            padding=(18, 8),
        )
    except Exception:
        pass

    pad = int(24 * scale)
    frame = ttk.Frame(root, padding=pad, style="A.TFrame")
    frame.pack(fill="both", expand=True)

    accent = "#b91c1c" if error else "#0f766e"
    badge = "失败" if error else "已解压"
    if "压缩" in title or "已压缩" in message:
        badge = "失败" if error else "已压缩"
    if error:
        badge = "出错"

    tk.Label(
        frame,
        text=badge,
        font=scaled_font(16, True, scale),
        fg=accent,
        bg="#ffffff",
        anchor="w",
    ).pack(fill="x")

    tk.Label(
        frame,
        text=title,
        font=scaled_font(11, True, scale),
        fg="#111827",
        bg="#ffffff",
        anchor="w",
    ).pack(fill="x", pady=(10, 0))

    tk.Label(
        frame,
        text=message,
        font=scaled_font(10, False, scale),
        fg="#374151",
        bg="#ffffff",
        anchor="w",
        justify="left",
        wraplength=int(380 * scale),
    ).pack(fill="x", pady=(8, 18))

    def _ok() -> None:
        try:
            root.destroy()
        except Exception:
            pass

    btn = ttk.Button(frame, text="确定", style="AOk.TButton", command=_ok)
    btn.pack(anchor="e")
    root.bind("<Return>", lambda _e: _ok())
    root.bind("<Escape>", lambda _e: _ok())

    root.update_idletasks()
    w = max(int(400 * scale), root.winfo_reqwidth() + pad)
    h = max(int(200 * scale), root.winfo_reqheight() + pad)
    sw = root.winfo_screenwidth()
    sh = root.winfo_screenheight()
    root.geometry(f"{w}x{h}+{(sw - w) // 2}+{(sh - h) // 3}")
    try:
        root.deiconify()
        root.lift()
        root.focus_force()
    except Exception:
        pass
    root.mainloop()


def run_job_with_progress(
    title: str,
    status: str,
    worker: Callable[[ProgressFn, Callable[[str, str], None], Callable[[str, str], None]], None],
) -> None:
    """创建进度窗并在后台执行 worker。

    worker(progress, finish_ok, finish_error)
    """
    dialog = ProgressDialog(title=title, status=status)

    def _wrap() -> None:
        try:
            worker(dialog.progress, dialog.finish_ok, dialog.finish_error)
        except Exception as exc:
            dialog.finish_error(title, str(exc))

    threading.Thread(target=_wrap, daemon=True).start()
    dialog.run()
