"""主界面窗口"""

from __future__ import annotations

from PyQt6.QtCore import Qt, pyqtSignal, QTime
from PyQt6.QtGui import QCloseEvent
from PyQt6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSlider,
    QSpinBox,
    QTabWidget,
    QTimeEdit,
    QVBoxLayout,
    QWidget,
)

from config import AppConfig, save_config
from icon import get_app_icon, render_app_pixmap
from theme import APP_STYLESHEET, MAIN_WINDOW_EXTRA


class SliderRow(QWidget):
    value_changed = pyqtSignal(int)

    def __init__(self, label: str, minimum: int, maximum: int, value: int, parent=None):
        super().__init__(parent)
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        self._label = QLabel(label)
        self._slider = QSlider(Qt.Orientation.Horizontal)
        self._slider.setRange(minimum, maximum)
        self._slider.setValue(value)
        self._value_label = QLabel(str(value))
        self._value_label.setMinimumWidth(36)
        self._value_label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        self._value_label.setStyleSheet("color: #16a34a; font-weight: 600;")

        self._slider.valueChanged.connect(self._on_change)
        layout.addWidget(self._label)
        layout.addWidget(self._slider, 1)
        layout.addWidget(self._value_label)

    def _on_change(self, val: int) -> None:
        self._value_label.setText(str(val))
        self.value_changed.emit(val)

    def value(self) -> int:
        return self._slider.value()

    def set_value(self, val: int) -> None:
        self._slider.setValue(val)


