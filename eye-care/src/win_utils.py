"""Windows 平台工具函数"""

from __future__ import annotations

import sys

IS_WINDOWS = sys.platform == "win32"

if IS_WINDOWS:
    import ctypes

    GWL_EXSTYLE = -20
    WS_EX_LAYERED = 0x00080000
    WS_EX_TRANSPARENT = 0x00000020
    WS_EX_TOOLWINDOW = 0x00000080
    WS_EX_NOACTIVATE = 0x08000000

    _kernel32 = ctypes.windll.kernel32
    _user32 = ctypes.windll.user32

    MUTEX_NAME = "Global\\EyeCareGuardian_Mutex_v1"
    ERROR_ALREADY_EXISTS = 183


def ensure_single_instance() -> bool:
    """返回 True 表示可以运行，False 表示已有实例"""
    if not IS_WINDOWS:
        return True
    handle = _kernel32.CreateMutexW(None, True, MUTEX_NAME)
    if _kernel32.GetLastError() == ERROR_ALREADY_EXISTS:
        if handle:
            _kernel32.CloseHandle(handle)
        return False
    return True


def set_click_through(hwnd: int) -> None:
    """设置窗口鼠标穿透，且不显示在任务栏"""
    if not IS_WINDOWS or not hwnd:
        return
    style = _user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
    style |= WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE
    _user32.SetWindowLongW(hwnd, GWL_EXSTYLE, style)


def hide_from_taskbar(hwnd: int) -> None:
    """隐藏窗口在任务栏的按钮"""
    if not IS_WINDOWS or not hwnd:
        return
    style = _user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
    style |= WS_EX_TOOLWINDOW
    style &= ~0x00040000  # 清除 WS_EX_APPWINDOW
    _user32.SetWindowLongW(hwnd, GWL_EXSTYLE, style)
