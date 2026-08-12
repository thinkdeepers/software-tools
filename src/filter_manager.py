"""护眼滤镜统一管理 — Windows 伽马 + 遮罩回退，Linux 遮罩"""

from __future__ import annotations

import sys

from overlay import OverlayManager

IS_WINDOWS = sys.platform == "win32"

if IS_WINDOWS:
    from gamma_filter import GammaFilter


class FilterManager:
    """跨平台护眼滤镜后端"""

    def __init__(self) -> None:
        self._enabled = False
        self._temperature = 45
        self._brightness = 75
        self._overlay = OverlayManager()
        if IS_WINDOWS:
            self._gamma = GammaFilter()
            self._use_overlay_fallback = False
        else:
            self._gamma = None
            self._use_overlay_fallback = True

    def rebuild(self) -> None:
        if self._use_overlay_fallback:
            self._overlay.rebuild()

    def apply(self, enabled: bool, temperature: int, brightness: int) -> None:
        self._enabled = enabled
        self._temperature = temperature
        self._brightness = brightness

        if not enabled:
            if IS_WINDOWS and self._gamma is not None:
                self._gamma.restore()
            self._overlay.apply(False, temperature, brightness)
            return

        if IS_WINDOWS and self._gamma is not None:
            gamma_ok = self._gamma.apply(True, temperature, brightness)
            if gamma_ok and self._gamma.gamma_supported:
                self._overlay.apply(False, temperature, brightness)
                self._use_overlay_fallback = False
                return
            self._use_overlay_fallback = True
            self._gamma.restore()

        if not self._overlay._windows:
            self._overlay.rebuild()
        self._overlay.apply(True, temperature, brightness)
        self._raise_overlays()

    def refresh(self) -> None:
        """重新应用滤镜（显示器变更或系统重置伽马后）"""
        if IS_WINDOWS and self._gamma and self._enabled and not self._use_overlay_fallback:
            self._gamma.refresh()
        elif self._use_overlay_fallback and self._enabled:
            if not self._overlay._windows:
                self._overlay.rebuild()
            self._overlay.apply(True, self._temperature, self._brightness)
            self._raise_overlays()

    def refresh_screens(self) -> None:
        if IS_WINDOWS and self._gamma and self._enabled and not self._use_overlay_fallback:
            self._gamma.refresh()
        else:
            self._overlay.rebuild()
            if self._enabled:
                self._overlay.apply(True, self._temperature, self._brightness)
                self._raise_overlays()

    def shutdown(self) -> None:
        if IS_WINDOWS and self._gamma:
            self._gamma.restore()
        self._overlay.apply(False, 0, 100)

    def _raise_overlays(self) -> None:
        if not IS_WINDOWS:
            return
        from win_utils import set_topmost

        for win in self._overlay._windows:
            if win.isVisible():
                set_topmost(int(win.winId()))
