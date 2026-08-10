"""系统托盘"""

from __future__ import annotations

from PyQt6.QtCore import QObject, QTimer, pyqtSignal
from PyQt6.QtGui import QAction, QIcon, QPixmap, QPainter, QColor, QFont
from PyQt6.QtWidgets import QMenu, QSystemTrayIcon

from config import AppConfig


def _make_tray_icon() -> QIcon:
    size = 64
    pix = QPixmap(size, size)
    pix.fill(QColor(0, 0, 0, 0))
    painter = QPainter(pix)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)

    painter.setBrush(QColor(45, 106, 79))
    painter.setPen(QColor(125, 211, 168))
    painter.drawEllipse(4, 4, size - 8, size - 8)

    font = QFont()
    font.setPointSize(28)
    font.setBold(True)
    painter.setFont(font)
    painter.setPen(QColor(255, 255, 255))
    painter.drawText(pix.rect(), 0x84, "眼")  # AlignCenter

    painter.end()
    return QIcon(pix)


class TrayManager(QObject):
    toggle_requested = pyqtSignal()
    main_window_requested = pyqtSignal()
    quit_requested = pyqtSignal()
    preset_requested = pyqtSignal(str)

    def __init__(self, config: AppConfig, parent: QObject | None = None):
        super().__init__(parent)
        self._config = config
        self._tray = QSystemTrayIcon(_make_tray_icon(), parent)
        self._tray.setToolTip("护眼卫士 - 双击打开主界面")

        self._toggle_action = QAction("关闭护眼", self)
        self._toggle_action.triggered.connect(self.toggle_requested.emit)

        menu = QMenu()
        menu.addAction(self._toggle_action)

        preset_menu = menu.addMenu("快速切换模式")
        for preset in config.presets:
            action = QAction(preset.name, self)
            action.triggered.connect(
                lambda checked, n=preset.name: self.preset_requested.emit(n)
            )
            preset_menu.addAction(action)

        menu.addSeparator()
        menu.addAction("打开主界面", self.main_window_requested.emit)
        menu.addSeparator()
        menu.addAction("退出程序", self.quit_requested.emit)

        self._tray.setContextMenu(menu)
        self._tray.activated.connect(self._on_activated)

        self._click_timer = QTimer(self)
        self._click_timer.setSingleShot(True)
        self._click_timer.setInterval(350)
        self._click_timer.timeout.connect(self._reset_click_state)
        self._click_count = 0

    def _reset_click_state(self) -> None:
        self._click_count = 0

    def _open_main_window(self) -> None:
        self._click_timer.stop()
        self._click_count = 0
        self.main_window_requested.emit()

    def _on_activated(self, reason: QSystemTrayIcon.ActivationReason) -> None:
        if reason == QSystemTrayIcon.ActivationReason.DoubleClick:
            self._open_main_window()
        elif reason == QSystemTrayIcon.ActivationReason.Trigger:
            # Windows 托盘有时不触发 DoubleClick，用两次单击模拟双击
            self._click_count += 1
            if self._click_count >= 2:
                self._open_main_window()
            else:
                self._click_timer.start()

    def show(self) -> None:
        if not QSystemTrayIcon.isSystemTrayAvailable():
            raise RuntimeError("系统托盘不可用，无法运行")
        self._tray.show()

    def update_state(self, config: AppConfig) -> None:
        self._config = config
        status = "已开启" if config.enabled else "已关闭"
        self._toggle_action.setText(f"{'关闭' if config.enabled else '开启'}护眼")
        self._tray.setToolTip(f"护眼卫士 - {status} | 双击打开主界面")

    def notify(self, title: str, message: str, duration_ms: int = 5000) -> None:
        self._tray.showMessage(
            title, message, QSystemTrayIcon.MessageIcon.Information, duration_ms
        )

    def notify_minimized(self) -> None:
        self.notify(
            "护眼卫士",
            "已最小化到系统托盘，双击图标可再次打开主界面",
            3000,
        )
