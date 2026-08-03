"""核心压缩 / 解压逻辑的测试。"""

import os
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "src"))

from jianya import core  # noqa: E402


def _make_tree(base: Path) -> None:
    (base / "a.txt").write_text("hello", encoding="utf-8")
    sub = base / "sub"
    sub.mkdir()
    (sub / "b.txt").write_text("world", encoding="utf-8")


def test_compress_single_file(tmp_path):
    f = tmp_path / "note.txt"
    f.write_text("内容", encoding="utf-8")
    out = core.compress_to_zip([f])
    assert out.exists()
    assert out.suffix == ".zip"
    with zipfile.ZipFile(out) as zf:
        assert zf.namelist() == ["note.txt"]


def test_compress_directory_preserves_structure(tmp_path):
    src = tmp_path / "proj"
    src.mkdir()
    _make_tree(src)
    out = core.compress_to_zip([src])
    with zipfile.ZipFile(out) as zf:
        names = set(zf.namelist())
    assert "proj/a.txt" in names
    assert "proj/sub/b.txt" in names


def test_compress_then_extract_roundtrip(tmp_path):
    src = tmp_path / "proj"
    src.mkdir()
    _make_tree(src)
    zip_path = core.compress_to_zip([src], output=tmp_path / "out.zip")

    dest = core.extract_archive(zip_path, output_dir=tmp_path / "unpacked")
    assert (dest / "proj" / "a.txt").read_text(encoding="utf-8") == "hello"
    assert (dest / "proj" / "sub" / "b.txt").read_text(encoding="utf-8") == "world"


def test_output_extension_forced_to_zip(tmp_path):
    f = tmp_path / "x.txt"
    f.write_text("x", encoding="utf-8")
    out = core.compress_to_zip([f], output=tmp_path / "archive.dat")
    assert out.suffix == ".zip"


def test_unique_path_avoids_overwrite(tmp_path):
    f = tmp_path / "x.txt"
    f.write_text("x", encoding="utf-8")
    first = core.compress_to_zip([f], output=tmp_path / "a.zip")
    second = core.compress_to_zip([f], output=tmp_path / "a.zip")
    assert first != second
    assert first.exists() and second.exists()


def test_is_archive():
    assert core.is_archive("foo.zip")
    assert core.is_archive("foo.tar.gz")
    assert core.is_archive("FOO.RAR")
    assert not core.is_archive("foo.txt")


def test_fix_archive_filename_gbk_mojibake():
    raw = "测试文件.txt"
    mojibake = raw.encode("gbk").decode("cp437")
    assert core.fix_archive_filename(mojibake) == raw
    # 已是正确中文时保持不变
    assert core.fix_archive_filename(raw) == raw


def test_extract_missing_file(tmp_path):
    with pytest.raises(core.ArchiveError):
        core.extract_archive(tmp_path / "nope.zip")


def test_compress_empty_inputs():
    with pytest.raises(core.ArchiveError):
        core.compress_to_zip([])


def test_extract_targz_roundtrip(tmp_path):
    import tarfile

    src = tmp_path / "data"
    src.mkdir()
    (src / "f.txt").write_text("tar-content", encoding="utf-8")
    archive = tmp_path / "data.tar.gz"
    with tarfile.open(archive, "w:gz") as tf:
        tf.add(src / "f.txt", arcname="f.txt")

    dest = core.extract_archive(archive)
    assert (dest / "f.txt").read_text(encoding="utf-8") == "tar-content"


def test_extract_gz_plain(tmp_path):
    import gzip

    archive = tmp_path / "single.txt.gz"
    with gzip.open(archive, "wb") as f:
        f.write("plain".encode("utf-8"))
    dest = core.extract_archive(archive)
    assert (dest / "single.txt").read_text(encoding="utf-8") == "plain"


def test_zip_slip_protection(tmp_path):
    malicious = tmp_path / "evil.zip"
    with zipfile.ZipFile(malicious, "w") as zf:
        zf.writestr("../escape.txt", "bad")
    with pytest.raises(core.ArchiveError):
        core.extract_archive(malicious, output_dir=tmp_path / "out")


def test_list_zip_preview(tmp_path):
    src = tmp_path / "a.txt"
    src.write_text("preview", encoding="utf-8")
    archive = core.compress_to_zip([src], output=tmp_path / "p.zip")
    members = core.list_archive(archive)
    names = [m.name for m in members]
    assert "a.txt" in names
    assert members[0].size == len("preview".encode("utf-8"))


