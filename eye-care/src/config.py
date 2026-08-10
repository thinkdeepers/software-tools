"""护眼软件配置管理"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

if sys.platform == "win32":
    CONFIG_DIR = Path(os.environ.get("APPDATA", Path.home())) / "EyeCare"
else:
    CONFIG_DIR = Path.home() / ".config" / "eye-care"
CONFIG_FILE = CONFIG_DIR / "config.json"


@dataclass
class Preset:
    name: str
    temperature: int  # 0-100，越高越暖（滤蓝光越强）
    brightness: int   # 0-100，越高越亮


DEFAULT_PRESETS: list[Preset] = [
    Preset("健康", 25, 85),
    Preset("办公", 45, 75),
    Preset("阅读", 60, 65),
    Preset("夜间", 85, 45),
    Preset("影院", 70, 55),
]


@dataclass
class AppConfig:
    enabled: bool = True
    temperature: int = 45
    brightness: int = 75
    active_preset: str = "办公"

    # 休息提醒
    break_enabled: bool = True
    work_minutes: int = 60
    break_minutes: int = 5
    break_fullscreen: bool = True

    # 定时
    schedule_enabled: bool = False
    schedule_start: str = "08:00"
    schedule_end: str = "22:00"

    # 开机自启
    autostart: bool = False

    presets: list[Preset] = field(default_factory=lambda: list(DEFAULT_PRESETS))

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["presets"] = [asdict(p) for p in self.presets]
        return data

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> AppConfig:
        presets_data = data.pop("presets", None)
        cfg = cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})
        if presets_data:
            cfg.presets = [Preset(**p) for p in presets_data]
        return cfg


def load_config() -> AppConfig:
    if CONFIG_FILE.exists():
        try:
            with open(CONFIG_FILE, encoding="utf-8") as f:
                return AppConfig.from_dict(json.load(f))
        except (json.JSONDecodeError, TypeError, KeyError):
            pass
    return AppConfig()


def save_config(config: AppConfig) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(config.to_dict(), f, ensure_ascii=False, indent=2)
