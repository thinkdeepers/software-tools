"""Windows DPI 感知：在创建任何窗口前调用，避免界面/对话框被系统拉伸发虚。"""

from __future__ import annotations

import sys
from typing import Any


_ENABLED = False


def enable_high_dpi() -> None:
    """声明进程 DPI 感知。必须在创建第一个 Tk / 原生窗口之前调用。"""
    global _ENABLED
    if _ENABLED or sys.platform != "win32":
        _ENABLED = True
        return
    try:
        import ctypes

        # Per-Monitor v2（Windows 10 1703+）
        try:
            ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
            _ENABLED = True
            return
        except Exception:
            pass
        # Per-Monitor（Windows 8.1+）
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
            _ENABLED = True
            return
        except Exception:
            pass
        # System DPI aware（Vista+）
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass
    except Exception:
        pass
    _ENABLED = True


def configure_tk_scaling(root: Any) -> float:
    """按屏幕 DPI 设置 Tk scaling，并返回相对 96dpi 的缩放比。"""
    try:
        dpi = float(root.winfo_fpixels("1i"))
    except Exception:
        dpi = 96.0
    if dpi < 96.0:
        dpi = 96.0
    try:
        # Tk 内部以 72dpi 为 scaling=1.0
        root.tk.call("tk", "scaling", dpi / 72.0)
    except Exception:
        pass
    return dpi / 96.0