@pytest.mark.skipif(not core.HAS_PYZIPPER, reason="需要 pyzipper")
def test_encrypted_zip_roundtrip(tmp_path):
    src = tmp_path / "secret.txt"
    src.write_text("top-secret", encoding="utf-8")
    archive = core.compress_to_zip(
        [src], output=tmp_path / "enc.zip", password="s3cret"
    )
    assert core.archive_is_encrypted(archive)

    with pytest.raises(core.PasswordRequiredError):
        core.extract_archive(archive, output_dir=tmp_path / "bad")

    dest = core.extract_archive(
        archive, output_dir=tmp_path / "good", password="s3cret"
    )
    assert (dest / "secret.txt").read_text(encoding="utf-8") == "top-secret"

    members = core.list_archive(archive, password="s3cret")
    assert any(m.encrypted for m in members)


@pytest.mark.skipif(not core.HAS_7Z, reason="需要 py7zr")
def test_extract_7z(tmp_path):
    import py7zr

    src = tmp_path / "n.txt"
    src.write_text("seven", encoding="utf-8")
    archive = tmp_path / "n.7z"
    with py7zr.SevenZipFile(archive, "w") as zf:
        zf.write(src, "n.txt")

    members = core.list_archive(archive)
    assert any(m.name.endswith("n.txt") for m in members)
    dest = core.extract_archive(archive, output_dir=tmp_path / "out7")
    assert (dest / "n.txt").read_text(encoding="utf-8") == "seven"


@pytest.mark.skipif(not core.HAS_7Z, reason="需要 py7zr")
def test_encrypted_7z_needs_password(tmp_path):
    import py7zr

    src = tmp_path / "n.txt"
    src.write_text("seven-secret", encoding="utf-8")
    archive = tmp_path / "enc.7z"
    with py7zr.SevenZipFile(archive, "w", password="pw7") as zf:
        zf.write(src, "n.txt")

    assert core.archive_is_encrypted(archive)
    with pytest.raises(core.PasswordRequiredError):
        core.extract_archive(archive, output_dir=tmp_path / "bad7")

    dest = core.extract_archive(archive, output_dir=tmp_path / "ok7", password="pw7")
    assert (dest / "n.txt").read_text(encoding="utf-8") == "seven-secret"


@pytest.mark.skipif(not core.HAS_RAR, reason="需要 rarfile")
def test_extract_rar_if_tool_available(tmp_path):
    """若系统有 rar/unrar，则验证 rar 解压与预览。"""
    import shutil
    import subprocess

    if not shutil.which("rar"):
        pytest.skip("系统无 rar 命令，无法生成测试压缩包")

    src = tmp_path / "r.txt"
    src.write_text("rar-content", encoding="utf-8")
    archive = tmp_path / "r.rar"
    subprocess.run(
        ["rar", "a", "-ep1", str(archive), str(src)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    members = core.list_archive(archive)
    assert any("r.txt" in m.name for m in members)
    dest = core.extract_archive(archive, output_dir=tmp_path / "outrar")
    extracted = list(Path(dest).rglob("r.txt"))
    assert extracted
    assert extracted[0].read_text(encoding="utf-8") == "rar-content"


@pytest.mark.skipif(not core.HAS_RAR, reason="需要 rarfile")
def test_encrypted_rar_needs_password(tmp_path):
    import shutil
    import subprocess

    if not shutil.which("rar"):
        pytest.skip("系统无 rar 命令，无法生成测试压缩包")

    src = tmp_path / "r.txt"
    src.write_text("rar-secret", encoding="utf-8")
    archive = tmp_path / "enc.rar"
    subprocess.run(
        ["rar", "a", "-ep1", "-hpsecret", str(archive), str(src)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    assert core.archive_is_encrypted(archive)
    with pytest.raises(core.PasswordRequiredError):
        core.list_archive(archive)
    with pytest.raises(core.PasswordRequiredError):
        core.extract_archive(archive, output_dir=tmp_path / "bad")

    members = core.list_archive(archive, password="secret")
    assert any("r.txt" in m.name for m in members)
    dest = core.extract_archive(
        archive, output_dir=tmp_path / "ok", password="secret"
    )
    extracted = list(Path(dest).rglob("r.txt"))
    assert extracted
    assert extracted[0].read_text(encoding="utf-8") == "rar-secret"
