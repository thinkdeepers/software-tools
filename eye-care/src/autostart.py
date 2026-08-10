"""开机自启动管理（跨平台）"""

from __future__ import annotations

import sys
from pathlib import Path

IS_WINDOWS = sys.platform == "win32"

if IS_WINDOWS:
    import winreg

    RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
    APP_NAME = "EyeCareGuardian"
else:
    AUTOSTART_DIR = Path.home() / ".config" / "autostart"
    DESKTOP_FILE = AUTOSTART_DIR / "eye-care.desktop"


def _linux_desktop_content() -> str:
    script = Path(__file__).resolve().parent.parent / "run.sh"
    return f"""[Desktop Entry]
Type=Application
Name=护眼卫士
Comment=全局护眼软件，滤蓝光、调亮度、休息提醒
Exec={script}
Icon=preferences-desktop-display
Terminal=false
Categories=Utility;
X-GNOME-Autostart-enabled=true
"""


def _windows_exe_path() -> str:
    if getattr(sys, "frozen", False):
        return str(Path(sys.executable).resolve())
    return str((Path(__file__).resolve().parent.parent / "run.sh").resolve())


def set_autostart(enabled: bool) -> None:
    if IS_WINDOWS:
        try:
            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE
            ) as key:
                if enabled:
                    winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, _windows_exe_path())
                else:
                    try:
                        winreg.DeleteValue(key, APP_NAME)
                    except FileNotFoundError:
                        pass
        except OSError:
            pass
        return

    if enabled:
        AUTOSTART_DIR.mkdir(parents=True, exist_ok=True)
        DESKTOP_FILE.write_text(_linux_desktop_content(), encoding="utf-8")
    elif DESKTOP_FILE.exists():
        DESKTOP_FILE.unlink()


def is_autostart_enabled() -> bool:
    if IS_WINDOWS:
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_READ) as key:
                winreg.QueryValueEx(key, APP_NAME)
                return True
        except OSError:
            return False
    return DESKTOP_FILE.exists()
