# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置 - Windows EXE"""

import sys
from pathlib import Path

block_cipher = None
root = Path(SPECPATH)
src = root / "src"
icon_file = root / "assets" / "icon.ico"
if not icon_file.exists():
    raise FileNotFoundError(f"缺少应用图标: {icon_file}，请先运行 python scripts/generate_icon.py")

a = Analysis(
    [str(src / "main.py")],
    pathex=[str(src)],
    binaries=[],
    datas=[],
    hiddenimports=[
        "PyQt6.QtCore",
        "PyQt6.QtGui",
        "PyQt6.QtWidgets",
        "gamma_filter",
        "filter_manager",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="护眼卫士",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(icon_file),
    uac_admin=False,
)
