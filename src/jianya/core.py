"""压缩 / 解压核心逻辑。

设计目标：
- 压缩：统一输出为最常用的 ZIP 格式（不依赖任何第三方库）。
- 解压：尽量支持常见格式（zip / tar / tar.gz / tgz / tar.bz2 / tar.xz /
  gz / bz2 / xz），并在安装了可选依赖时额外支持 7z / rar。

所有耗时操作都支持一个 ``progress`` 回调，方便界面显示进度。
回调签名为 ``progress(done: int, total: int, name: str)``。
"""

from __future__ import annotations

import bz2
import gzip
import lzma
import os
import sys
import tarfile
import zipfile
from pathlib import Path
from typing import Callable, Iterable, Optional, Sequence

# 可选依赖：安装后自动启用对应格式的解压能力。
try:  # 7z 支持
    import py7zr  # type: ignore

    HAS_7Z = True
except Exception:  # pragma: no cover - 取决于运行环境
    HAS_7Z = False

try:  # rar 支持（需系统安装 unrar / bsdtar）
    import rarfile  # type: ignore

    HAS_RAR = True
except Exception:  # pragma: no cover - 取决于运行环境
    HAS_RAR = False


ProgressCallback = Callable[[int, int, str], None]


class ArchiveError(Exception):
    """压缩 / 解压过程中出现的可读错误。"""


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

# 解压支持的扩展名（用于识别与右键菜单判断）。
_TAR_SUFFIXES = (
    ".tar",
    ".tar.gz",
    ".tgz",
    ".tar.bz2",
    ".tbz2",
    ".tar.xz",
    ".txz",
)
_PLAIN_SUFFIXES = (".gz", ".bz2", ".xz")
_ZIP_SUFFIXES = (".zip",)
_OPTIONAL_SUFFIXES = (".7z", ".rar")

SUPPORTED_EXTRACT_SUFFIXES = (
    _ZIP_SUFFIXES + _TAR_SUFFIXES + _PLAIN_SUFFIXES + _OPTIONAL_SUFFIXES
)


def _lower_name(path: os.PathLike | str) -> str:
    return os.path.basename(str(path)).lower()


def is_archive(path: os.PathLike | str) -> bool:
    """根据扩展名判断是否为可解压的压缩包。"""
    name = _lower_name(path)
    return any(name.endswith(suffix) for suffix in SUPPORTED_EXTRACT_SUFFIXES)


def _unique_path(path: Path) -> Path:
    """若目标路径已存在，则在文件名后追加 (1)/(2)… 直到不冲突。"""
    if not path.exists():
        return path
    stem, suffix = path.stem, path.suffix
    parent = path.parent
    # 处理形如 .tar.gz 的复合后缀
    name_lower = path.name.lower()
    for compound in (".tar.gz", ".tar.bz2", ".tar.xz"):
        if name_lower.endswith(compound):
            stem = path.name[: -len(compound)]
            suffix = path.name[-len(compound):]
            break
    index = 1
    while True:
        candidate = parent / f"{stem} ({index}){suffix}"
        if not candidate.exists():
            return candidate
        index += 1


def _iter_files(paths: Sequence[Path]):
    """展开目录，产出 (绝对文件路径, 压缩包内相对路径)。

    多个输入时以它们的公共父目录为基准，尽量保留可读的目录结构。
    """
    paths = [p.resolve() for p in paths]
    for base in paths:
        if base.is_dir():
            root = base.parent
            for dirpath, _dirnames, filenames in os.walk(base):
                # 保留空目录
                if not filenames and not os.listdir(dirpath):
                    arc = os.path.relpath(dirpath, root)
                    yield None, arc + "/"
                for filename in filenames:
                    full = Path(dirpath) / filename
                    arc = os.path.relpath(full, root)
                    yield full, arc
        elif base.is_file():
            yield base, base.name
        else:
            raise ArchiveError(f"路径不存在：{base}")


