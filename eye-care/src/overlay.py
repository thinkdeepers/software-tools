"""全局护眼遮罩层 - 覆盖所有显示器"""

from __future__ import annotations

import sys

from PyQt6.QtCore import Qt, QRect
from PyQt6.QtGui import QColor, QPainter
from PyQt6.QtWidgets import QApplication, QWidget

from win_utils import IS_WINDOWS, set_click_through


def temperature_to_warm_color(temperature: int) -> QColor:
    """将色温值(0-100)转换为暖色叠加色"""
    t = max(0, min(100, temperature)) / 100.0
    r = 255
    g = int(220 - t * 90)   # 220 -> 130
    b = int(180 - t * 150)  # 180 -> 30
    return QColor(r, g, b)


def calc_overlay_alphas(temperature: int, brightness: int) -> tuple[float, float]:
    """计算暖色遮罩和暗化遮罩的不透明度"""
    t = max(0, min(100, temperature)) / 100.0
    b = max(0, min(100, brightness)) / 100.0
    warm_alpha = t * 0.45
    dim_alpha = (1.0 - b) * 0.55
    return warm_alpha, dim_alpha


class OverlayWindow(QWidget):
    """单显示器全屏护眼遮罩，鼠标穿透"""

    def __init__(self, geometry: QRect, parent: QWidget | None = None):
        super().__init__(parent)
        self._geometry = geometry
        self._enabled = False
        self._temperature = 45
        self._brightness = 75
        self._warm_color = temperature_to_warm_color(45)
        self._warm_alpha = 0.0
        self._dim_alpha = 0.0

        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
            | Qt.WindowType.WindowDoesNotAcceptFocus
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        self.setGeometry(geometry)
        self.setWindowTitle("EyeCare Overlay")

    def apply(self, enabled: bool, temperature: int, brightness: int) -> None:
        self._enabled = enabled
        self._temperature = temperature
        self._brightness = brightness
        self._warm_color = temperature_to_warm_color(temperature)
        self._warm_alpha, self._dim_alpha = calc_overlay_alphas(temperature, brightness)
        self.update()
        if enabled:
            self.show()
            self.raise_()
            if IS_WINDOWS:
                set_click_through(int(self.winId()))
        else:
            self.hide()

    def paintEvent(self, _event) -> None:
        if not self._enabled:
            return
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        if self._warm_alpha > 0.001:
            color = QColor(self._warm_color)
            color.setAlphaF(self._warm_alpha)
            painter.fillRect(self.rect(), color)

        if self._dim_alpha > 0.001:
            dim = QColor(0, 0, 0)
            dim.setAlphaF(self._dim_alpha)
            painter.fillRect(self.rect(), dim)

        painter.end()


class OverlayManager:
    """管理多显示器护眼遮罩"""

    def __init__(self):
        self._windows: list[OverlayWindow] = []
        self._enabled = False
        self._temperature = 45
        self._brightness = 75

    def rebuild(self) -> None:
        """根据当前屏幕布局重建遮罩窗口"""
        for w in self._windows:
            w.close()
        self._windows.clear()

        app = QApplication.instance()
        if app is None:
            return

        for screen in app.screens():
            geo = screen.geometry()
            win = OverlayWindow(geo)
            win.apply(self._enabled, self._temperature, self._brightness)
            self._windows.append(win)

    def apply(self, enabled: bool, temperature: int, brightness: int) -> None:
        self._enabled = enabled
        self._temperature = temperature
        self._brightness = brightness

        if not self._windows:
            self.rebuild()
            return

        for w in self._windows:
            w.apply(enabled, temperature, brightness)

    def refresh_screens(self) -> None:
        self.rebuild()

    def set_visible(self, visible: bool) -> None:
        self.apply(visible, self._temperature, self._brightness)
