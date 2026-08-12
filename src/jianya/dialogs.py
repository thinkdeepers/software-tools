"""DPI 感知的进度窗口与提示对话框。

进度使用 Canvas 自绘（不依赖易在打包环境下失效的 ttk.Progressbar）。
完成后在同一窗口内显示结果，避免再开第二个 Tk 导致文字发虚。
"""

from __future__ import annotations

import queue
import sys
import threading
import time
from typing import Any, Callable, Optional

from .dpi import configure_tk_scaling, enable_high_dpi, pick_ui_font
from .resources import resource_path


ProgressFn = Callable[[int, int, str], None]

# 进度窗至少显示这么久，避免解压太快时用户完全看不到进度条
_MIN_PROGRESS_MS = 800


class ProgressDialog:
    """始终置顶可见的进度窗口。

    - Canvas 自绘进度条 + 不确定动画
    - 收到真实进度后切换为确定进度
    - 完成后在同一窗口显示清晰结果，点「确定」再关闭
    """

    def __init__(
        self,
        title: str = "简压",
        status: str = "处理中…",
        parent: Any = None,
    ):
        enable_high_dpi()
        import tkinter as tk

        self.tk = tk
        self._events: "queue.Queue[tuple]" = queue.Queue()
        self._closed = False
        self._finished = False
        self._showing_result = False
        self._indeterminate = True
        self._anim_pos = 0.0
        self._pct = 0
        self._owns_root = parent is None
        self._result_title = ""
        self._result_message = ""
        self._result_error = False
        self._wait_var = None
        self._shown_at = time.monotonic()
        self._title_text = title

        if parent is None:
            root = tk.Tk()
            self.scale = configure_tk_scaling(root)
            self.root = root
            self.win = root
        else:
            self.root = parent.winfo_toplevel()
            try:
                self.scale = max(1.0, float(parent.winfo_fpixels("1i")) / 96.0)
            except Exception:
                self.scale = 1.0
            self.win = tk.Toplevel(parent)
            self._wait_var = tk.BooleanVar(master=self.root, value=False)

        win = self.win
        win.title(title)
        win.configure(bg="#ffffff")
        self._w = int(480 * self.scale)
        self._h = int(200 * self.scale)
        win.geometry(f"{self._w}x{self._h}")
        win.minsize(self._w, self._h)
        win.resizable(False, False)

        try:
            win.attributes("-topmost", True)
        except Exception:
            pass
        try:
            win.transient(self.root)
        except Exception:
            pass
        try:
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

        pad = int(22 * self.scale)
        self._body = tk.Frame(win, bg="#ffffff", padx=pad, pady=pad)
        self._body.pack(fill="both", expand=True)

        self.title_label = tk.Label(
            self._body,
            text=title,
            font=pick_ui_font(win, 13, True),
            fg="#0f766e",
            bg="#ffffff",
            anchor="w",
        )
        self.title_label.pack(fill="x")

        self.status_label = tk.Label(
            self._body,
            text=status,
            font=pick_ui_font(win, 10, False),
            fg="#333333",
            bg="#ffffff",
            anchor="w",
        )
        self.status_label.pack(fill="x", pady=(12, 14))

        bar_h = max(16, int(20 * self.scale))
        self.bar_canvas = tk.Canvas(
            self._body,
            height=bar_h,
            bg="#e5e7eb",
            highlightthickness=0,
            bd=0,
        )
        self.bar_canvas.pack(fill="x")
        self._bar_rect = self.bar_canvas.create_rectangle(
            0, 0, 0, bar_h, fill="#0f766e", width=0
        )

        self.pct_label = tk.Label(
            self._body,
            text="请稍候…",
            font=pick_ui_font(win, 11, True),
            fg="#0f766e",
            bg="#ffffff",
            anchor="e",
        )
        self.pct_label.pack(fill="x", pady=(10, 0))

        win.protocol("WM_DELETE_WINDOW", self._on_user_close)

        try:
            sw = win.winfo_screenwidth()
            sh = win.winfo_screenheight()
            x = max(0, (sw - self._w) // 2)
            y = max(0, (sh - self._h) // 3)
            win.geometry(f"{self._w}x{self._h}+{x}+{y}")
        except Exception:
            pass

        # 强制立刻绘制，再开始后台任务
        win.update_idletasks()
        win.update()
        self._draw_bar()
        win.after(20, self._drain)
        win.after(40, self._animate)

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

    def dismiss(self) -> None:
        """立即关闭进度窗且不弹出结果（例如改输密码前）。"""
        self._finished = True
        self.close()

    def _draw_bar(self) -> None:
        if self._closed or self._showing_result:
            return
        try:
            self.bar_canvas.update_idletasks()
            width = max(int(self.bar_canvas.winfo_width()), int(400 * self.scale))
            height = max(int(self.bar_canvas.winfo_height()), 16)
        except Exception:
            return

        if self._indeterminate:
            block = max(48, int(width * 0.28))
            x0 = int(self._anim_pos) % (width + block) - block
            x1 = x0 + block
            self.bar_canvas.coords(self._bar_rect, x0, 0, x1, height)
        else:
            fill = int(width * max(0, min(100, self._pct)) / 100.0)
            self.bar_canvas.coords(self._bar_rect, 0, 0, fill, height)

    def _animate(self) -> None:
        if self._closed or self._showing_result:
            return
        if self._indeterminate:
            try:
                width = max(int(self.bar_canvas.winfo_width()), 200)
            except Exception:
                width = 200
            self._anim_pos += max(6.0, width * 0.035)
            self._draw_bar()
        if not self._closed and not self._showing_result:
            self.win.after(40, self._animate)

    def _set_determinate(self, pct: int) -> None:
        self._indeterminate = False
        self._pct = max(0, min(100, pct))
        self.pct_label.configure(text=f"{self._pct}%")
        self._draw_bar()

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
                    self.status_label.configure(text=f"正在处理：{shown}")
                    try:
                        self.win.update_idletasks()
                    except Exception:
                        pass
                elif kind == "done":
                    _, title, message, is_error = event
                    self._finished = True
                    self._set_determinate(100)
                    self.status_label.configure(
                        text=("操作失败" if is_error else "处理完成")
                    )
                    self._result_title = title
                    self._result_message = message
                    self._result_error = is_error
                    try:
                        self.win.update_idletasks()
                        self.win.update()
                    except Exception:
                        pass
                    elapsed_ms = int((time.monotonic() - self._shown_at) * 1000)
                    delay = max(220, _MIN_PROGRESS_MS - elapsed_ms)
                    self.win.after(delay, self._show_result_inplace)
        except queue.Empty:
            pass
        if not self._closed and not self._showing_result:
            self.win.after(30, self._drain)

    def _show_result_inplace(self) -> None:
        """在同一窗口切换为结果页，避免第二个 Tk 发虚。"""
        if self._closed or self._showing_result:
            return
        self._showing_result = True

        title = self._result_title or "简压"
        message = self._result_message or ""
        is_error = self._result_error
        accent = "#b91c1c" if is_error else "#0f766e"
        badge = "出错" if is_error else "已完成"
        if not is_error:
            if "解压" in title or "已解压" in message or "已解压" in title:
                badge = "已解压"
            elif "压缩" in title or "已压缩" in message:
                badge = "已压缩"

        for child in list(self._body.winfo_children()):
            try:
                child.destroy()
            except Exception:
                pass

        win = self.win
        tk = self.tk
        win.title(title)
        new_h = max(self._h, int(260 * self.scale))
        try:
            geo = win.geometry()
            # 保持当前位置，只加高
            if "+" in geo:
                _size, rest = geo.split("+", 1)
                win.geometry(f"{self._w}x{new_h}+{rest}")
            else:
                win.geometry(f"{self._w}x{new_h}")
        except Exception:
            win.geometry(f"{self._w}x{new_h}")

        try:
            tk.Label(
                self._body,
                text=badge,
                font=pick_ui_font(win, 18, True),
                fg=accent,
                bg="#ffffff",
                anchor="w",
            ).pack(fill="x")

            tk.Label(
                self._body,
                text=title,
                font=pick_ui_font(win, 11, True),
                fg="#111827",
                bg="#ffffff",
                anchor="w",
            ).pack(fill="x", pady=(12, 0))

            tk.Label(
                self._body,
                text=message,
                font=pick_ui_font(win, 10, False),
                fg="#374151",
                bg="#ffffff",
                anchor="w",
                justify="left",
                wraplength=int(400 * self.scale),
            ).pack(fill="x", pady=(8, 20))

            btn = tk.Button(
                self._body,
                text="确定",
                font=pick_ui_font(win, 11, True),
                fg="#ffffff",
                bg=accent,
                activeforeground="#ffffff",
                activebackground=accent,
                relief="flat",
                padx=int(22 * self.scale),
                pady=int(8 * self.scale),
                cursor="hand2",
                command=self.close,
            )
            btn.pack(anchor="e")
            win.bind("<Return>", lambda _e: self.close())
            win.bind("<Escape>", lambda _e: self.close())

            try:
                win.attributes("-topmost", True)
                win.deiconify()
                win.lift()
                win.focus_force()
                btn.focus_set()
            except Exception:
                pass
            try:
                win.update_idletasks()
                win.update()
            except Exception:
                pass
        except Exception:
            # 结果页绘制失败时回退到系统/备用提示，避免留下空白白框
            try:
                self.close()
            except Exception:
                pass
            show_alert(title, message, error=is_error)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self.win.grab_release()
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
        elif self._owns_root:
            try:
                self.root.quit()
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
    """清晰的完成/错误对话框。

    Windows 优先使用系统 MessageBox（DPI 感知后 ClearType 清晰）；
    其它平台或失败时使用单窗口 Tk。
    """
    enable_high_dpi()
    if sys.platform == "win32":
        try:
            import ctypes

            # 再声明一次线程 DPI，避免被拉伸
            try:
                ctypes.windll.user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))
            except Exception:
                pass
            flags = 0x00000010 if error else 0x00000040  # ICONERROR / ICONINFORMATION
            flags |= 0x00040000  # MB_TOPMOST
            ctypes.windll.user32.MessageBoxW(None, str(message), str(title), flags)
            return
        except Exception:
            pass

    import tkinter as tk

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

    pad = int(24 * scale)
    frame = tk.Frame(root, bg="#ffffff", padx=pad, pady=pad)
    frame.pack(fill="both", expand=True)

    accent = "#b91c1c" if error else "#0f766e"
    badge = "出错" if error else "已完成"
    if not error:
        if "解压" in title or "已解压" in message:
            badge = "已解压"
        elif "压缩" in title or "已压缩" in message:
            badge = "已压缩"

    tk.Label(
        frame,
        text=badge,
        font=pick_ui_font(root, 16, True),
        fg=accent,
        bg="#ffffff",
        anchor="w",
    ).pack(fill="x")

    tk.Label(
        frame,
        text=title,
        font=pick_ui_font(root, 11, True),
        fg="#111827",
        bg="#ffffff",
        anchor="w",
    ).pack(fill="x", pady=(10, 0))

    tk.Label(
        frame,
        text=message,
        font=pick_ui_font(root, 10, False),
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

    tk.Button(
        frame,
        text="确定",
        font=pick_ui_font(root, 11, True),
        fg="#ffffff",
        bg=accent,
        activeforeground="#ffffff",
        activebackground=accent,
        relief="flat",
        padx=18,
        pady=8,
        cursor="hand2",
        command=_ok,
    ).pack(anchor="e")
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