def _count_files(paths: Sequence[Path]) -> int:
    total = 0
    for full, _arc in _iter_files(paths):
        if full is not None:
            total += 1
    return total


def default_zip_output(paths: Sequence[Path]) -> Path:
    """根据输入推断默认的 zip 输出路径。"""
    paths = [Path(p) for p in paths]
    if len(paths) == 1:
        base = paths[0].resolve()
        if base.is_dir():
            return base.parent / f"{base.name}.zip"
        return base.with_suffix(base.suffix + ".zip") if base.suffix == "" else base.with_name(base.stem + ".zip")
    parent = paths[0].resolve().parent
    return parent / "打包.zip"


# ---------------------------------------------------------------------------
# 压缩
# ---------------------------------------------------------------------------

def compress_to_zip(
    inputs: Iterable[os.PathLike | str],
    output: Optional[os.PathLike | str] = None,
    progress: Optional[ProgressCallback] = None,
    compresslevel: int = 6,
) -> Path:
    """将若干文件 / 目录压缩为一个 ZIP。

    :param inputs: 要压缩的文件或目录路径集合。
    :param output: 输出 zip 路径；缺省时自动推断。
    :param progress: 进度回调 ``(done, total, name)``。
    :param compresslevel: 压缩级别 0-9。
    :returns: 实际写出的 zip 路径。
    """
    paths = [Path(p) for p in inputs]
    if not paths:
        raise ArchiveError("没有选择要压缩的内容。")
    for p in paths:
        if not p.exists():
            raise ArchiveError(f"路径不存在：{p}")

    out = Path(output) if output else default_zip_output(paths)
    if out.suffix.lower() != ".zip":
        out = out.with_suffix(".zip")
    out = _unique_path(out)
    out.parent.mkdir(parents=True, exist_ok=True)

    total = _count_files(paths) or 1
    done = 0

    try:
        with zipfile.ZipFile(
            out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=compresslevel
        ) as zf:
            for full, arc in _iter_files(paths):
                if full is None:
                    # 空目录
                    zf.writestr(arc, "")
                    continue
                zf.write(full, arc)
                done += 1
                if progress:
                    progress(done, total, arc)
    except Exception as exc:  # 出错时清理半成品
        if out.exists():
            try:
                out.unlink()
            except OSError:
                pass
        raise ArchiveError(f"压缩失败：{exc}") from exc

    return out


# ---------------------------------------------------------------------------
# 解压
# ---------------------------------------------------------------------------

def _safe_join(base: Path, *paths: str) -> Path:
    """防止压缩包中的路径穿越（Zip Slip）。"""
    target = base.joinpath(*paths).resolve()
    base_resolved = base.resolve()
    if base_resolved != target and base_resolved not in target.parents:
        raise ArchiveError(f"压缩包包含非法路径，已阻止：{'/'.join(paths)}")
    return target


def _extract_dir_for(archive: Path, output_dir: Optional[os.PathLike | str]) -> Path:
    if output_dir:
        return Path(output_dir)
    name = archive.name
    lower = name.lower()
    for compound in (".tar.gz", ".tar.bz2", ".tar.xz"):
        if lower.endswith(compound):
            stem = name[: -len(compound)]
            break
    else:
        stem = archive.stem
    return archive.parent / stem


def _extract_zip(archive: Path, dest: Path, progress: Optional[ProgressCallback]):
    with zipfile.ZipFile(archive) as zf:
        members = zf.infolist()
        total = len(members) or 1
        for i, member in enumerate(members, start=1):
            _safe_join(dest, member.filename)
            zf.extract(member, dest)
            if progress:
                progress(i, total, member.filename)


def _extract_tar(archive: Path, dest: Path, progress: Optional[ProgressCallback]):
    # Python 3.12+ 支持 filter='data'，可拒绝危险的 tar 条目并抑制弃用警告。
    supports_filter = sys.version_info >= (3, 12)
    with tarfile.open(archive) as tf:
        members = tf.getmembers()
        total = len(members) or 1
        for i, member in enumerate(members, start=1):
            _safe_join(dest, member.name)
            if supports_filter:
                tf.extract(member, dest, filter="data")
            else:
                tf.extract(member, dest)
            if progress:
                progress(i, total, member.name)


