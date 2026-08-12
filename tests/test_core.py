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
    # 包内唯一根目录为 proj/ → 解压到 unpacked/proj，并剥掉一层
    assert dest == tmp_path / "unpacked" / "proj"
    assert (dest / "a.txt").read_text(encoding="utf-8") == "hello"
    assert (dest / "sub" / "b.txt").read_text(encoding="utf-8") == "world"
    assert not (dest / "proj").exists()


def test_extract_into_existing_dir_not_renamed(tmp_path):
    """「解压到…」选中已有文件夹时，应在其下创建同名子目录，而不是改名为「文件夹 (1)」。"""
    src = tmp_path / "note.txt"
    src.write_text("hi", encoding="utf-8")
    archive = core.compress_to_zip([src], output=tmp_path / "n.zip")

    chosen = tmp_path / "chosen"
    chosen.mkdir()
    (chosen / "keep.txt").write_text("keep", encoding="utf-8")

    dest = core.extract_archive(archive, output_dir=chosen)
    assert dest == chosen / "n"
    assert not (tmp_path / "chosen (1)").exists()
    assert (dest / "note.txt").read_text(encoding="utf-8") == "hi"
    assert (chosen / "keep.txt").read_text(encoding="utf-8") == "keep"


def test_extract_again_creates_numbered_folder(tmp_path):
    """目录已有解压结果时，再次解压应生成 demo (1) 等新目录。"""
    src = tmp_path / "a.txt"
    src.write_text("v1", encoding="utf-8")
    archive = core.compress_to_zip([src], output=tmp_path / "demo.zip")

    first = core.extract_archive(archive)
    assert first == tmp_path / "demo"
    assert (first / "a.txt").read_text(encoding="utf-8") == "v1"

    src.write_text("v2", encoding="utf-8")
    archive = core.compress_to_zip([src], output=tmp_path / "demo.zip")
    # 覆盖原 zip 后再解压
    second = core.extract_archive(archive)
    assert second == tmp_path / "demo (1)"
    assert (second / "a.txt").read_text(encoding="utf-8") == "v2"
    # 原目录保持不变
    assert (first / "a.txt").read_text(encoding="utf-8") == "v1"


def test_extract_default_goes_to_archive_parent(tmp_path):
    """未指定输出目录时，在压缩包所在目录下创建同名子目录。"""
    src = tmp_path / "a.txt"
    src.write_text("here", encoding="utf-8")
    archive = core.compress_to_zip([src], output=tmp_path / "pack.zip")

    dest = core.extract_archive(archive)
    assert dest == tmp_path / "pack"
    assert (dest / "a.txt").read_text(encoding="utf-8") == "here"


def test_extract_avoids_double_folder(tmp_path):
    """压缩文件夹后再解压，不应出现 proj/proj/ 两层。"""
    src = tmp_path / "proj"
    src.mkdir()
    (src / "a.txt").write_text("one-layer", encoding="utf-8")
    archives = tmp_path / "archives"
    archives.mkdir()
    archive = core.compress_to_zip([src], output=archives / "proj.zip")

    dest = core.extract_archive(archive, output_dir=tmp_path / "out")
    assert dest == tmp_path / "out" / "proj"
    assert (dest / "a.txt").read_text(encoding="utf-8") == "one-layer"
    assert not (dest / "proj").exists()


def test_extract_member_opens_single_file(tmp_path):
    src = tmp_path / "readme.txt"
    src.write_text("open-me", encoding="utf-8")
    archive = core.compress_to_zip([src], output=tmp_path / "p.zip")
    out_dir = tmp_path / "one"
    path = core.extract_member(archive, "readme.txt", output_dir=out_dir)
    assert path.is_file()
    assert path.read_text(encoding="utf-8") == "open-me"


def test_extract_member_nested_path(tmp_path):
    root = tmp_path / "proj"
    root.mkdir()
    nested = root / "sub"
    nested.mkdir()
    (nested / "deep.txt").write_text("nested", encoding="utf-8")
    archive = core.compress_to_zip([root], output=tmp_path / "n.zip")
    out = core.extract_member(archive, "proj/sub/deep.txt", output_dir=tmp_path / "x")
    assert out.read_text(encoding="utf-8") == "nested"


def test_nested_archive_inside_zip(tmp_path):
    """外层 zip 内含压缩包时，可单独解出并用 is_archive 识别。"""
    inner_file = tmp_path / "hello.txt"
    inner_file.write_text("hello-inner", encoding="utf-8")
    inner_zip = core.compress_to_zip([inner_file], output=tmp_path / "inner.zip")
    outer_zip = core.compress_to_zip([inner_zip], output=tmp_path / "outer.zip")

    extracted = core.extract_member(
        outer_zip, "inner.zip", output_dir=tmp_path / "nest"
    )
    assert extracted.is_file()
    assert core.is_archive(extracted)
    members = core.list_archive(extracted)
    assert any(m.name.endswith("hello.txt") for m in members)
    assert (
        core.extract_member(
            extracted, "hello.txt", output_dir=tmp_path / "nest2"
        ).read_text(encoding="utf-8")
        == "hello-inner"
    )


def test_cleanup_temp_dirs(tmp_path):
    from jianya.gui import cleanup_temp_dirs

    d1 = tmp_path / "jianya-open-a"
    d2 = tmp_path / "jianya-open-b"
    d1.mkdir()
    d2.mkdir()
    (d1 / "f.txt").write_text("x", encoding="utf-8")
    dirs = [d1, d2]
    cleanup_temp_dirs(dirs)
    assert not d1.exists() and not d2.exists()
    assert dirs == []


def test_member_flag_marks_nested_archive():
    from jianya.gui import _member_flag

    nested = core.ArchiveMember(name="pack/inner.zip", size=10, compressed_size=8)
    assert "压缩包" in _member_flag(nested)
    enc = core.ArchiveMember(
        name="secret.zip", size=10, compressed_size=8, encrypted=True
    )
    assert "加密" in _member_flag(enc)
    assert "压缩包" in _member_flag(enc)
    plain = core.ArchiveMember(name="a.txt", size=1, compressed_size=1)
    assert _member_flag(plain) == ""


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

    src = tmp_path / "srcdata"
    src.mkdir()
    (src / "f.txt").write_text("tar-content", encoding="utf-8")
    archive = tmp_path / "data.tar.gz"
    with tarfile.open(archive, "w:gz") as tf:
        tf.add(src / "f.txt", arcname="f.txt")

    dest = core.extract_archive(archive)
    assert dest == tmp_path / "data"
    assert (dest / "f.txt").read_text(encoding="utf-8") == "tar-content"


def test_extract_gz_plain(tmp_path):
    import gzip

    archive = tmp_path / "single.txt.gz"
    with gzip.open(archive, "wb") as f:
        f.write("plain".encode("utf-8"))
    dest = core.extract_archive(archive)
    # single.txt.gz → 同名目录 single.txt/ 下再放 single.txt 文件
    assert dest == tmp_path / "single.txt"
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
