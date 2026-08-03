"""极简图形界面：压缩 / 解压，以及双击压缩包时的预览窗口。"""

from __future__ import annotations

import os
import queue
import sys
import threading
from pathlib import Path
from typing import List, Optional, Sequence

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


def launch(open_archives: Optional[Sequence[str]] = None) -> int:
    _enable_high_dpi()
    try:
        import tkinter as tk
        from tkinter import filedialog, messagebox, simpledialog, ttk
    except Exception as exc:  # pragma: no cover - 缺少 tkinter 时
        print(f"无法启动图形界面（缺少 tkinter）：{exc}", file=sys.stderr)
        print("你也可以使用命令行：jianya --compress <文件> 或 jianya --extract <压缩包>")
        return 1

    app = _App(tk, filedialog, messagebox, simpledialog, ttk)
    archives = [a for a in (open_archives or []) if a]
    if archives:
        # 双击压缩包：直接进入预览，不显示主按钮页。
        for archive in archives:
            app.open_preview(archive)
        # 若预览窗口都未能打开，再回退到主界面。
        if not app._preview_windows:
            app.run()
        else:
            app.run(show_main=False)
    else:
        app.run()
    return 0


class _App:
    """封装 tkinter 主窗口，避免在模块顶层导入 tkinter。"""

    def __init__(self, tk, filedialog, messagebox, simpledialog, ttk):
        self.tk = tk
        self.filedialog = filedialog
        self.messagebox = messagebox
        self.simpledialog = simpledialog
        self.ttk = ttk

        self._events: "queue.Queue[tuple]" = queue.Queue()
        self._busy = False
        self._preview_windows: List["_PreviewWindow"] = []
        self._main_built = False

        root = tk.Tk()
        self.root = root
        root.title(f"简压 {__version__} — 简洁 · 免费 · 无广告")
        root.withdraw()

        # 按屏幕真实 DPI 缩放窗口尺寸（96 dpi 为 100%）。声明 DPI 感知后，
        # 文字/图标由系统清晰绘制，窗口尺寸也随缩放等比放大以容纳内容。
        try:
            scale = root.winfo_fpixels("1i") / 96.0
        except Exception:
            scale = 1.0
        if scale < 1.0:
            scale = 1.0
        self._scale = scale
        base_w, base_h = 480, 520
        self._win_w, self._win_h = int(base_w * scale), int(base_h * scale)
        root.configure(bg="white")

        self._set_window_icon()
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
        if self._main_built:
            return
        self._main_built = True
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
            style.configure("Preview.Treeview", font=("Microsoft YaHei", 9), rowheight=int(24 * self._scale))
        except Exception:
            pass

        self.root.geometry(f"{self._win_w}x{self._win_h}")
        # 固定最小尺寸，保证底部按钮与说明文字不会因窗口缩小被遮挡。
        self.root.minsize(self._win_w, self._win_h)

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
            header, text="压缩统一为 ZIP · 解压支持常见格式 · 可加密",
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
            middle, text="选择文件开始压缩，或选择压缩包预览/解压",
            style="Status.TLabel", font=("Microsoft YaHei", 9),
        )
        self.status.pack(fill="x")

    # ------------------------------------------------------------------
    # 密码 / 预览
    # ------------------------------------------------------------------
    def ask_password(self, title: str = "输入密码", prompt: str = "请输入密码：") -> Optional[str]:
        """弹出密码输入框；取消返回 None，空字符串表示用户确认不使用密码。"""
        parent = self.root
        result = self.simpledialog.askstring(title, prompt, show="*", parent=parent)
        return result

    def open_preview(self, archive: str, password: Optional[str] = None) -> None:
        archive_path = Path(archive)
        if not archive_path.is_file():
            self.messagebox.showerror("简压", f"文件不存在：{archive_path}")
            return
        if not core.is_archive(archive_path):
            self.messagebox.showerror("简压", f"不支持的压缩格式：{archive_path.name}")
            return

        pwd = password
        try:
            members = core.list_archive(archive_path, password=pwd)
        except core.PasswordRequiredError:
            pwd = self.ask_password("加密压缩包", f"{archive_path.name} 已加密，请输入密码：")
            if pwd is None:
                return
            try:
                members = core.list_archive(archive_path, password=pwd)
            except core.PasswordRequiredError:
                self.messagebox.showerror("简压", "密码不正确，或压缩包已加密。")
                return
            except core.ArchiveError as exc:
                self.messagebox.showerror("简压", str(exc))
                return
        except core.ArchiveError as exc:
            self.messagebox.showerror("简压", str(exc))
            return

        # 内容加密但列表可读：若检测到加密条目且尚未提供密码，提前询问。
        if pwd is None and any(m.encrypted for m in members):
            pwd = self.ask_password("加密压缩包", f"{archive_path.name} 含加密文件，解压前请输入密码：")
            if pwd is None:
                # 仍允许预览列表，解压时再问。
                pwd = None

        preview = _PreviewWindow(self, archive_path, members, pwd)
        self._preview_windows.append(preview)

    # ------------------------------------------------------------------
    # 事件处理
    # ------------------------------------------------------------------
    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        if not self._main_built:
            return
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

        password = None
        if self.messagebox.askyesno("简压", "是否使用密码加密此压缩包？"):
            password = self.ask_password("设置密码", "请输入压缩密码：")
            if password is None:
                return
            password = password.strip()
            if not password:
                self.messagebox.showerror("简压", "密码不能为空。")
                return

        self._start_task(self._do_compress, paths, output, password)

    def _on_extract(self) -> None:
        if self._busy:
            return
        archive = self.filedialog.askopenfilename(
            title="选择要预览 / 解压的压缩包",
            filetypes=[
                ("压缩包", "*.zip *.tar *.gz *.tgz *.bz2 *.xz *.7z *.rar"),
                ("所有文件", "*.*"),
            ],
        )
        if not archive:
            return
        self.open_preview(archive)

    def _start_task(self, target, *args) -> None:
        self._set_busy(True)
        if self._main_built:
            self.progress.configure(value=0)
            self.status.configure(text="处理中…")
        thread = threading.Thread(target=target, args=args, daemon=True)
        thread.start()

    def _progress(self, done: int, total: int, name: str) -> None:
        self._events.put(("progress", done, total, name))

    def _do_compress(self, paths: List[str], output: str, password: Optional[str]) -> None:
        try:
            result = core.compress_to_zip(
                paths, output=output, progress=self._progress, password=password
            )
            tip = "（已加密）" if password else ""
            self._events.put(("done", f"压缩完成{tip}", str(result)))
        except Exception as exc:
            self._events.put(("error", str(exc)))

    def _do_extract(
        self,
        archive: str,
        output: Optional[str],
        password: Optional[str],
    ) -> None:
        try:
            result = core.extract_archive(
                archive,
                output_dir=output,
                progress=self._progress,
                password=password,
            )
            self._events.put(("done", "解压完成", str(result)))
        except core.PasswordRequiredError as exc:
            self._events.put(("need_password", archive, output, str(exc)))
        except Exception as exc:
            self._events.put(("error", str(exc)))

    def extract_archive_interactive(
        self,
        archive: str,
        output: Optional[str] = None,
        password: Optional[str] = None,
    ) -> None:
        """供预览窗口调用：选择目录后解压。"""
        if self._busy:
            return
        if output is None:
            chosen = self.filedialog.askdirectory(
                title="选择解压到的目录（取消则解压到同名目录）"
            )
            # 取消目录对话框 → 使用默认同名目录；若连默认也不想，用户可关预览。
            # 这里：点取消表示使用默认目录（与旧行为一致：取消则解压到同名目录）。
            output = chosen or None
        self._start_task(self._do_extract, archive, output, password)

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
                    "· 双击压缩包可预览内容并解压\n"
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
                    if self._main_built:
                        self.progress.configure(value=pct)
                        self.status.configure(text=f"[{pct}%] {name}")
                elif kind == "done":
                    _, title, path = event
                    if self._main_built:
                        self.progress.configure(value=100)
                        self.status.configure(text=f"{title}：{path}")
                    self._set_busy(False)
                    self.messagebox.showinfo("简压", f"{title}\n{path}")
                elif kind == "need_password":
                    _, archive, output, msg = event
                    self._set_busy(False)
                    pwd = self.ask_password("需要密码", f"{Path(archive).name}\n{msg}")
                    if pwd is None:
                        if self._main_built:
                            self.status.configure(text="已取消解压")
                        continue
                    self._start_task(self._do_extract, archive, output, pwd)
                elif kind == "error":
                    _, msg = event
                    self._set_busy(False)
                    if self._main_built:
                        self.status.configure(text="操作失败")
                    self.messagebox.showerror("简压", msg)
        except queue.Empty:
            pass
        self.root.after(80, self._drain_events)

    def run(self, show_main: bool = True) -> None:
        if show_main:
            self._build_ui()
            self.root.deiconify()
        else:
            # 仅预览模式：保留隐藏的 root 作为 Tk 主循环载体。
            self.root.protocol("WM_DELETE_WINDOW", self._on_root_close)
        self.root.mainloop()

    def _on_root_close(self) -> None:
        # 预览模式：所有预览关掉后退出。
        if self._preview_windows:
            return
        self.root.destroy()

    def _preview_closed(self, preview: "_PreviewWindow") -> None:
        if preview in self._preview_windows:
            self._preview_windows.remove(preview)
        if not self._main_built and not self._preview_windows:
            self.root.quit()
            self.root.destroy()


