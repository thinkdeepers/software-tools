"""Windows DPI 感知：在创建任何窗口前调用，避免界面/对话框被系统拉伸发虚。"""

from __future__ import annotations

import sys
from typing import Any


_ENABLED = False


def enable_high_dpi() -> None:
    """声明进程 DPI 感知。必须在创建第一个窗口之前调用。"""
    global _ENABLED
    if sys.platform != "win32":
        _ENABLED = True
        return
    # 即使已调用过，也再尝试一次（兼容 bootloader 抢先初始化的情况）
    try:
        import ctypes

        # Per-Monitor v2
        try:
            # DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
            ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
        except Exception:
            try:
                ctypes.windll.shcore.SetProcessDpiAwareness(2)
            except Exception:
                try:
                    ctypes.windll.user32.SetProcessDPIAware()
                except Exception:
                    pass

        # 当前线程也设为 Per-Monitor V2，避免后续 MessageBox/对话框发虚
        try:
            ctypes.windll.user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))
        except Exception:
            pass
    except Exception:
        pass
    _ENABLED = True


def configure_tk_scaling(root: Any) -> float:
    """按屏幕 DPI 设置 Tk scaling，返回相对 96dpi 的缩放比。"""
    try:
        dpi = float(root.winfo_fpixels("1i"))
    except Exception:
        dpi = 96.0
    if dpi < 96.0:
        dpi = 96.0
    try:
        root.tk.call("tk", "scaling", dpi / 72.0)
    except Exception:
        pass
    return max(1.0, dpi / 96.0)


def scaled_font(size: int, bold: bool = False, scale: float = 1.0) -> tuple:
    """返回字体元组。Tk scaling 已处理 DPI，不再二次放大字号。"""
    weight = "bold" if bold else "normal"
    return ("Microsoft YaHei UI", int(size), weight)


def pick_ui_font(root: Any, size: int, bold: bool = False) -> tuple:
    """选择当前系统实际可用的清晰 UI 字体。"""
    weight = "bold" if bold else "normal"
    candidates = (
        "Microsoft YaHei UI",
        "Microsoft YaHei",
        "Segoe UI",
        "PingFang SC",
        "Noto Sans CJK SC",
        "Source Han Sans SC",
        "SimHei",
    )
    try:
        import tkinter.font as tkfont

        families = set(tkfont.families(root))
        for name in candidates:
            if name in families:
                return (name, int(size), weight)
    except Exception:
        pass
    return ("TkDefaultFont", int(size), weight)
