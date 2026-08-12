"""统一应用图标 — 托盘、窗口、EXE 共用同一设计"""

from __future__ import annotations

from pathlib import Path

from PyQt6.QtCore import Qt, QRectF, QPointF
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
    """绘制现代简约护眼图标：渐变圆底 + 眼睛图形

    小尺寸（<=32）使用简化图形，保证资源管理器缩略图清晰可见。
    """
    pix = QPixmap(size, size)
    pix.fill(Qt.GlobalColor.transparent)

    painter = QPainter(pix)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)

    s = float(size)
    margin = max(1.0, s * 0.04)

    # 不透明背景圆（避免透明导致 EXE 图标“消失”）
    bg_grad = QLinearGradient(margin, margin, s - margin, s - margin)
    bg_grad.setColorAt(0.0, QColor("#34d399"))
    bg_grad.setColorAt(1.0, QColor("#059669"))
    painter.setBrush(QBrush(bg_grad))
    painter.setPen(Qt.PenStyle.NoPen)
    painter.drawEllipse(QRectF(margin, margin, s - 2 * margin, s - 2 * margin))

    cx, cy = s * 0.5, s * 0.48

    if size <= 24:
        # 超小尺寸：白色圆 + 深绿瞳孔
        eye_r = s * 0.22
        painter.setBrush(QBrush(QColor("#ffffff")))
        painter.drawEllipse(QRectF(cx - eye_r, cy - eye_r, eye_r * 2, eye_r * 2))
        pupil_r = s * 0.10
        painter.setBrush(QBrush(QColor("#064e3b")))
        painter.drawEllipse(QRectF(cx - pupil_r, cy - pupil_r, pupil_r * 2, pupil_r * 2))
    else:
        # 眼睛轮廓
        eye_w, eye_h = s * 0.54, s * 0.32
        eye_path = QPainterPath()
        eye_path.moveTo(cx - eye_w / 2, cy)
        eye_path.quadTo(cx, cy - eye_h / 2, cx + eye_w / 2, cy)
        eye_path.quadTo(cx, cy + eye_h / 2, cx - eye_w / 2, cy)
        painter.setBrush(QBrush(QColor("#ffffff")))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawPath(eye_path)

        # 瞳孔
        pupil_r = s * 0.10
        painter.setBrush(QBrush(QColor("#064e3b")))
        painter.drawEllipse(QRectF(cx - pupil_r, cy - pupil_r, pupil_r * 2, pupil_r * 2))

        if size >= 48:
            hl_r = s * 0.035
            painter.setBrush(QBrush(QColor(255, 255, 255, 220)))
            painter.drawEllipse(
                QRectF(cx - pupil_r * 0.35, cy - pupil_r * 0.55, hl_r, hl_r)
            )

    painter.end()
    return pix


def get_app_icon() -> QIcon:
    """获取多尺寸应用图标"""
    icon = QIcon()
    for size in (16, 24, 32, 48, 64, 128, 256):
        icon.addPixmap(render_app_pixmap(size))
    return icon


def get_icon_path() -> str | None:
    if ICON_ICO_PATH.exists():
        return str(ICON_ICO_PATH)
    return None
