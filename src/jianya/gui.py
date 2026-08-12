"""极简图形界面：压缩 / 解压，以及双击压缩包时的预览窗口。"""

from __future__ import annotations

import atexit
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import List, Optional, Sequence, Set

from . import __version__
from . import core
from .dialogs import ProgressDialog, show_alert
from .dpi import configure_tk_scaling, enable_high_dpi
from .resources import resource_path


def _rmtree_quiet(path: Path) -> None:
    try:
        shutil.rmtree(path, ignore_errors=True)
    except Exception:
        pass


def cleanup_temp_dirs(dirs: List[Path]) -> None:
    """删除并清空临时目录列表（关闭预览 / 退出时调用）。"""
    for path in list(dirs):
        _rmtree_quiet(path)
    dirs.clear()


def launch(open_archives: Optional[Sequence[str]] = None) -> int:
    enable_high_dpi()
    try:
        import tkinter as tk
        from tkinter import filedialog, simpledialog, ttk
    except Exception as exc:  # pragma: no cover - 缺少 tkinter 时
        print(f"无法启动图形界面（缺少 tkinter）：{exc}", file=sys.stderr)
        print("你也可以使用命令行：jianya --compress <文件> 或 jianya --extract <压缩包>")
        return 1

    app = _App(tk, filedialog, simpledialog, ttk)
    archives = [a for a in (open_archives or []) if a]
    if archives:
        for archive in archives:
            app.open_preview(archive)
        if not app._preview_windows:
            app.run()
        else:
            app.run(show_main=False)
    else:
        app.run()
    return 0


