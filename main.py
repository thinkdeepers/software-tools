#!/usr/bin/env python3
"""简压 —— 项目入口。

直接运行以打开图形界面，或使用命令行参数：
    python main.py --compress <文件...>
    python main.py --extract <压缩包>
    python main.py --open <压缩包>      # 预览
    python main.py <压缩包>             # 同上
    python main.py --install / --uninstall
"""

import os
import sys

# 保证以源码方式运行时能找到 src 下的包。
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "src"))

# 尽早声明 DPI，必须在创建任何窗口之前。
try:
    from jianya.dpi import enable_high_dpi

    enable_high_dpi()
except Exception:
    pass

from jianya.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
