"""统一应用图标 — 托盘、窗口、EXE 共用同一设计"""

from __future__ import annotations

from pathlib import Path

from PyQt6.QtCore import Qt, QRectF
from PyQt6.QtGui import (
    QBrush,
    QColor,
    QIcon,
    QLinearGradient,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
)

ASSETS_DIR = Path(__file__).resolve().parent.parent / "assets"
ICON_ICO_PATH = ASSETS_DIR / "icon.ico"


def render_app_pixmap(size: int) -> QPixmap:
    """绘制现代简约护眼图标：渐变圆底 + 眼睛图形"""
    pix = QPixmap(size, size)
    pix.fill(Qt.GlobalColor.transparent)

    painter = QPainter(pix)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)

    s = float(size)
    margin = s * 0.06

    # 背景渐变圆
    bg_grad = QLinearGradient(margin, margin, s - margin, s - margin)
    bg_grad.setColorAt(0.0, QColor("#6ee7b7"))
    bg_grad.setColorAt(0.5, QColor("#34d399"))
    bg_grad.setColorAt(1.0, QColor("#059669"))
    painter.setBrush(QBrush(bg_grad))
    painter.setPen(QPen(QColor("#047857"), max(1, int(s * 0.02))))
    painter.drawEllipse(QRectF(margin, margin, s - 2 * margin, s - 2 * margin))

    # 眼睛轮廓
    cx, cy = s * 0.5, s * 0.48
    eye_w, eye_h = s * 0.52, s * 0.30
    eye_path = QPainterPath()
    eye_path.moveTo(cx - eye_w / 2, cy)
    eye_path.quadTo(cx, cy - eye_h / 2, cx + eye_w / 2, cy)
    eye_path.quadTo(cx, cy + eye_h / 2, cx - eye_w / 2, cy)
    painter.setBrush(QBrush(QColor(255, 255, 255, 235)))
    painter.setPen(QPen(QColor(255, 255, 255, 180), max(1, int(s * 0.015))))
    painter.drawPath(eye_path)

    # 瞳孔
    pupil_r = s * 0.09
    painter.setBrush(QBrush(QColor("#065f46")))
    painter.setPen(Qt.PenStyle.NoPen)
    painter.drawEllipse(QRectF(cx - pupil_r, cy - pupil_r, pupil_r * 2, pupil_r * 2))

    # 高光
    hl_r = s * 0.035
    painter.setBrush(QBrush(QColor(255, 255, 255, 200)))
    painter.drawEllipse(QRectF(cx - pupil_r * 0.3, cy - pupil_r * 0.5, hl_r, hl_r))

    painter.end()
    return pix


def get_app_icon() -> QIcon:
    """获取多尺寸应用图标"""
    icon = QIcon()
    for size in (16, 24, 32, 48, 64, 128, 256):
        icon.addPixmap(render_app_pixmap(size))
    return icon


def get_icon_path() -> str | None:
    """返回 ICO 文件路径（用于 PyInstaller / 桌面快捷方式）"""
    if ICON_ICO_PATH.exists():
        return str(ICON_ICO_PATH)
    return None
