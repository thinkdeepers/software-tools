#!/usr/bin/env python3
"""护眼卫士 - 全局护眼软件"""

from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

from PyQt6.QtCore import QTimer
from PyQt6.QtWidgets import QApplication, QMessageBox, QSystemTrayIcon

# 确保 src 目录在路径中
SRC_DIR = Path(__file__).resolve().parent
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from autostart import set_autostart
from break_timer import BreakOverlay, BreakTimer
from config import AppConfig, load_config, save_config
from overlay import OverlayManager
from settings_window import SettingsWindow
from tray import TrayManager, _make_tray_icon
from win_utils import IS_WINDOWS, ensure_single_instance, hide_from_taskbar


class EyeCareApp:
    def __init__(self):
        self._config = load_config()
        self._overlay = OverlayManager()
        self._break_timer = BreakTimer()
        self._break_overlay: BreakOverlay | None = None
        self._settings: SettingsWindow | None = None

        self._schedule_timer = QTimer()
        self._schedule_timer.setInterval(30_000)
        self._schedule_timer.timeout.connect(self._check_schedule)

        self._tray = TrayManager(self._config)
        self._tray.toggle_requested.connect(self._toggle_filter)
        self._tray.settings_requested.connect(self._show_settings)
        self._tray.quit_requested.connect(self._quit)
        self._tray.preset_requested.connect(self._apply_preset)

        self._break_timer.break_started.connect(self._on_break_start)
        self._break_timer.work_resumed.connect(self._on_work_resumed)

        self._apply_config(self._config)

    def start(self) -> None:
        self._overlay.rebuild()
        self._tray.show()
        self._schedule_timer.start()
        self._check_schedule()

        if self._config.enabled:
            self._tray.notify("护眼卫士", "已启动并常驻托盘，右键图标可设置或退出")

    def _apply_config(self, config: AppConfig) -> None:
        self._config = config
        save_config(config)
        set_autostart(config.autostart)

        effective_enabled = config.enabled
        if config.schedule_enabled:
            effective_enabled = effective_enabled and self._in_schedule()

        self._overlay.apply(effective_enabled, config.temperature, config.brightness)
        self._tray.update_state(config)

        self._break_timer.configure(
            config.break_enabled, config.work_minutes, config.break_minutes
        )
        if config.break_enabled:
            self._break_timer.start()
        else:
            self._break_timer.stop()

        if self._settings:
            self._settings.update_config(config)

    def _in_schedule(self) -> bool:
        now = datetime.now().time()
        start_h, start_m = map(int, self._config.schedule_start.split(":"))
        end_h, end_m = map(int, self._config.schedule_end.split(":"))
        start = datetime.now().replace(hour=start_h, minute=start_m, second=0).time()
        end = datetime.now().replace(hour=end_h, minute=end_m, second=0).time()

        if start <= end:
            return start <= now <= end
        return now >= start or now <= end

    def _check_schedule(self) -> None:
        if not self._config.schedule_enabled:
            return
        effective = self._config.enabled and self._in_schedule()
        self._overlay.apply(
            effective, self._config.temperature, self._config.brightness
        )

    def _toggle_filter(self) -> None:
        self._config.enabled = not self._config.enabled
        self._apply_config(self._config)

    def _apply_preset(self, name: str) -> None:
        preset = next((p for p in self._config.presets if p.name == name), None)
        if preset:
            self._config.active_preset = name
            self._config.temperature = preset.temperature
            self._config.brightness = preset.brightness
            self._config.enabled = True
            self._apply_config(self._config)
            self._tray.notify("护眼卫士", f"已切换到「{name}」模式")

    def _show_settings(self) -> None:
        if self._settings is None:
            self._settings = SettingsWindow(self._config)
            self._settings.config_changed.connect(self._apply_config)
            if IS_WINDOWS:
                hide_from_taskbar(int(self._settings.winId()))
        self._settings.show()
        self._settings.raise_()
        self._settings.activateWindow()

    def _on_break_start(self, break_seconds: int) -> None:
        self._tray.notify("护眼卫士", "该休息一下了！放松双眼，远眺窗外。")

        if self._config.break_fullscreen:
            if self._break_overlay:
                self._break_overlay.close()
            self._break_overlay = BreakOverlay(break_seconds)
            self._break_overlay.dismissed.connect(self._break_timer.on_break_finished)
            self._break_overlay.snoozed.connect(self._break_timer.on_break_snoozed)
            self._break_overlay.show_fullscreen()

    def _on_work_resumed(self) -> None:
        self._tray.notify("护眼卫士", "休息结束，继续工作吧！")

    def _quit(self) -> None:
        self._overlay.apply(False, 0, 100)
        QApplication.quit()

    def on_screen_changed(self) -> None:
        self._overlay.refresh_screens()


def main() -> int:
    if IS_WINDOWS and not ensure_single_instance():
        return 0

    app = QApplication(sys.argv)
    app.setApplicationName("护眼卫士")
    app.setQuitOnLastWindowClosed(False)
    app.setWindowIcon(_make_tray_icon())

    if not QSystemTrayIcon.isSystemTrayAvailable():
        QMessageBox.critical(None, "护眼卫士", "系统托盘不可用，程序无法运行。")
        return 1

    eye_care = EyeCareApp()
    eye_care.start()

    app.primaryScreenChanged.connect(eye_care.on_screen_changed)

    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
