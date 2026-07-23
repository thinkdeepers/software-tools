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
