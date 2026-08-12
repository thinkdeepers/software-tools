"""Windows 伽马曲线全局滤镜 — 作用于整个显示输出，含右键菜单、预览窗等系统 UI"""

from __future__ import annotations

import atexit
import ctypes
from ctypes import wintypes

IS_WINDOWS = True  # 仅 Windows 模块

GDI32 = ctypes.windll.gdi32
USER32 = ctypes.windll.user32

DISPLAY_DEVICE_ATTACHED_TO_DESKTOP = 0x00000001


class GammaRamp(ctypes.Structure):
    _fields_ = [
        ("red", ctypes.c_uint16 * 256),
        ("green", ctypes.c_uint16 * 256),
        ("blue", ctypes.c_uint16 * 256),
    ]


class DISPLAY_DEVICEW(ctypes.Structure):
    _fields_ = [
        ("cb", wintypes.DWORD),
        ("DeviceName", wintypes.WCHAR * 32),
        ("DeviceString", wintypes.WCHAR * 128),
        ("StateFlags", wintypes.DWORD),
        ("DeviceID", wintypes.WCHAR * 128),
        ("DeviceKey", wintypes.WCHAR * 128),
    ]


def _identity_ramp() -> GammaRamp:
    ramp = GammaRamp()
    for i in range(256):
        v = i * 257
        ramp.red[i] = v
        ramp.green[i] = v
        ramp.blue[i] = v
    return ramp


def _build_ramp(temperature: int, brightness: int) -> GammaRamp:
    """根据色温与亮度生成伽马查找表"""
    t = max(0, min(100, temperature)) / 100.0
    b = max(0, min(100, brightness)) / 100.0

    dim = 0.25 + 0.75 * b
    r_mul = 1.0
    g_mul = 1.0 - t * 0.22
    b_mul = 1.0 - t * 0.62

    ramp = GammaRamp()
    prev_r = prev_g = prev_b = 0
    for i in range(256):
        x = i / 255.0 * dim
        r = min(65535, int(x * r_mul * 65535))
        g = min(65535, int(x * g_mul * 65535))
        bl = min(65535, int(x * b_mul * 65535))
        ramp.red[i] = max(prev_r, r)
        ramp.green[i] = max(prev_g, g)
        ramp.blue[i] = max(prev_b, bl)
        prev_r, prev_g, prev_b = ramp.red[i], ramp.green[i], ramp.blue[i]
    return ramp


def _enum_active_devices() -> list[str]:
    devices: list[str] = []
    i = 0
    while True:
        dev = DISPLAY_DEVICEW()
        dev.cb = ctypes.sizeof(DISPLAY_DEVICEW)
        if not USER32.EnumDisplayDevicesW(None, i, ctypes.byref(dev), 0):
            break
        if dev.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP:
            devices.append(dev.DeviceName)
        i += 1
    return devices


class GammaFilter:
    """通过 SetDeviceGammaRamp 实现全屏一致护眼效果"""

    def __init__(self) -> None:
        self._enabled = False
        self._temperature = 45
        self._brightness = 75
        self._original: dict[str, GammaRamp] = {}
        self._gamma_supported = True
        atexit.register(self.restore)

    def apply(self, enabled: bool, temperature: int, brightness: int) -> bool:
        self._temperature = temperature
        self._brightness = brightness

        if not enabled:
            self._enabled = False
            self.restore()
            return True

        self._enabled = True
        ramp = _build_ramp(temperature, brightness)
        return self._set_all(ramp)

    def refresh(self) -> bool:
        if not self._enabled:
            return True
        return self.apply(True, self._temperature, self._brightness)

    def restore(self) -> None:
        for name, ramp in list(self._original.items()):
            hdc = GDI32.CreateDCW(name, None, None, None)
            if hdc:
                GDI32.SetDeviceGammaRamp(hdc, ctypes.byref(ramp))
                GDI32.DeleteDC(hdc)
        self._original.clear()
        self._enabled = False

    def _set_all(self, ramp: GammaRamp) -> bool:
        devices = _enum_active_devices()
        if not devices:
            return False

        ok = False
        for name in devices:
            hdc = GDI32.CreateDCW(name, None, None, None)
            if not hdc:
                continue

            if name not in self._original:
                original = GammaRamp()
                if GDI32.GetDeviceGammaRamp(hdc, ctypes.byref(original)):
                    self._original[name] = original

            if GDI32.SetDeviceGammaRamp(hdc, ctypes.byref(ramp)):
                ok = True
            GDI32.DeleteDC(hdc)

        if not ok:
            self._gamma_supported = False
        return ok

    @property
    def gamma_supported(self) -> bool:
        return self._gamma_supported
