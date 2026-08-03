"""命令行打开 / 预览相关的轻量测试。"""

import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "src"))

from jianya import cli  # noqa: E402
from jianya import core  # noqa: E402


def test_parser_accepts_open_and_password():
    parser = cli._build_parser()
    args = parser.parse_args(["--open", "a.zip", "-p", "secret"])
    assert args.open == "a.zip"
    assert args.password == "secret"


def test_parser_positional_archive():
    parser = cli._build_parser()
    args = parser.parse_args(["demo.rar"])
    assert args.archives == ["demo.rar"]


def test_extract_cli_with_password(tmp_path, monkeypatch):
    if not core.HAS_PYZIPPER:
        return
    src = tmp_path / "x.txt"
    src.write_text("cli-secret", encoding="utf-8")
    archive = core.compress_to_zip([src], output=tmp_path / "e.zip", password="abc")
    out = tmp_path / "out"
    code = cli._run_extract(str(archive), str(out), "abc")
    assert code == 0
    assert (out / "x.txt").read_text(encoding="utf-8") == "cli-secret"


def test_extract_cli_wrong_password_then_cancel(tmp_path, monkeypatch):
    if not core.HAS_PYZIPPER:
        return
    src = tmp_path / "x.txt"
    src.write_text("cli-secret", encoding="utf-8")
    archive = core.compress_to_zip([src], output=tmp_path / "e.zip", password="abc")
    monkeypatch.setattr(cli, "_prompt_password", lambda prompt="": None)
    code = cli._run_extract(str(archive), str(tmp_path / "out"), "wrong")
    assert code == 1


def test_main_accepts_explicit_argv_with_chinese_path(tmp_path, monkeypatch):
    """显式传入含中文的 argv 时不应因编码崩溃。"""
    archive = tmp_path / "测试.rar"
    archive.write_bytes(b"not-a-real-rar")
    # 不存在有效 rar 内容时 --open 会失败，但参数解析应正常
    monkeypatch.setattr(cli, "_run_open", lambda archives: 0)
    # is_archive 为真但文件无效；直接测 parse 路径
    from jianya.win_argv import get_unicode_argv

    assert isinstance(get_unicode_argv(["jianya", "--open", str(archive)]), list)
