"""应用全局主题样式"""

from __future__ import annotations

import sys
from pathlib import Path


def _assets_dir() -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS) / "assets"
    return Path(__file__).resolve().parent.parent / "assets"


def _qss_path(filename: str) -> str:
    """Qt stylesheet url() 需要正斜杠路径"""
    return (_assets_dir() / filename).as_posix()


def build_stylesheet() -> str:
    check_on = _qss_path("checkbox_on.png")
    check_off = _qss_path("checkbox_off.png")

    return f"""
* {{
    font-family: "Microsoft YaHei UI", "Segoe UI", "PingFang SC", sans-serif;
    font-size: 13px;
}}

QWidget#MainWindow {{
    background-color: #f0fdf4;
}}

QTabWidget::pane {{
    border: none;
    background: transparent;
    top: -1px;
}}

QTabBar::tab {{
    background: transparent;
    color: #64748b;
    border: none;
    border-bottom: 2px solid transparent;
    border-radius: 0;
    padding: 10px 16px;
    margin: 0 2px;
    min-width: 72px;
}}

QTabBar::tab:selected {{
    background: transparent;
    color: #15803d;
    font-weight: bold;
    border-bottom: 2px solid #22c55e;
}}

QTabBar::tab:hover:!selected {{
    color: #166534;
    background: #ecfdf5;
    border-radius: 8px;
}}

QGroupBox {{
    background: #ffffff;
    border: 1px solid #d1fae5;
    border-radius: 14px;
    margin-top: 14px;
    padding: 18px 14px 12px 14px;
    font-weight: 600;
    color: #14532d;
}}

QGroupBox::title {{
    subcontrol-origin: margin;
    left: 14px;
    padding: 0 6px;
    color: #15803d;
}}

QLabel {{
    color: #334155;
}}

QCheckBox {{
    color: #1e293b;
    spacing: 10px;
}}

QCheckBox::indicator {{
    width: 20px;
    height: 20px;
    border: none;
    background: transparent;
}}

QCheckBox::indicator:unchecked {{
    image: url("{check_off}");
}}

QCheckBox::indicator:checked {{
    image: url("{check_on}");
}}

QCheckBox::indicator:hover {{
    opacity: 0.9;
}}

QComboBox, QSpinBox, QTimeEdit {{
    background: #ffffff;
    border: 1px solid #bbf7d0;
    border-radius: 8px;
    padding: 6px 10px;
    color: #1e293b;
    min-height: 20px;
}}

QComboBox:hover, QSpinBox:hover, QTimeEdit:hover {{
    border-color: #4ade80;
}}

QComboBox::drop-down {{
    border: none;
    width: 24px;
}}

QSlider::groove:horizontal {{
    height: 6px;
    background: #dcfce7;
    border-radius: 3px;
}}

QSlider::handle:horizontal {{
    width: 18px;
    height: 18px;
    margin: -6px 0;
    background: #ffffff;
    border: 2px solid #22c55e;
    border-radius: 9px;
}}

QSlider::sub-page:horizontal {{
    background: #86efac;
    border-radius: 3px;
}}

QPushButton {{
    background: #22c55e;
    color: white;
    border: none;
    border-radius: 10px;
    padding: 9px 22px;
    font-weight: 600;
    min-width: 80px;
}}

QPushButton:hover {{
    background: #16a34a;
}}

QPushButton:pressed {{
    background: #15803d;
}}

QPushButton#secondaryBtn {{
    background: #ffffff;
    color: #15803d;
    border: 1.5px solid #86efac;
}}

QPushButton#secondaryBtn:hover {{
    background: #f0fdf4;
    border-color: #4ade80;
}}

QMenu {{
    background: #ffffff;
    border: 1px solid #d1fae5;
    border-radius: 10px;
    padding: 6px;
}}

QMenu::item {{
    padding: 8px 28px 8px 16px;
    border-radius: 6px;
    color: #1e293b;
}}

QMenu::item:selected {{
    background: #ecfdf5;
    color: #14532d;
}}

QMenu::separator {{
    height: 1px;
    background: #ecfdf5;
    margin: 4px 8px;
}}
"""


MAIN_WINDOW_EXTRA = """
QLabel#appTitle {
    font-size: 22px;
    font-weight: bold;
    color: #14532d;
}

QLabel#appSubtitle {
    font-size: 12px;
    color: #64748b;
}

QLabel#statusBadge {
    background: #ecfdf5;
    color: #15803d;
    border: 1px solid #86efac;
    border-radius: 12px;
    padding: 4px 12px;
    font-size: 12px;
    font-weight: 600;
}

QLabel#statusBadge[off="true"] {
    background: #f8fafc;
    color: #94a3b8;
    border: 1px solid #e2e8f0;
}

QLabel#footer {
    color: #94a3b8;
    font-size: 11px;
    padding: 8px 12px 4px 12px;
    line-height: 1.5;
}
"""

# 兼容旧导入
APP_STYLESHEET = build_stylesheet()