def _extract_plain(archive: Path, dest: Path, progress: Optional[ProgressCallback]):
    """解压单文件压缩（.gz / .bz2 / .xz）。"""
    lower = archive.name.lower()
    if lower.endswith(".gz"):
        opener, strip = gzip.open, ".gz"
    elif lower.endswith(".bz2"):
        opener, strip = bz2.open, ".bz2"
    else:
        opener, strip = lzma.open, ".xz"

    out_name = archive.name[: -len(strip)] or archive.stem
    dest.mkdir(parents=True, exist_ok=True)
    out_path = _unique_path(dest / out_name)
    if progress:
        progress(0, 1, out_name)
    with opener(archive, "rb") as src, open(out_path, "wb") as dst:
        while True:
            chunk = src.read(1024 * 1024)
            if not chunk:
                break
            dst.write(chunk)
    if progress:
        progress(1, 1, out_name)


def _extract_7z(archive: Path, dest: Path, progress: Optional[ProgressCallback]):
    if not HAS_7Z:
        raise ArchiveError("解压 7z 需要安装 py7zr（pip install py7zr）。")
    with py7zr.SevenZipFile(archive, mode="r") as zf:  # type: ignore
        names = zf.getnames()
        total = len(names) or 1
        if progress:
            progress(0, total, archive.name)
        zf.extractall(path=dest)
        if progress:
            progress(total, total, archive.name)


def _extract_rar(archive: Path, dest: Path, progress: Optional[ProgressCallback]):
    if not HAS_RAR:
        raise ArchiveError("解压 rar 需要安装 rarfile 及系统 unrar 工具。")
    with rarfile.RarFile(archive) as rf:  # type: ignore
        members = rf.infolist()
        total = len(members) or 1
        for i, member in enumerate(members, start=1):
            _safe_join(dest, member.filename)
            rf.extract(member, dest)
            if progress:
                progress(i, total, member.filename)


def extract_archive(
    archive: os.PathLike | str,
    output_dir: Optional[os.PathLike | str] = None,
    progress: Optional[ProgressCallback] = None,
) -> Path:
    """将压缩包解压到目录。

    :param archive: 压缩包路径。
    :param output_dir: 输出目录；缺省时使用与压缩包同名的新目录。
    :param progress: 进度回调 ``(done, total, name)``。
    :returns: 实际解压到的目录。
    """
    archive_path = Path(archive)
    if not archive_path.is_file():
        raise ArchiveError(f"文件不存在：{archive_path}")

    dest = _extract_dir_for(archive_path, output_dir)
    lower = archive_path.name.lower()

    try:
        if lower.endswith(_ZIP_SUFFIXES):
            dest = _unique_path(dest)
            dest.mkdir(parents=True, exist_ok=True)
            _extract_zip(archive_path, dest, progress)
        elif lower.endswith(_TAR_SUFFIXES) or tarfile.is_tarfile(archive_path):
            dest = _unique_path(dest)
            dest.mkdir(parents=True, exist_ok=True)
            _extract_tar(archive_path, dest, progress)
        elif lower.endswith(".7z"):
            dest = _unique_path(dest)
            dest.mkdir(parents=True, exist_ok=True)
            _extract_7z(archive_path, dest, progress)
        elif lower.endswith(".rar"):
            dest = _unique_path(dest)
            dest.mkdir(parents=True, exist_ok=True)
            _extract_rar(archive_path, dest, progress)
        elif lower.endswith(_PLAIN_SUFFIXES):
            dest.mkdir(parents=True, exist_ok=True)
            _extract_plain(archive_path, dest, progress)
        else:
            raise ArchiveError(f"不支持的压缩格式：{archive_path.name}")
    except ArchiveError:
        raise
    except Exception as exc:
        raise ArchiveError(f"解压失败：{exc}") from exc

    return dest
