"""文件关联模块的轻量测试（不依赖 Windows 注册表）。"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "src"))

from jianya import context_menu  # noqa: E402


def test_associated_extensions_include_common_archives():
    exts = context_menu.associated_extensions()
    for expected in (".zip", ".7z", ".rar", ".tar", ".gz", ".tgz"):
        assert expected in exts


def test_progid_constant():
    assert context_menu._PROGID == "Jianya.Archive"


def test_launcher_open_and_extract_commands():
    open_cmd = context_menu._launcher("--open")
    extract_cmd = context_menu._launcher("--extract")
    assert "--open" in open_cmd
    assert "--extract" in extract_cmd
    assert open_cmd.rstrip().endswith('"%1"')
    assert extract_cmd.rstrip().endswith('"%1"')


def test_non_windows_install_raises(monkeypatch):
    monkeypatch.setattr(os, "name", "posix")
    try:
        context_menu.install()
        assert False, "应抛出 ContextMenuError"
    except context_menu.ContextMenuError as exc:
        assert "Windows" in str(exc)
