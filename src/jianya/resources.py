"""定位随程序分发的资源文件（如图标），兼容源码运行与 PyInstaller 打包。"""

from __future__ import annotations

import os
import sys
from typing import Optional


def resource_path(name: str) -> Optional[str]:
    """返回资源文件的绝对路径；找不到时返回 None。

    - PyInstaller 打包后资源被解包到 ``sys._MEIPASS``。
    - 源码运行时资源位于项目根目录的 ``assets/``。
    """
    candidates = []

    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        candidates.append(os.path.join(meipass, name))

    here = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(here, os.pardir, os.pardir))
    candidates.append(os.path.join(project_root, "assets", name))
    candidates.append(os.path.join(here, name))

    for path in candidates:
        if os.path.exists(path):
            return path
    return None