class _App:
    """封装 tkinter 主窗口，避免在模块顶层导入 tkinter。"""

    def __init__(self, tk, filedialog, simpledialog, ttk):
        self.tk = tk
        self.filedialog = filedialog
        self.simpledialog = simpledialog
        self.ttk = ttk

        self._events: "queue.Queue[tuple]" = queue.Queue()
        self._busy = False
        self._preview_windows: List["_PreviewWindow"] = []
        self._main_built = False
        self._progress_dialog: Optional[ProgressDialog] = None
        # 双击打开产生的临时目录；关闭预览 / 退出时清理
        self._temp_dirs: List[Path] = []
        self._temp_dir_set: Set[Path] = set()

        root = tk.Tk()
        root.withdraw()  # 立刻隐藏，避免启动时左上角闪一下
        self.root = root
        root.title(f"简压 {__version__} — 简洁 · 免费 · 无广告")
        root.protocol("WM_DELETE_WINDOW", self._on_app_exit)
        atexit.register(self._cleanup_all_temps)

        self._scale = configure_tk_scaling(root)
        if self._scale < 1.0:
            self._scale = 1.0
        base_w, base_h = 480, 520
        self._win_w, self._win_h = int(base_w * self._scale), int(base_h * self._scale)
        root.configure(bg="white")

        self._set_window_icon()
        root.after(50, self._drain_events)

    def _set_window_icon(self) -> None:
        tk = self.tk
        ico = resource_path("app.ico")
        if ico:
            try:
                self.root.iconbitmap(default=ico)
            except Exception:
                pass
        png = resource_path("app.png")
        if png:
            try:
                self._icon_image = tk.PhotoImage(file=png)
                self.root.iconphoto(True, self._icon_image)
            except Exception:
                pass

    def _info(self, message: str, title: str = "简压") -> None:
        show_alert(title, message, error=False)

    def _error(self, message: str, title: str = "简压") -> None:
        show_alert(title, message, error=True)

    # ------------------------------------------------------------------
    # 界面
    # ------------------------------------------------------------------
    def _build_ui(self) -> None:
        if self._main_built:
            return
        self._main_built = True
        tk, ttk = self.tk, self.ttk
        scale = self._scale

        WHITE = "#ffffff"
        try:
            style = ttk.Style()
            if "clam" in style.theme_names():
                style.theme_use("clam")
            style.configure("White.TFrame", background=WHITE)
            style.configure("White.TLabel", background=WHITE)
            style.configure("Title.TLabel", background=WHITE, foreground="#0f766e")
            style.configure("Sub.TLabel", background=WHITE, foreground="#555555")
            style.configure("Status.TLabel", background=WHITE, foreground="#555555")
            style.configure(
                "Big.TButton",
                font=("Microsoft YaHei UI", 16, "bold"),
                padding=int(18 * scale),
            )
            style.configure(
                "Link.TButton", font=("Microsoft YaHei UI", 9), padding=int(6 * scale)
            )
            style.configure("Footer.TLabel", background=WHITE, foreground="#9aa0a6")
            style.configure(
                "Preview.Treeview",
                font=("Microsoft YaHei UI", 9),
                rowheight=int(26 * scale),
            )
        except Exception:
            pass

        self.root.geometry(f"{self._win_w}x{self._win_h}")
        self.root.minsize(self._win_w, self._win_h)

        container = ttk.Frame(self.root, padding=int(24 * scale), style="White.TFrame")
        container.pack(fill="both", expand=True)

        header = ttk.Frame(container, style="White.TFrame")
        header.pack(side="top", fill="x")
        ttk.Label(
            header,
            text="简压",
            style="Title.TLabel",
            font=("Microsoft YaHei UI", 22, "bold"),
        ).pack(pady=(0, 4))
        ttk.Label(
            header,
            text="压缩统一为 ZIP · 解压支持常见格式 · 可加密",
            style="Sub.TLabel",
            font=("Microsoft YaHei UI", 10),
        ).pack(pady=(0, 18))

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

        footer = ttk.Frame(container, style="White.TFrame")
        footer.pack(side="bottom", fill="x", pady=(10, 0))
        ttk.Separator(footer, orient="horizontal").pack(fill="x", pady=(0, 8))
        for text in (
            "本软件由 Opus 4.8 模型自动生成",
            "致力于造福全人类，共建简单、和平、美好的生态",
            "—— 洞穴理论工作室 出品",
        ):
            ttk.Label(
                footer,
                text=text,
                style="Footer.TLabel",
                font=("Microsoft YaHei UI", 8),
                anchor="center",
                justify="center",
            ).pack(fill="x")

        bottom = ttk.Frame(container, style="White.TFrame")
        bottom.pack(side="bottom", fill="x", pady=(14, 0))
        inner = ttk.Frame(bottom, style="White.TFrame")
        inner.pack(anchor="center")
        ttk.Button(
            inner,
            text="设为默认打开",
            style="Link.TButton",
            command=self._on_install_menu,
        ).pack(side="left")
        ttk.Button(
            inner,
            text="取消默认打开",
            style="Link.TButton",
            command=self._on_uninstall_menu,
        ).pack(side="left", padx=(8, 0))

        middle = ttk.Frame(container, style="White.TFrame")
        middle.pack(side="top", fill="both", expand=True)

        self.progress = ttk.Progressbar(middle, mode="determinate")
        self.progress.pack(fill="x", pady=(22, 6))

        self.status = ttk.Label(
            middle,
            text="选择文件开始压缩，或选择压缩包预览/解压",
            style="Status.TLabel",
            font=("Microsoft YaHei UI", 9),
        )
        self.status.pack(fill="x")

    # ------------------------------------------------------------------
    # 密码 / 预览
    # ------------------------------------------------------------------
    def ask_password(
        self,
        title: str = "输入密码",
        prompt: str = "请输入密码：",
        parent=None,
    ) -> Optional[str]:
        dlg_parent = parent or self.root
        return self.simpledialog.askstring(
            title, prompt, show="*", parent=dlg_parent
        )

    def open_preview(
        self, archive: str, password: Optional[str] = None
    ) -> Optional["_PreviewWindow"]:
        archive_path = Path(archive)
        if not archive_path.is_file():
            self._error(f"文件不存在：{archive_path}")
            return None
        if not core.is_archive(archive_path):
            self._error(f"不支持的压缩格式：{archive_path.name}")
            return None

        pwd = password
        try:
            members = core.list_archive(archive_path, password=pwd)
        except core.PasswordRequiredError:
            pwd = self.ask_password(
                "加密压缩包", f"{archive_path.name} 已加密，请输入密码："
            )
            if pwd is None:
                return None
            try:
                members = core.list_archive(archive_path, password=pwd)
            except core.PasswordRequiredError:
                self._error("密码不正确，或压缩包已加密。")
                return None
            except core.ArchiveError as exc:
                self._error(str(exc))
                return None
        except core.ArchiveError as exc:
            self._error(str(exc))
            return None

        if pwd is None and any(m.encrypted for m in members):
            pwd = self.ask_password(
                "加密压缩包", f"{archive_path.name} 含加密文件，请输入密码："
            )

        preview = _PreviewWindow(self, archive_path, members, pwd)
        self._preview_windows.append(preview)
        return preview

    def register_temp_dir(self, path: Path, owner: Optional["_PreviewWindow"] = None) -> None:
        """登记临时目录，供关闭预览 / 退出时清理。"""
        resolved = path
        if resolved in self._temp_dir_set:
            if owner is not None:
                owner.own_temp_dir(resolved, register_app=False)
            return
        self._temp_dir_set.add(resolved)
        self._temp_dirs.append(resolved)
        if owner is not None:
            owner.own_temp_dir(resolved, register_app=False)

    def unregister_temp_dir(self, path: Path) -> None:
        if path in self._temp_dir_set:
            self._temp_dir_set.discard(path)
        try:
            self._temp_dirs.remove(path)
        except ValueError:
            pass

    def _cleanup_all_temps(self) -> None:
        cleanup_temp_dirs(self._temp_dirs)
        self._temp_dir_set.clear()
        for preview in list(self._preview_windows):
            preview._temp_dirs.clear()

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
        for preview in self._preview_windows:
            preview.set_busy(busy)

    def _on_compress(self) -> None:
        if self._busy:
            return
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
        # 使用系统对话框询问是否加密（DPI 已在进程级声明）
        try:
            import ctypes

            MB_YESNO = 0x4
            MB_ICONQUESTION = 0x20
            IDYES = 6
            ret = ctypes.windll.user32.MessageBoxW(
                None,
                "是否使用密码加密此压缩包？",
                "简压",
                MB_YESNO | MB_ICONQUESTION,
            )
            want_encrypt = ret == IDYES
        except Exception:
            want_encrypt = False

        if want_encrypt:
            password = self.ask_password("设置密码", "请输入压缩密码：")
            if password is None:
                return
            password = password.strip()
            if not password:
                self._error("密码不能为空。")
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

        # 进度窗挂到可见窗口上（预览窗优先）；避免挂在已隐藏的 root 导致看不见
        parent = None
        if self._preview_windows:
            parent = self._preview_windows[-1].win
        elif self._main_built:
            parent = self.root

        self._progress_dialog = ProgressDialog(
            title="简压 — 正在处理",
            status="正在处理，请稍候…",
            parent=parent,
        )
        thread = threading.Thread(target=target, args=args, daemon=True)
        thread.start()
        self._progress_dialog.run()
        self._progress_dialog = None

    def _progress(self, done: int, total: int, name: str) -> None:
        self._events.put(("progress", done, total, name))
        dlg = self._progress_dialog
        if dlg is not None:
            dlg.progress(done, total, name)

    def _do_compress(self, paths: List[str], output: str, password: Optional[str]) -> None:
        try:
            result = core.compress_to_zip(
                paths, output=output, progress=self._progress, password=password
            )
            tip = "（已加密）" if password else ""
            self._events.put(("done", f"已压缩{tip}", str(result)))
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
            self._events.put(("done", "已解压", str(result)))
        except core.PasswordRequiredError as exc:
            self._events.put(("need_password", archive, output, str(exc)))
        except Exception as exc:
            self._events.put(("error", str(exc)))

    def extract_archive_interactive(
        self,
        archive: str,
        output: Optional[str] = None,
        password: Optional[str] = None,
        ask_dir: bool = False,
    ) -> None:
        """供预览窗口调用。

        ask_dir=True：「解压到…」弹出选目录，在所选文件夹下创建同名目录（冲突则 (1)）。
        ask_dir=False：「解压到当前文件夹」在压缩包所在目录下创建同名目录（冲突则 (1)）。
        """
        if self._busy:
            return
        if ask_dir:
            chosen = self.filedialog.askdirectory(title="选择解压到的文件夹")
            if not chosen:
                return
            output = chosen
        else:
            # 父目录 = 压缩包所在处；实际子目录由 core 按同名+(1) 规则创建
            output = str(Path(archive).resolve().parent)
        self._start_task(self._do_extract, archive, output, password)

    def open_archive_member(
        self,
        archive: Path,
        member_name: str,
        password: Optional[str] = None,
        *,
        encrypted: bool = False,
        owner_preview: Optional["_PreviewWindow"] = None,
    ) -> None:
        """双击预览列表中的文件：解压到临时目录后打开。

        - 普通文件：用系统默认程序打开
        - 嵌套压缩包：再开一层预览窗口
        - 加密文件：先提示输入密码
        """
        if self._busy:
            return

        parent_win = owner_preview.win if owner_preview is not None else None
        pwd = (password or "").strip() or None
        if encrypted and not pwd:
            pwd = self.ask_password(
                "加密文件",
                f"{Path(member_name).name} 已加密，请输入密码：",
                parent=parent_win,
            )
            if pwd is None:
                return
            if owner_preview is not None and not owner_preview.password:
                owner_preview.password = pwd

        tmp = Path(tempfile.mkdtemp(prefix="jianya-open-"))
        try:
            out = self._extract_member_with_password_prompt(
                archive,
                member_name,
                tmp,
                pwd,
                parent_win=parent_win,
                owner_preview=owner_preview,
            )
            if out is None:
                _rmtree_quiet(tmp)
                return
        except Exception as exc:
            _rmtree_quiet(tmp)
            self._error(str(exc))
            return

        # 嵌套压缩包：再开一层预览，临时目录归属新预览窗
        if out.is_file() and core.is_archive(out):
            nested = self.open_preview(str(out))
            if nested is None:
                _rmtree_quiet(tmp)
                return
            self.register_temp_dir(tmp, owner=nested)
            if owner_preview is not None:
                owner_preview.status.configure(
                    text=f"已打开嵌套压缩包：{out.name}"
                )
            return

        # 普通文件：临时目录归属当前预览（或应用级）
        self.register_temp_dir(tmp, owner=owner_preview)
        self._reveal_path(out)
        if owner_preview is not None:
            owner_preview.status.configure(text=f"已打开：{Path(member_name).name}")

    def _extract_member_with_password_prompt(
        self,
        archive: Path,
        member_name: str,
        tmp: Path,
        password: Optional[str],
        *,
        parent_win=None,
        owner_preview: Optional["_PreviewWindow"] = None,
    ) -> Optional[Path]:
        pwd = password
        for _attempt in range(3):
            try:
                return core.extract_member(
                    archive, member_name, output_dir=tmp, password=pwd
                )
            except core.PasswordRequiredError as exc:
                pwd = self.ask_password(
                    "加密压缩包",
                    f"{Path(member_name).name}\n{exc}\n请输入密码：",
                    parent=parent_win,
                )
                if pwd is None:
                    return None
                if owner_preview is not None:
                    owner_preview.password = pwd
        self._error("密码不正确，无法打开该文件。")
        return None

    def _reveal_path(self, path: Path) -> None:
        """用系统默认程序打开文件。"""
        target = str(path)
        try:
            if sys.platform == "win32":
                os.startfile(target)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", target], close_fds=True)
            else:
                subprocess.Popen(["xdg-open", target], close_fds=True)
        except Exception as exc:
            self._error(f"无法打开文件：{exc}")

    def _on_install_menu(self) -> None:
        self._manage_menu(install=True)

    def _on_uninstall_menu(self) -> None:
        self._manage_menu(install=False)

    def _manage_menu(self, install: bool) -> None:
        from . import context_menu

        try:
            if install:
                context_menu.install()
                self._info(
                    "已注册文件关联与右键菜单。\n"
                    "· zip/7z/rar 等压缩包将优先用简压打开（显示简压图标）\n"
                    "· 双击压缩包可直接解压\n"
                    "· 右击压缩包可选择打开\n"
                    "· 右键压缩包可「解压到当前文件夹」\n"
                    "· 右键文件/文件夹可「压缩为 ZIP」\n\n"
                    "若个别格式仍不是默认，请到：\n"
                    "设置 → 应用 → 默认应用 → 按文件类型，选「简压」。"
                )
            else:
                context_menu.uninstall()
                self._info("已取消默认打开，并移除右键菜单。")
        except context_menu.ContextMenuError as exc:
            self._error(str(exc))

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
                    for preview in self._preview_windows:
                        preview.update_progress(pct, name)
                elif kind == "done":
                    _, title, path = event
                    if self._main_built:
                        self.progress.configure(value=100)
                        self.status.configure(text=f"{title}：{path}")
                    for preview in self._preview_windows:
                        preview.update_progress(100, title)
                    self._set_busy(False)
                    dlg = self._progress_dialog
                    if dlg is not None:
                        # 进度窗负责在 100% 后再弹结果
                        dlg.finish_ok("简压", f"{title}\n{path}")
                    else:
                        # 主界面路径：先确保进度到 100%，再提示
                        self.root.update_idletasks()
                        self.root.after(
                            80,
                            lambda t=title, p=path: self._info(f"{t}\n{p}"),
                        )
                elif kind == "need_password":
                    _, archive, output, msg = event
                    self._set_busy(False)
                    dlg = self._progress_dialog
                    if dlg is not None:
                        dlg.dismiss()
                        self._progress_dialog = None
                    pwd = self.ask_password(
                        "需要密码", f"{Path(archive).name}\n{msg}"
                    )
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
                    dlg = self._progress_dialog
                    if dlg is not None:
                        dlg.finish_error("简压", msg)
                    else:
                        self._error(msg)
        except queue.Empty:
            pass
        self.root.after(50, self._drain_events)

    def run(self, show_main: bool = True) -> None:
        if show_main:
            self._build_ui()
            self.root.deiconify()
        else:
            self.root.protocol("WM_DELETE_WINDOW", self._on_root_close)
        self.root.mainloop()

    def _on_app_exit(self) -> None:
        self._cleanup_all_temps()
        try:
            self.root.destroy()
        except Exception:
            pass

    def _on_root_close(self) -> None:
        if self._preview_windows:
            return
        self._cleanup_all_temps()
        self.root.destroy()

    def _preview_closed(self, preview: "_PreviewWindow") -> None:
        preview.cleanup_temps()
        if preview in self._preview_windows:
            self._preview_windows.remove(preview)
        if not self._main_built and not self._preview_windows:
            self._cleanup_all_temps()
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
        self._temp_dirs: List[Path] = []
        tk, ttk = app.tk, app.ttk
        scale = app._scale

        win = tk.Toplevel(app.root)
        self.win = win
        win.withdraw()  # 先隐藏，避免左上角闪一下
        win.title(f"预览 — {archive.name}")
        w, h = int(560 * scale), int(460 * scale)
        win.minsize(int(420 * scale), int(320 * scale))
        win.configure(bg="white")

        try:
            ico = resource_path("app.ico")
            if ico:
                win.iconbitmap(default=ico)
        except Exception:
            pass

        frame = ttk.Frame(win, padding=int(12 * scale), style="White.TFrame")
        frame.pack(fill="both", expand=True)

        ttk.Label(
            frame,
            text=archive.name,
            style="Title.TLabel",
            font=("Microsoft YaHei UI", 12, "bold"),
        ).pack(anchor="w")
        info = f"{len(members)} 个项目"
        if any(m.encrypted for m in members) or password:
            info += " · 已加密"
        ttk.Label(
            frame, text=info, style="Sub.TLabel", font=("Microsoft YaHei UI", 9)
        ).pack(anchor="w", pady=(2, 8))

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
        tree.column("flag", width=int(80 * scale), anchor="center")

        scroll = ttk.Scrollbar(tree_frame, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=scroll.set)
        tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

        for member in members:
            flag = _member_flag(member)
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

        self.tree = tree
        tree.bind("<Double-1>", self._on_double_click)

        # 进度区域（解压时可见）
        prog = ttk.Frame(frame, style="White.TFrame")
        prog.pack(fill="x", pady=(10, 0))
        self.progress = ttk.Progressbar(prog, mode="determinate")
        self.progress.pack(fill="x")
        self.status = ttk.Label(
            prog,
            text="双击文件可打开 · 双击压缩包可再预览 · 选择下方按钮解压",
            style="Status.TLabel",
            font=("Microsoft YaHei UI", 9),
        )
        self.status.pack(fill="x", pady=(4, 0))

        buttons = ttk.Frame(frame, style="White.TFrame")
        buttons.pack(fill="x", pady=(10, 0))
        self.btn_to = ttk.Button(buttons, text="解压到…", command=self._extract_to)
        self.btn_to.pack(side="right")
        self.btn_here = ttk.Button(
            buttons, text="解压到当前文件夹", command=self._extract_here
        )
        self.btn_here.pack(side="right", padx=(0, 8))
        ttk.Button(buttons, text="关闭", command=self._close).pack(side="left")

        win.protocol("WM_DELETE_WINDOW", self._close)

        # 居中后再显示
        try:
            win.update_idletasks()
            sw = win.winfo_screenwidth()
            sh = win.winfo_screenheight()
            x = max(0, (sw - w) // 2)
            y = max(0, (sh - h) // 3)
            win.geometry(f"{w}x{h}+{x}+{y}")
        except Exception:
            win.geometry(f"{w}x{h}")
        try:
            win.deiconify()
            win.lift()
            win.focus_force()
        except Exception:
            pass

    def own_temp_dir(self, path: Path, register_app: bool = True) -> None:
        if path not in self._temp_dirs:
            self._temp_dirs.append(path)
        if register_app:
            self.app.register_temp_dir(path, owner=None)

    def cleanup_temps(self) -> None:
        for path in list(self._temp_dirs):
            _rmtree_quiet(path)
            self.app.unregister_temp_dir(path)
        self._temp_dirs.clear()

    def set_busy(self, busy: bool) -> None:
        state = "disabled" if busy else "normal"
        try:
            self.btn_to.configure(state=state)
            self.btn_here.configure(state=state)
        except Exception:
            pass

    def update_progress(self, pct: int, name: str) -> None:
        try:
            self.progress.configure(value=pct)
            self.status.configure(text=f"[{pct}%] {name}")
        except Exception:
            pass

    def _close(self) -> None:
        self.cleanup_temps()
        self.win.destroy()
        self.app._preview_closed(self)

    def _on_double_click(self, event) -> None:
        item = self.tree.identify_row(event.y)
        if not item:
            return
        name = self.tree.item(item, "text")
        member = None
        for m in self.members:
            if m.name == name or m.name.rstrip("/") == str(name).rstrip("/"):
                member = m
                break
        if member is None:
            return
        if member.is_dir:
            self.status.configure(text="请双击文件以打开（目录请先解压）")
            return
        base = Path(member.name).name
        if core.is_archive(base):
            self.status.configure(text=f"正在打开嵌套压缩包 {base}…")
        else:
            self.status.configure(text=f"正在打开 {base}…")
        self.app.open_archive_member(
            self.archive,
            member.name,
            self.password,
            encrypted=member.encrypted,
            owner_preview=self,
        )

    def _extract_here(self) -> None:
        # 解压到压缩包所在的当前文件夹
        self.status.configure(text="正在解压到当前文件夹…")
        self.progress.configure(value=0)
        self.app.extract_archive_interactive(
            str(self.archive),
            output=None,
            password=self.password,
            ask_dir=False,
        )

    def _extract_to(self) -> None:
        # 解压到用户选择的文件夹（不再套同名子目录）
        self.app.extract_archive_interactive(
            str(self.archive),
            output=None,
            password=self.password,
            ask_dir=True,
        )


def _member_flag(member: core.ArchiveMember) -> str:
    if member.is_dir:
        return "目录"
    parts: List[str] = []
    if member.encrypted:
        parts.append("加密")
    if core.is_archive(Path(member.name).name):
        parts.append("压缩包")
    return " · ".join(parts)


def _format_size(size: int) -> str:
    if size < 1024:
        return f"{size} B"
    if size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    if size < 1024 * 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MB"
    return f"{size / (1024 * 1024 * 1024):.2f} GB"
