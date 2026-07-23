"""Windows 右键菜单集成。

通过写入 ``HKEY_CURRENT_USER\\Software\\Classes`` 注册右键菜单，
因此**不需要管理员权限**，且只影响当前用户。

- 对任意文件 / 文件夹：新增"压缩为 ZIP（简压）"。
- 对常见压缩包（.zip/.7z/.rar/.tar/.gz/.tgz/.bz2/.xz）：新增"解压到此处（简压）"。
"""

from __future__ import annotations

import os
import sys
from typing import List

# 需要注册解压菜单的扩展名。
_EXTRACT_EXTS = [
    ".zip", ".7z", ".rar", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".tbz2", ".txz",
]

_COMPRESS_KEY = "Jianya.Compress"
_EXTRACT_KEY = "Jianya.Extract"


class ContextMenuError(Exception):
    """右键菜单注册相关错误。"""


def _require_windows():
    if os.name != "nt":
        raise ContextMenuError("右键菜单集成目前仅支持 Windows 系统。")


def _launcher(action: str) -> str:
    """构造用于右键菜单的启动命令，末尾带占位的 \"%1\"。

    - 打包成 exe 时直接调用自身。
    - 源码运行时使用 pythonw + 入口脚本。
    """
    if getattr(sys, "frozen", False):
        exe = sys.executable
        return f'"{exe}" {action} "%1"'

    # 源码运行：优先使用无控制台窗口的 pythonw。
    python = sys.executable
    pythonw = os.path.join(os.path.dirname(python), "pythonw.exe")
    if os.path.exists(pythonw):
        python = pythonw

    # 通过项目根目录的 main.py 启动，确保能找到 src 包。
    main_py = os.path.join(_project_root(), "main.py")
    return f'"{python}" "{main_py}" {action} "%1"'


def _project_root() -> str:
    # src/jianya/context_menu.py -> 项目根目录
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.abspath(os.path.join(here, os.pardir, os.pardir))


def _icon() -> str:
    if getattr(sys, "frozen", False):
        return sys.executable
    return ""


def _create_verb(winreg, base_path: str, verb_key: str, label: str, command: str):
    """在 ``base_path`` 下创建一个右键动词（含 command 子键）。"""
    verb_path = f"{base_path}\\{verb_key}"
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, verb_path) as key:
        winreg.SetValueEx(key, None, 0, winreg.REG_SZ, label)
        icon = _icon()
        if icon:
            winreg.SetValueEx(key, "Icon", 0, winreg.REG_SZ, icon)
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, f"{verb_path}\\command") as key:
        winreg.SetValueEx(key, None, 0, winreg.REG_SZ, command)


def _delete_verb(winreg, base_path: str, verb_key: str):
    verb_path = f"{base_path}\\{verb_key}"
    for sub in (f"{verb_path}\\command", verb_path):
        try:
            winreg.DeleteKey(winreg.HKEY_CURRENT_USER, sub)
        except FileNotFoundError:
            pass
        except OSError:
            # 可能还有其它子键，忽略。
            pass


def install() -> None:
    """注册右键菜单。"""
    _require_windows()
    import winreg  # type: ignore

    compress_cmd = _launcher("--compress")
    extract_cmd = _launcher("--extract")

    classes = "Software\\Classes"

    # 文件与文件夹上的"压缩为 ZIP"
    _create_verb(winreg, f"{classes}\\*\\shell", _COMPRESS_KEY, "压缩为 ZIP（简压）", compress_cmd)
    _create_verb(winreg, f"{classes}\\Directory\\shell", _COMPRESS_KEY, "压缩为 ZIP（简压）", compress_cmd)

    # 常见压缩包上的"解压到此处"
    for ext in _EXTRACT_EXTS:
        base = f"{classes}\\SystemFileAssociations\\{ext}\\shell"
        _create_verb(winreg, base, _EXTRACT_KEY, "解压到此处（简压）", extract_cmd)

    _notify_shell_changed()


def uninstall() -> None:
    """移除右键菜单。"""
    _require_windows()
    import winreg  # type: ignore

    classes = "Software\\Classes"
    _delete_verb(winreg, f"{classes}\\*\\shell", _COMPRESS_KEY)
    _delete_verb(winreg, f"{classes}\\Directory\\shell", _COMPRESS_KEY)
    for ext in _EXTRACT_EXTS:
        base = f"{classes}\\SystemFileAssociations\\{ext}\\shell"
        _delete_verb(winreg, base, _EXTRACT_KEY)

    _notify_shell_changed()


def _notify_shell_changed() -> None:
    """通知资源管理器刷新，使菜单立即生效。"""
    try:
        import ctypes

        SHCNE_ASSOCCHANGED = 0x08000000
        SHCNF_IDLIST = 0x0000
        ctypes.windll.shell32.SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, None, None)
    except Exception:
        pass
