# PyInstaller runtime hook：在 bootloader 初始化后、业务代码前声明 DPI 感知。
# 必须尽早执行，否则高分屏下窗口会被系统位图拉伸，文字发虚。

def _enable_dpi() -> None:
    import sys

    if sys.platform != "win32":
        return
    try:
        import ctypes

        try:
            # DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
            ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
            return
        except Exception:
            pass
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
            return
        except Exception:
            pass
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass
    except Exception:
        pass


_enable_dpi()
