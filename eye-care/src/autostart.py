"""开机自启动管理"""

from __future__ import annotations

from pathlib import Path

AUTOSTART_DIR = Path.home() / ".config" / "autostart"
DESKTOP_FILE = AUTOSTART_DIR / "eye-care.desktop"


def _desktop_content() -> str:
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


def set_autostart(enabled: bool) -> None:
    if enabled:
        AUTOSTART_DIR.mkdir(parents=True, exist_ok=True)
        DESKTOP_FILE.write_text(_desktop_content(), encoding="utf-8")
    elif DESKTOP_FILE.exists():
        DESKTOP_FILE.unlink()


def is_autostart_enabled() -> bool:
    return DESKTOP_FILE.exists()
