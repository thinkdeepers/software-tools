"""休息提醒定时器"""

from __future__ import annotations

from PyQt6.QtCore import QObject, QTimer, pyqtSignal, Qt
from PyQt6.QtGui import QFont
from PyQt6.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QVBoxLayout,
    QWidget,
)


class BreakOverlay(QWidget):
    """全屏休息提醒界面"""

    dismissed = pyqtSignal()
    snoozed = pyqtSignal()

    def __init__(self, break_seconds: int, parent: QWidget | None = None):
        super().__init__(parent)
        self._remaining = break_seconds
        self._total = break_seconds

        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, False)
        self.setStyleSheet("background-color: #1a2332;")

        layout = QVBoxLayout(self)
        layout.setAlignment(Qt.AlignmentFlag.AlignCenter)

        title = QLabel("👁 该休息一下了")
        title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        title_font = QFont()
        title_font.setPointSize(28)
        title_font.setBold(True)
        title.setFont(title_font)
        title.setStyleSheet("color: #7dd3a8; margin-bottom: 16px;")

        self._countdown = QLabel(self._format_time(self._remaining))
        self._countdown.setAlignment(Qt.AlignmentFlag.AlignCenter)
        cd_font = QFont()
        cd_font.setPointSize(64)
        cd_font.setBold(True)
        self._countdown.setFont(cd_font)
        self._countdown.setStyleSheet("color: #ffffff;")

        hint = QLabel("远眺窗外，放松双眼\n遵循 20-20-20 法则：每20分钟看20英尺外至少20秒")
        hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hint.setStyleSheet("color: #a0aec0; font-size: 16px; margin-top: 12px;")

        btn_row = QHBoxLayout()
        btn_row.setAlignment(Qt.AlignmentFlag.AlignCenter)

        skip_btn = QPushButton("跳过休息")
        skip_btn.setStyleSheet(
            "QPushButton { background: #4a5568; color: white; padding: 10px 24px;"
            " border-radius: 6px; font-size: 14px; }"
            "QPushButton:hover { background: #718096; }"
        )
        skip_btn.clicked.connect(self._on_skip)

        snooze_btn = QPushButton("稍后提醒 (5分钟)")
        snooze_btn.setStyleSheet(
            "QPushButton { background: #2d6a4f; color: white; padding: 10px 24px;"
            " border-radius: 6px; font-size: 14px; margin-left: 12px; }"
            "QPushButton:hover { background: #40916c; }"
        )
        snooze_btn.clicked.connect(self._on_snooze)

        btn_row.addWidget(skip_btn)
        btn_row.addWidget(snooze_btn)

        layout.addWidget(title)
        layout.addWidget(self._countdown)
        layout.addWidget(hint)
        layout.addLayout(btn_row)

        self._timer = QTimer(self)
        self._timer.timeout.connect(self._tick)

    def _format_time(self, seconds: int) -> str:
        m, s = divmod(max(0, seconds), 60)
        return f"{m:02d}:{s:02d}"

    def _tick(self) -> None:
        self._remaining -= 1
        self._countdown.setText(self._format_time(self._remaining))
        if self._remaining <= 0:
            self._timer.stop()
            self.dismissed.emit()
            self.close()

    def _on_skip(self) -> None:
        self._timer.stop()
        self.dismissed.emit()
        self.close()

    def _on_snooze(self) -> None:
        self._timer.stop()
        self.snoozed.emit()
        self.close()

    def show_fullscreen(self) -> None:
        app = QApplication.instance()
        if app and app.primaryScreen():
            geo = app.primaryScreen().geometry()
            for screen in app.screens():
                geo = geo.united(screen.geometry())
            self.setGeometry(geo)
        self.showFullScreen()
        self._timer.start(1000)


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
