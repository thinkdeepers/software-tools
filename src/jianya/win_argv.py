"""Windows 下获取 Unicode 命令行参数，避免中文路径乱码。"""

from __future__ import annotations

import sys
from typing import List, Optional


def get_unicode_argv(argv: Optional[List[str]] = None) -> List[str]:
    """返回 Unicode 正确的 argv。

    PyInstaller ``--windowed`` 打包后，``sys.argv`` 在含中文路径时可能乱码。
    这里通过 ``GetCommandLineW`` + ``CommandLineToArgvW`` 取宽字符参数。
    """
    if argv is not None:
        return list(argv)
    if sys.platform != "win32":
        return list(sys.argv)
    try:
        import ctypes
        from ctypes import wintypes

        kernel32 = ctypes.windll.kernel32
        shell32 = ctypes.windll.shell32

        kernel32.GetCommandLineW.restype = wintypes.LPWSTR
        shell32.CommandLineToArgvW.argtypes = [
            wintypes.LPCWSTR,
            ctypes.POINTER(ctypes.c_int),
        ]
        shell32.CommandLineToArgvW.restype = ctypes.POINTER(wintypes.LPWSTR)

        argc = ctypes.c_int(0)
        argv_w = shell32.CommandLineToArgvW(kernel32.GetCommandLineW(), ctypes.byref(argc))
        if not argv_w or argc.value <= 0:
            return list(sys.argv)
        try:
            return [argv_w[i] for i in range(argc.value)]
        finally:
            kernel32.LocalFree(argv_w)
    except Exception:
        return list(sys.argv)
