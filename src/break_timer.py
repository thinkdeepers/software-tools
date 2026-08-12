"""休息提醒定时器"""

from __future__ import annotations

from PyQt6.QtCore import QObject, QTimer, pyqtSignal, Qt, QRect
from PyQt6.QtGui import QFont
from PyQt6.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QVBoxLayout,
    QWidget,
)


class _BreakScreenWindow(QWidget):
    """单显示器休息遮罩"""

    def __init__(
        self,
        geometry: QRect,
        is_primary: bool,
        manager: BreakOverlayManager,
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self._manager = manager
        self._is_primary = is_primary

        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, False)
        self.setStyleSheet("""
            QWidget { background-color: #0f172a; }
            QLabel { color: #e2e8f0; }
            QPushButton {
                background: #22c55e; color: white; border: none;
                border-radius: 10px; padding: 10px 24px; font-size: 14px; font-weight: 600;
            }
            QPushButton:hover { background: #16a34a; }
        """)
        self.setGeometry(geometry)

        layout = QVBoxLayout(self)
        layout.setAlignment(Qt.AlignmentFlag.AlignCenter)

        title = QLabel("👁 该休息一下了" if is_primary else "👁 休息时间")
        title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        title_font = QFont()
        title_font.setPointSize(28 if is_primary else 22)
        title_font.setBold(True)
        title.setFont(title_font)
        title.setStyleSheet("color: #6ee7b7; margin-bottom: 16px;")

        self._countdown = QLabel(manager.format_time(manager.remaining))
        self._countdown.setAlignment(Qt.AlignmentFlag.AlignCenter)
        cd_font = QFont()
        cd_font.setPointSize(64 if is_primary else 48)
        cd_font.setBold(True)
        self._countdown.setFont(cd_font)
        self._countdown.setStyleSheet("color: #f8fafc;")
        manager.register_countdown(self._countdown)

        layout.addWidget(title)
        layout.addWidget(self._countdown)

        if is_primary:
            hint = QLabel(
                "远眺窗外，放松双眼\n遵循 20-20-20 法则：每20分钟看20英尺外至少20秒"
            )
            hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
            hint.setStyleSheet("color: #94a3b8; font-size: 16px; margin-top: 12px;")
            layout.addWidget(hint)

            btn_row = QHBoxLayout()
            btn_row.setAlignment(Qt.AlignmentFlag.AlignCenter)

            skip_btn = QPushButton("跳过休息")
            skip_btn.setStyleSheet(
                "QPushButton { background: #334155; color: white; padding: 10px 24px;"
                " border-radius: 10px; font-size: 14px; }"
                "QPushButton:hover { background: #475569; }"
            )
            skip_btn.clicked.connect(manager.skip)

            snooze_btn = QPushButton("稍后提醒 (5分钟)")
            snooze_btn.setStyleSheet(
                "QPushButton { background: #22c55e; color: white; padding: 10px 24px;"
                " border-radius: 10px; font-size: 14px; margin-left: 12px; }"
                "QPushButton:hover { background: #16a34a; }"
            )
            snooze_btn.clicked.connect(manager.snooze)

            btn_row.addWidget(skip_btn)
            btn_row.addWidget(snooze_btn)
            layout.addLayout(btn_row)
        else:
            sub = QLabel("请在主屏操作")
            sub.setAlignment(Qt.AlignmentFlag.AlignCenter)
            sub.setStyleSheet("color: #a0aec0; font-size: 14px; margin-top: 16px;")
            layout.addWidget(sub)

    def show_on_screen(self) -> None:
        self.show()
        self.raise_()
        if self._is_primary:
            self.activateWindow()


class BreakOverlayManager(QObject):
    """多显示器全屏休息界面管理"""

    dismissed = pyqtSignal()
    snoozed = pyqtSignal()

    def __init__(self, break_seconds: int, parent: QObject | None = None):
        super().__init__(parent)
        self._remaining = break_seconds
        self._windows: list[_BreakScreenWindow] = []
        self._countdown_labels: list[QLabel] = []

        self._timer = QTimer(self)
        self._timer.setInterval(1000)
        self._timer.timeout.connect(self._tick)

    @property
    def remaining(self) -> int:
        return self._remaining

    def register_countdown(self, label: QLabel) -> None:
        self._countdown_labels.append(label)

    @staticmethod
    def format_time(seconds: int) -> str:
        m, s = divmod(max(0, seconds), 60)
        return f"{m:02d}:{s:02d}"

    def _update_countdowns(self) -> None:
        text = self.format_time(self._remaining)
        for label in self._countdown_labels:
            label.setText(text)

    def show_fullscreen(self) -> None:
        self.close_all()
        app = QApplication.instance()
        if not app:
            return

        primary = app.primaryScreen()
        for screen in app.screens():
            is_primary = screen is primary
            win = _BreakScreenWindow(screen.geometry(), is_primary, self)
            win.show_on_screen()
            self._windows.append(win)

        self._timer.start()

    def close_all(self) -> None:
        self._timer.stop()
        for win in self._windows:
            win.close()
        self._windows.clear()
        self._countdown_labels.clear()

    def _tick(self) -> None:
        self._remaining -= 1
        self._update_countdowns()
        if self._remaining <= 0:
            self._finish()

    def skip(self) -> None:
        self._finish()

    def snooze(self) -> None:
        self.close_all()
        self.snoozed.emit()

    def _finish(self) -> None:
        self.close_all()
        self.dismissed.emit()


# 兼容旧接口
BreakOverlay = BreakOverlayManager


class BreakTimer(QObject):
    """工作/休息周期管理"""

    break_started = pyqtSignal(int)  # break_seconds
    work_resumed = pyqtSignal()

    def __init__(self, parent: QObject | None = None):
        super().__init__(parent)
        self._enabled = True
        self._work_minutes = 60
        self._break_minutes = 5
        self._work_seconds_left = 0
        self._on_break = False
        self._snooze_seconds = 0

        self._timer = QTimer(self)
        self._timer.setInterval(1000)
        self._timer.timeout.connect(self._tick)

    def configure(self, enabled: bool, work_minutes: int, break_minutes: int) -> None:
        self._enabled = enabled
        self._work_minutes = max(1, work_minutes)
        self._break_minutes = max(1, break_minutes)
        if enabled and not self._on_break:
            self.reset_work_timer()
            self._timer.start()
        elif not enabled:
            self._timer.stop()

    def reset_work_timer(self) -> None:
        self._on_break = False
        self._work_seconds_left = self._work_minutes * 60
        self._snooze_seconds = 0

    def start(self) -> None:
        if self._enabled:
            self.reset_work_timer()
            self._timer.start()

    def stop(self) -> None:
        self._timer.stop()

    def on_break_finished(self) -> None:
        self._on_break = False
        self.reset_work_timer()
        self.work_resumed.emit()

    def on_break_snoozed(self) -> None:
        self._on_break = False
        self._snooze_seconds = 5 * 60
        self._work_seconds_left = self._snooze_seconds

    def _tick(self) -> None:
        if not self._enabled or self._on_break:
            return

        self._work_seconds_left -= 1
        if self._work_seconds_left <= 0:
            self._on_break = True
            self.break_started.emit(self._break_minutes * 60)

    @property
    def work_seconds_left(self) -> int:
        return self._work_seconds_left

    @property
    def on_break(self) -> bool:
        return self._on_break