class MainWindow(QWidget):
    config_changed = pyqtSignal(object)
    minimized_to_tray = pyqtSignal()

    def __init__(self, config: AppConfig, parent=None):
        super().__init__(parent)
        self._config = config
        self.setObjectName("MainWindow")
        self.setWindowTitle("护眼卫士")
        self.setWindowIcon(get_app_icon())
        self.setWindowFlags(
            Qt.WindowType.Window
            | Qt.WindowType.WindowCloseButtonHint
            | Qt.WindowType.WindowMinimizeButtonHint
        )
        self.setMinimumSize(500, 520)
        self.setStyleSheet(APP_STYLESHEET + MAIN_WINDOW_EXTRA)

        root = QVBoxLayout(self)
        root.setContentsMargins(20, 16, 20, 12)
        root.setSpacing(12)

        # 顶部：图标 + 标题 + 状态
        header_row = QHBoxLayout()
        icon_label = QLabel()
        icon_label.setPixmap(render_app_pixmap(48))
        icon_label.setFixedSize(48, 48)

        title_col = QVBoxLayout()
        title_col.setSpacing(2)
        self._title = QLabel("护眼卫士")
        self._title.setObjectName("appTitle")
        subtitle = QLabel("轻松护眼，守护每一刻")
        subtitle.setObjectName("appSubtitle")
        title_col.addWidget(self._title)
        title_col.addWidget(subtitle)

        self._status_badge = QLabel()
        self._status_badge.setObjectName("statusBadge")
        self._update_status_badge(config.enabled)

        header_row.addWidget(icon_label)
        header_row.addLayout(title_col, 1)
        header_row.addWidget(self._status_badge, 0, Qt.AlignmentFlag.AlignTop)
        root.addLayout(header_row)

        tabs = QTabWidget()

        # --- 护眼 tab ---
        filter_tab = QWidget()
        filter_layout = QVBoxLayout(filter_tab)
        filter_layout.setSpacing(10)

        preset_group = QGroupBox("预设模式")
        preset_layout = QHBoxLayout(preset_group)
        self._preset_combo = QComboBox()
        for p in config.presets:
            self._preset_combo.addItem(p.name)
        idx = next((i for i, p in enumerate(config.presets) if p.name == config.active_preset), 0)
        self._preset_combo.setCurrentIndex(idx)
        self._preset_combo.currentTextChanged.connect(self._on_preset_changed)
        preset_layout.addWidget(QLabel("选择模式"))
        preset_layout.addWidget(self._preset_combo, 1)

        adjust_group = QGroupBox("手动调节")
        adjust_layout = QFormLayout(adjust_group)
        adjust_layout.setSpacing(12)
        self._temp_slider = SliderRow("色温（暖色）", 0, 100, config.temperature)
        self._bright_slider = SliderRow("亮度", 0, 100, config.brightness)
        self._temp_slider.value_changed.connect(self._on_manual_change)
        self._bright_slider.value_changed.connect(self._on_manual_change)
        adjust_layout.addRow(self._temp_slider)
        adjust_layout.addRow(self._bright_slider)

        self._enabled_cb = QCheckBox("启用护眼滤镜")
        self._enabled_cb.setChecked(config.enabled)
        self._enabled_cb.toggled.connect(self._on_enabled_toggled)

        filter_layout.addWidget(self._enabled_cb)
        filter_layout.addWidget(preset_group)
        filter_layout.addWidget(adjust_group)
        filter_layout.addStretch()

        # --- 休息 tab ---
        break_tab = QWidget()
        break_layout = QVBoxLayout(break_tab)

        break_group = QGroupBox("休息提醒")
        break_form = QFormLayout(break_group)
        break_form.setSpacing(10)

        self._break_enabled = QCheckBox("启用休息提醒")
        self._break_enabled.setChecked(config.break_enabled)

        self._work_spin = QSpinBox()
        self._work_spin.setRange(5, 180)
        self._work_spin.setSuffix(" 分钟")
        self._work_spin.setValue(config.work_minutes)

        self._break_spin = QSpinBox()
        self._break_spin.setRange(1, 30)
        self._break_spin.setSuffix(" 分钟")
        self._break_spin.setValue(config.break_minutes)

        self._break_fullscreen = QCheckBox("全屏休息界面")
        self._break_fullscreen.setChecked(config.break_fullscreen)

        break_form.addRow(self._break_enabled)
        break_form.addRow("工作时长", self._work_spin)
        break_form.addRow("休息时长", self._break_spin)
        break_form.addRow(self._break_fullscreen)

        break_layout.addWidget(break_group)
        break_layout.addStretch()

        # --- 定时 tab ---
        schedule_tab = QWidget()
        schedule_layout = QVBoxLayout(schedule_tab)

        sched_group = QGroupBox("定时护眼")
        sched_form = QFormLayout(sched_group)
        sched_form.setSpacing(10)

        self._schedule_enabled = QCheckBox("仅在指定时段启用护眼")
        self._schedule_enabled.setChecked(config.schedule_enabled)

        self._start_time = QTimeEdit()
        self._start_time.setDisplayFormat("HH:mm")
        h, m = map(int, config.schedule_start.split(":"))
        self._start_time.setTime(QTime(h, m))

        self._end_time = QTimeEdit()
        self._end_time.setDisplayFormat("HH:mm")
        h, m = map(int, config.schedule_end.split(":"))
        self._end_time.setTime(QTime(h, m))

        sched_form.addRow(self._schedule_enabled)
        sched_form.addRow("开始时间", self._start_time)
        sched_form.addRow("结束时间", self._end_time)

        self._autostart_cb = QCheckBox("开机自动启动")
        self._autostart_cb.setChecked(config.autostart)

        schedule_layout.addWidget(sched_group)
        schedule_layout.addWidget(self._autostart_cb)
        schedule_layout.addStretch()

        tabs.addTab(filter_tab, "护眼滤镜")
        tabs.addTab(break_tab, "休息提醒")
        tabs.addTab(schedule_tab, "定时与启动")

        root.addWidget(tabs)

        btn_row = QHBoxLayout()
        btn_row.addStretch()
        apply_btn = QPushButton("应用设置")
        apply_btn.clicked.connect(self._apply)
        tray_btn = QPushButton("最小化到托盘")
        tray_btn.setObjectName("secondaryBtn")
        tray_btn.clicked.connect(self.minimize_to_tray)
        btn_row.addWidget(apply_btn)
        btn_row.addWidget(tray_btn)
        root.addLayout(btn_row)

        footer = QLabel(
            "本软件由AI自动生成\n"
            "致力于造福全人类，共建简单、和平、美好的生态————洞穴理论工作室 出品"
        )
        footer.setObjectName("footer")
        footer.setAlignment(Qt.AlignmentFlag.AlignCenter)
        footer.setWordWrap(True)
        root.addWidget(footer)

        for w in [
            self._break_enabled, self._work_spin, self._break_spin,
            self._break_fullscreen, self._schedule_enabled,
            self._start_time, self._end_time, self._autostart_cb,
        ]:
            if hasattr(w, "toggled"):
                w.toggled.connect(self._emit_change)
            elif hasattr(w, "valueChanged"):
                w.valueChanged.connect(self._emit_change)
            elif hasattr(w, "timeChanged"):
                w.timeChanged.connect(self._emit_change)

    def _update_status_badge(self, enabled: bool) -> None:
        if enabled:
            self._status_badge.setText("● 护眼中")
            self._status_badge.setProperty("off", False)
        else:
            self._status_badge.setText("○ 已关闭")
            self._status_badge.setProperty("off", True)
        self._status_badge.style().unpolish(self._status_badge)
        self._status_badge.style().polish(self._status_badge)

    def _on_enabled_toggled(self, checked: bool) -> None:
        self._update_status_badge(checked)
        self._emit_change()

    def _on_preset_changed(self, name: str) -> None:
        preset = next((p for p in self._config.presets if p.name == name), None)
        if preset:
            self._temp_slider.set_value(preset.temperature)
            self._bright_slider.set_value(preset.brightness)
            self._config.active_preset = name
            self._emit_change()

    def _on_manual_change(self, _val: int) -> None:
        self._config.active_preset = "自定义"
        if self._preset_combo.findText("自定义") < 0:
            self._preset_combo.addItem("自定义")
        self._preset_combo.setCurrentText("自定义")
        self._emit_change()

    def _collect_config(self) -> AppConfig:
        cfg = self._config
        cfg.enabled = self._enabled_cb.isChecked()
        cfg.temperature = self._temp_slider.value()
        cfg.brightness = self._bright_slider.value()
        cfg.break_enabled = self._break_enabled.isChecked()
        cfg.work_minutes = self._work_spin.value()
        cfg.break_minutes = self._break_spin.value()
        cfg.break_fullscreen = self._break_fullscreen.isChecked()
        cfg.schedule_enabled = self._schedule_enabled.isChecked()
        cfg.schedule_start = self._start_time.time().toString("HH:mm")
        cfg.schedule_end = self._end_time.time().toString("HH:mm")
        cfg.autostart = self._autostart_cb.isChecked()
        return cfg

    def _emit_change(self, *_args) -> None:
        self.config_changed.emit(self._collect_config())

    def _apply(self) -> None:
        cfg = self._collect_config()
        save_config(cfg)
        self._config = cfg
        self.config_changed.emit(cfg)

    def show_main(self) -> None:
        if self.isMinimized():
            self.showNormal()
        self.show()
        self.raise_()
        self.activateWindow()

    def minimize_to_tray(self) -> None:
        self.hide()
        self.minimized_to_tray.emit()

    def update_config(self, config: AppConfig) -> None:
        self._config = config
        self._update_status_badge(config.enabled)

    def closeEvent(self, event: QCloseEvent) -> None:
        event.ignore()
        self.minimize_to_tray()


SettingsWindow = MainWindow