class _PreviewWindow:
    """压缩包预览窗口：列出内容，并可一键解压。"""

    def __init__(
        self,
        app: _App,
        archive: Path,
        members: List[core.ArchiveMember],
        password: Optional[str],
    ):
        self.app = app
        self.archive = archive
        self.members = members
        self.password = password
        tk, ttk = app.tk, app.ttk

        win = tk.Toplevel(app.root)
        self.win = win
        win.title(f"预览 — {archive.name}")
        scale = app._scale
        w, h = int(560 * scale), int(420 * scale)
        win.geometry(f"{w}x{h}")
        win.minsize(int(420 * scale), int(300 * scale))
        win.configure(bg="white")

        try:
            win.iconbitmap(default=resource_path("app.ico") or "")
        except Exception:
            pass

        frame = ttk.Frame(win, padding=12, style="White.TFrame")
        frame.pack(fill="both", expand=True)

        ttk.Label(
            frame,
            text=archive.name,
            style="Title.TLabel",
            font=("Microsoft YaHei", 12, "bold"),
        ).pack(anchor="w")
        info = f"{len(members)} 个项目"
        if any(m.encrypted for m in members) or password:
            info += " · 已加密"
        ttk.Label(frame, text=info, style="Sub.TLabel", font=("Microsoft YaHei", 9)).pack(
            anchor="w", pady=(2, 8)
        )

        tree_frame = ttk.Frame(frame, style="White.TFrame")
        tree_frame.pack(fill="both", expand=True)

        columns = ("size", "compressed", "flag")
        tree = ttk.Treeview(
            tree_frame,
            columns=columns,
            show="tree headings",
            style="Preview.Treeview",
        )
        tree.heading("#0", text="名称")
        tree.heading("size", text="大小")
        tree.heading("compressed", text="压缩后")
        tree.heading("flag", text="")
        tree.column("#0", width=int(280 * scale), stretch=True)
        tree.column("size", width=int(90 * scale), anchor="e")
        tree.column("compressed", width=int(90 * scale), anchor="e")
        tree.column("flag", width=int(60 * scale), anchor="center")

        scroll = ttk.Scrollbar(tree_frame, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=scroll.set)
        tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

        for member in members:
            flag = "加密" if member.encrypted else ("目录" if member.is_dir else "")
            tree.insert(
                "",
                "end",
                text=member.name,
                values=(
                    "" if member.is_dir else _format_size(member.size),
                    "" if member.is_dir else _format_size(member.compressed_size),
                    flag,
                ),
            )

        buttons = ttk.Frame(frame, style="White.TFrame")
        buttons.pack(fill="x", pady=(10, 0))
        ttk.Button(buttons, text="解压到…", command=self._extract_to).pack(side="right")
        ttk.Button(buttons, text="解压到同名目录", command=self._extract_here).pack(
            side="right", padx=(0, 8)
        )
        ttk.Button(buttons, text="关闭", command=win.destroy).pack(side="left")

        win.protocol("WM_DELETE_WINDOW", self._close)
        win.focus_force()

    def _close(self) -> None:
        self.win.destroy()
        self.app._preview_closed(self)

    def _extract_here(self) -> None:
        self.app.extract_archive_interactive(
            str(self.archive),
            output=None,
            password=self.password,
        )

    def _extract_to(self) -> None:
        chosen = self.app.filedialog.askdirectory(title="选择解压到的目录")
        if not chosen:
            return
        self.app.extract_archive_interactive(
            str(self.archive),
            output=chosen,
            password=self.password,
        )


def _format_size(size: int) -> str:
    if size < 1024:
        return f"{size} B"
    if size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    if size < 1024 * 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MB"
    return f"{size / (1024 * 1024 * 1024):.2f} GB"
