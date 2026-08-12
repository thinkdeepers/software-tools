"""应用全局主题样式"""

APP_STYLESHEET = """
* {
    font-family: "Microsoft YaHei UI", "Segoe UI", "PingFang SC", sans-serif;
    font-size: 13px;
}

QWidget#MainWindow {
    background-color: #f0fdf4;
}

QTabWidget::pane {
    border: none;
    background: transparent;
    top: -1px;
}

QTabBar::tab {
    background: #dcfce7;
    color: #166534;
    border: none;
    border-radius: 10px;
    padding: 8px 18px;
    margin: 4px 3px;
    min-width: 72px;
}

QTabBar::tab:selected {
    background: #22c55e;
    color: white;
    font-weight: bold;
}

QTabBar::tab:hover:!selected {
    background: #bbf7d0;
}

QGroupBox {
    background: #ffffff;
    border: 1px solid #d1fae5;
    border-radius: 14px;
    margin-top: 14px;
    padding: 18px 14px 12px 14px;
    font-weight: 600;
    color: #14532d;
}

QGroupBox::title {
    subcontrol-origin: margin;
    left: 14px;
    padding: 0 6px;
    color: #15803d;
}

QLabel {
    color: #334155;
}

QCheckBox {
    color: #1e293b;
    spacing: 8px;
}

QCheckBox::indicator {
    width: 18px;
    height: 18px;
    border-radius: 5px;
    border: 2px solid #86efac;
    background: white;
}

QCheckBox::indicator:checked {
    background: #22c55e;
    border-color: #16a34a;
}

QComboBox, QSpinBox, QTimeEdit {
    background: #ffffff;
    border: 1px solid #bbf7d0;
    border-radius: 8px;
    padding: 6px 10px;
    color: #1e293b;
    min-height: 20px;
}

QComboBox:hover, QSpinBox:hover, QTimeEdit:hover {
    border-color: #4ade80;
}

QComboBox::drop-down {
    border: none;
    width: 24px;
}

QSlider::groove:horizontal {
    height: 6px;
    background: #dcfce7;
    border-radius: 3px;
}

QSlider::handle:horizontal {
    width: 18px;
    height: 18px;
    margin: -6px 0;
    background: #22c55e;
    border: 2px solid #ffffff;
    border-radius: 9px;
}

QSlider::sub-page:horizontal {
    background: #86efac;
    border-radius: 3px;
}

QPushButton {
    background: #22c55e;
    color: white;
    border: none;
    border-radius: 10px;
    padding: 9px 22px;
    font-weight: 600;
    min-width: 80px;
}

QPushButton:hover {
    background: #16a34a;
}

QPushButton:pressed {
    background: #15803d;
}

QPushButton#secondaryBtn {
    background: #ffffff;
    color: #15803d;
    border: 1.5px solid #86efac;
}

QPushButton#secondaryBtn:hover {
    background: #f0fdf4;
    border-color: #4ade80;
}

QMenu {
    background: #ffffff;
    border: 1px solid #d1fae5;
    border-radius: 10px;
    padding: 6px;
}

QMenu::item {
    padding: 8px 28px 8px 16px;
    border-radius: 6px;
    color: #1e293b;
}

QMenu::item:selected {
    background: #dcfce7;
    color: #14532d;
}

QMenu::separator {
    height: 1px;
    background: #ecfdf5;
    margin: 4px 8px;
}
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
    background: #dcfce7;
    color: #15803d;
    border-radius: 12px;
    padding: 4px 12px;
    font-size: 12px;
    font-weight: 600;
}

QLabel#statusBadge[off="true"] {
    background: #f1f5f9;
    color: #94a3b8;
}

QLabel#footer {
    color: #94a3b8;
    font-size: 11px;
    padding: 8px 12px 4px 12px;
    line-height: 1.5;
}
"""
