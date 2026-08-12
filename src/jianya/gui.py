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
from .dpi import configure_tk_scaling, enable_high_dpi, pick_ui_font
from .resources import resource_path
from . import theme as ui


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
        root.title(f"简压 {__version__}")
        root.protocol("WM_DELETE_WINDOW", self._on_app_exit)
        atexit.register(self._cleanup_all_temps)

        self._scale = configure_tk_scaling(root)
        if self._scale < 1.0:
            self._scale = 1.0
        base_w, base_h = 520, 560
        self._win_w, self._win_h = int(base_w * self._scale), int(base_h * self._scale)
        ui.apply_window_chrome(root)

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
        tk = self.tk
        scale = self._scale
        root = self.root

        # ttk 仅用于预览列表；主界面改用 Canvas 圆角按钮，贴近 README 截图
        try:
            style = self.ttk.Style()
            if "clam" in style.theme_names():
                style.theme_use("clam")
            style.configure("White.TFrame", background=ui.BG)
            style.configure(
                "Preview.Treeview",
                background=ui.BG,
                fieldbackground=ui.BG,
                foreground=ui.TEXT,
                borderwidth=0,
                font=pick_ui_font(root, 10, False),
                rowheight=int(30 * scale),
            )
            style.configure(
                "Preview.Treeview.Heading",
                background="#f8fafc",
                foreground=ui.TEXT_SUB,
                relief="flat",
                font=pick_ui_font(root, 9, True),
            )
            style.map(
                "Preview.Treeview",
                background=[("selected", ui.PRIMARY_SOFT)],
                foreground=[("selected", ui.PRIMARY)],
            )
        except Exception:
            pass

        self.root.geometry(f"{self._win_w}x{self._win_h}")
        self.root.minsize(self._win_w, self._win_h)

        pad = int(36 * scale)
        container = tk.Frame(self.root, bg=ui.BG, padx=pad, pady=int(28 * scale))
        container.pack(fill="both", expand=True)

        # —— 品牌标题区 ——
        header = tk.Frame(container, bg=ui.BG)
        header.pack(side="top", fill="x", pady=(int(12 * scale), 0))
        tk.Label(
            header,
            text="简压",
            font=pick_ui_font(root, 36, True),
            fg=ui.PRIMARY,
            bg=ui.BG,
        ).pack()
        tk.Label(
            header,
            text="压缩统一为 ZIP · 解压支持常见格式 · 可加密",
            font=pick_ui_font(root, 10, False),
            fg=ui.TEXT_MUTED,
            bg=ui.BG,
        ).pack(pady=(int(8 * scale), int(28 * scale)))

        # —— 主操作：压缩 / 解压 ——
        buttons = tk.Frame(container, bg=ui.BG)
        buttons.pack(side="top", fill="x")
        buttons.columnconfigure(0, weight=1)
        buttons.columnconfigure(1, weight=1)

        # 主按钮圆角略收敛，减少视觉失真
        btn_h = int(56 * scale)
        self.btn_compress = ui.make_rounded_button(
            buttons,
            tk,
            "压缩",
            self._on_compress,
            variant="primary",
            font_size=16,
            height=btn_h,
            radius=int(8 * scale),
            min_width=int(160 * scale),
            expand_width=True,
        )
        self.btn_compress.grid(row=0, column=0, sticky="ew", padx=(0, int(10 * scale)))

        self.btn_extract = ui.make_rounded_button(
            buttons,
            tk,
            "解压",
            self._on_extract,
            variant="primary",
            font_size=16,
            height=btn_h,
            radius=int(8 * scale),
            min_width=int(160 * scale),
            expand_width=True,
        )
        self.btn_extract.grid(row=0, column=1, sticky="ew", padx=(int(10 * scale), 0))

        # —— 进度 + 状态 ——
        middle = tk.Frame(container, bg=ui.BG)
        middle.pack(side="top", fill="both", expand=True, pady=(int(28 * scale), 0))

        self._progress_bar = ui.RoundedProgressBar(
            middle, tk, height=int(10 * scale), radius=int(5 * scale)
        )
        self._progress_bar.pack(fill="x", pady=(int(8 * scale), int(10 * scale)))
        self.progress = self._progress_bar  # 兼容旧调用 progress.configure(value=)

        self.status = tk.Label(
            middle,
            text="选择文件开始压缩，或选择压缩包预览 / 解压",
            font=pick_ui_font(root, 9, False),
            fg=ui.TEXT_MUTED,
            bg=ui.BG,
            anchor="w",
        )
        self.status.pack(fill="x")

        # —— 底部关联按钮 ——
        bottom = tk.Frame(container, bg=ui.BG)
        bottom.pack(side="bottom", fill="x", pady=(int(8 * scale), 0))

        foot_note = tk.Frame(bottom, bg=ui.BG)
        foot_note.pack(side="bottom", fill="x", pady=(int(14 * scale), 0))
        for text in (
            "本软件由 Opus 4.8 模型自动生成",
            "致力于造福全人类，共建简单、和平、美好的生态",
            "—— 洞穴理论工作室 出品",
        ):
            tk.Label(
                foot_note,
                text=text,
                font=pick_ui_font(root, 8, False),
                fg="#9ca3af",
                bg=ui.BG,
                anchor="center",
                justify="center",
            ).pack(fill="x")

        assoc = tk.Frame(bottom, bg=ui.BG)
        assoc.pack(side="bottom", fill="x")
        assoc.columnconfigure(0, weight=1)
        assoc.columnconfigure(1, weight=1)

        outline_h = int(40 * scale)
        self.btn_install = ui.make_rounded_button(
            assoc,
            tk,
            "设为默认打开",
            self._on_install_menu,
            variant="outline",
            font_size=10,
            height=outline_h,
            radius=int(8 * scale),
            min_width=int(140 * scale),
            expand_width=True,
        )
        self.btn_install.grid(row=0, column=0, sticky="ew", padx=(0, int(8 * scale)))

        self.btn_uninstall = ui.make_rounded_button(
            assoc,
            tk,
            "取消默认打开",
            self._on_uninstall_menu,
            variant="outline",
            font_size=10,
            height=outline_h,
            radius=int(8 * scale),
            min_width=int(140 * scale),
            expand_width=True,
        )
        self.btn_uninstall.grid(row=0, column=1, sticky="ew", padx=(int(8 * scale), 0))

        # 居中显示
        try:
            root.update_idletasks()
            sw = root.winfo_screenwidth()
            sh = root.winfo_screenheight()
            x = max(0, (sw - self._win_w) // 2)
            y = max(0, (sh - self._win_h) // 3)
            root.geometry(f"{self._win_w}x{self._win_h}+{x}+{y}")
        except Exception:
            pass

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
        for btn in (self.btn_compress, self.btn_extract):
            try:
                btn.configure_state(state)
            except Exception:
                try:
                    btn.configure(state=state)
                except Exception:
                    pass
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
        win.title(f"简压 预览 — {archive.name}")
        w, h = int(660 * scale), int(560 * scale)
        win.minsize(int(520 * scale), int(420 * scale))
        ui.apply_window_chrome(win)

        try:
            ico = resource_path("app.ico")
            if ico:
                win.iconbitmap(default=ico)
        except Exception:
            pass

        pad = int(20 * scale)
        frame = tk.Frame(win, bg=ui.BG, padx=pad, pady=pad)
        frame.pack(fill="both", expand=True)

        # —— 底部按钮（先 pack 到底部，避免被列表挤出可视区）——
        buttons = tk.Frame(frame, bg=ui.BG)
        buttons.pack(side="bottom", fill="x", pady=(int(12 * scale), 0))

        self.btn_close = ui.make_rounded_button(
            buttons,
            tk,
            "关闭",
            self._close,
            variant="ghost",
            font_size=10,
            height=int(40 * scale),
            radius=int(10 * scale),
            min_width=int(88 * scale),
        )
        self.btn_close.pack(side="left")

        right = tk.Frame(buttons, bg=ui.BG)
        right.pack(side="right")
        self.btn_to = ui.make_rounded_button(
            right,
            tk,
            "解压到…",
            self._extract_to,
            variant="primary",
            font_size=10,
            height=int(40 * scale),
            radius=int(10 * scale),
            min_width=int(110 * scale),
        )
        self.btn_to.pack(side="right")
        self.btn_here = ui.make_rounded_button(
            right,
            tk,
            "解压到当前文件夹",
            self._extract_here,
            variant="primary",
            font_size=10,
            height=int(40 * scale),
            radius=int(10 * scale),
            min_width=int(150 * scale),
        )
        self.btn_here.pack(side="right", padx=(0, int(8 * scale)))

        # —— 进度（贴在按钮上方）——
        prog = tk.Frame(frame, bg=ui.BG)
        prog.pack(side="bottom", fill="x", pady=(int(10 * scale), 0))
        self._progress_bar = ui.RoundedProgressBar(
            prog, tk, height=int(8 * scale), radius=int(4 * scale)
        )
        self._progress_bar.pack(fill="x")
        self.progress = self._progress_bar
        self.status = tk.Label(
            prog,
            text="双击文件可打开 · 双击压缩包可再预览 · 选择下方按钮解压",
            font=pick_ui_font(win, 9, False),
            fg=ui.TEXT_MUTED,
            bg=ui.BG,
            anchor="w",
        )
        self.status.pack(fill="x", pady=(int(6 * scale), 0))

        # —— 头部：图标 + 文件名 ——
        header = tk.Frame(frame, bg=ui.BG)
        header.pack(side="top", fill="x", pady=(0, int(14 * scale)))

        icon_size = int(56 * scale)
        icon_widget, self._icon_photo = ui.make_zip_icon(header, tk, size=icon_size)
        icon_widget.pack(side="left", padx=(0, int(14 * scale)))

        titles = tk.Frame(header, bg=ui.BG)
        titles.pack(side="left", fill="x", expand=True)
        tk.Label(
            titles,
            text=archive.name,
            font=pick_ui_font(win, 16, True),
            fg=ui.TEXT,
            bg=ui.BG,
            anchor="w",
        ).pack(fill="x")
        info = f"{len(members)} 个项目"
        if any(m.encrypted for m in members) or password:
            info += " · 已加密"
        tk.Label(
            titles,
            text=info,
            font=pick_ui_font(win, 10, False),
            fg=ui.TEXT_MUTED,
            bg=ui.BG,
            anchor="w",
        ).pack(fill="x", pady=(int(2 * scale), 0))

        # —— 列表外框 ——
        list_wrap = tk.Frame(
            frame,
            bg=ui.BG,
            highlightbackground=ui.BORDER,
            highlightthickness=1,
            bd=0,
        )
        list_wrap.pack(side="top", fill="both", expand=True)

        tree_frame = tk.Frame(list_wrap, bg=ui.BG)
        tree_frame.pack(fill="both", expand=True, padx=1, pady=1)

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
        tree.column("#0", width=int(300 * scale), stretch=True)
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
        for btn in (self.btn_to, self.btn_here):
            try:
                btn.configure_state(state)
            except Exception:
                try:
                    btn.configure(state=state)
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
