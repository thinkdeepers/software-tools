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

    HWND_TOPMOST = -1
    SWP_NOMOVE = 0x0002
    SWP_NOSIZE = 0x0001
    SWP_NOACTIVATE = 0x0010
    SWP_SHOWWINDOW = 0x0040

    MUTEX_NAME = "Global\\EyeCareGuardian_Mutex_v1"
    MAIN_WINDOW_TITLE = "护眼卫士"
    ERROR_ALREADY_EXISTS = 183
    SW_RESTORE = 9


def enable_dpi_awareness() -> None:
    """启用每显示器 DPI 感知，确保多屏与遮罩回退时几何正确"""
    if not IS_WINDOWS:
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            _user32.SetProcessDPIAware()
        except Exception:
            pass


def set_topmost(hwnd: int) -> None:
    """将窗口置于最顶层，覆盖系统弹出层（遮罩回退模式）"""
    if not IS_WINDOWS or not hwnd:
        return
    _user32.SetWindowPos(
        hwnd,
        HWND_TOPMOST,
        0,
        0,
        0,
        0,
        SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW,
    )


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


def activate_existing_instance() -> bool:
    """若已有实例在运行，激活其主界面窗口"""
    if not IS_WINDOWS:
        return False
    hwnd = _user32.FindWindowW(None, MAIN_WINDOW_TITLE)
    if not hwnd:
        return False
    _user32.ShowWindow(hwnd, SW_RESTORE)
    _user32.SetForegroundWindow(hwnd)
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
