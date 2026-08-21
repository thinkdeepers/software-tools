"""压缩 / 解压核心逻辑。

设计目标：
- 压缩：统一输出为最常用的 ZIP 格式；可选 AES 加密（需 pyzipper）。
- 解压：支持常见格式（zip / tar / tar.gz / tgz / tar.bz2 / tar.xz /
  gz / bz2 / xz），并在安装了可选依赖时额外支持 7z / rar。
- 预览：可列出压缩包内文件，供界面双击打开时浏览；也可解压单个文件以打开。
- 密码：支持加密压缩，以及解压加密压缩包时传入密码。
- 解压目标：始终新建与压缩包同名的文件夹，把内容放进去；已存在则自动加 (1)/(2)…
  若包内唯一根目录与压缩包同名，则剥掉该层，避免 ``proj/proj``。

所有耗时操作都支持一个 ``progress`` 回调，方便界面显示进度。
回调签名为 ``progress(done: int, total: int, name: str)``。
"""

from __future__ import annotations

import bz2
import gzip
import lzma
import os
import stat
import sys
import tarfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, List, Optional, Sequence, Tuple

# 可选依赖：安装后自动启用对应格式的解压 / 加密能力。
try:  # 7z 支持
    import py7zr  # type: ignore

    HAS_7Z = True
except Exception:  # pragma: no cover - 取决于运行环境
    HAS_7Z = False

try:  # rar 支持（需系统或捆绑的 unrar / 7z）
    import rarfile  # type: ignore

    HAS_RAR = True
except Exception:  # pragma: no cover - 取决于运行环境
    HAS_RAR = False

try:  # AES 加密 ZIP
    import pyzipper  # type: ignore

    HAS_PYZIPPER = True
except Exception:  # pragma: no cover - 取决于运行环境
    HAS_PYZIPPER = False

try:  # ZIP Deflate64（WinRAR / 资源管理器大文件常用，标准库不支持）
    import zipfile_deflate64  # type: ignore  # noqa: F401

    HAS_DEFLATE64 = True
except Exception:  # pragma: no cover - 取决于运行环境
    HAS_DEFLATE64 = False


ProgressCallback = Callable[[int, int, str], None]


class ArchiveError(Exception):
    """压缩 / 解压过程中出现的可读错误。"""


class PasswordRequiredError(ArchiveError):
    """压缩包已加密，需要密码（或密码不正确）。"""


@dataclass(frozen=True)
class ArchiveMember:
    """压缩包内的一个条目，供预览界面使用。"""

    name: str
    size: int = 0
    compressed_size: int = 0
    is_dir: bool = False
    encrypted: bool = False


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

_RAR_TOOL_CONFIGURED = False


def _lower_name(path: os.PathLike | str) -> str:
    return os.path.basename(str(path)).lower()


def is_archive(path: os.PathLike | str) -> bool:
    """根据扩展名判断是否为可解压的压缩包。"""
    name = _lower_name(path)
    return any(name.endswith(suffix) for suffix in SUPPORTED_EXTRACT_SUFFIXES)


def _vendor_candidates() -> List[Path]:
    """查找随程序分发的第三方工具目录。

    优先顺序：安装目录（与 简压.exe 同级）→ PyInstaller 临时目录 → 源码 vendor。
    """
    candidates: List[Path] = []
    if getattr(sys, "frozen", False):
        exe_dir = Path(sys.executable).resolve().parent
        # 安装程序会把 UnRAR.exe 放到与主程序同级，优先使用，避免误用临时目录中的旧文件。
        candidates.append(exe_dir)
        candidates.append(exe_dir / "vendor")
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        candidates.append(Path(meipass))
        candidates.append(Path(meipass) / "vendor")
    here = Path(__file__).resolve().parent
    project_root = here.parent.parent
    candidates.append(project_root / "vendor")
    candidates.append(project_root / "assets")
    return candidates


def fix_archive_filename(name: str) -> str:
    """修正压缩包内文件名的中文乱码（常见于本地编码 ZIP）。"""
    if not name:
        return name
    # 已含常见汉字则认为解码正确。
    if any("\u4e00" <= ch <= "\u9fff" for ch in name):
        return name

    def _try(encode_as: str, decode_as: str) -> Optional[str]:
        try:
            fixed = name.encode(encode_as).decode(decode_as)
        except (UnicodeEncodeError, UnicodeDecodeError, LookupError):
            return None
        if fixed == name:
            return None
        # 结果应更像正常路径：包含汉字或减少替换字符。
        if any("\u4e00" <= ch <= "\u9fff" for ch in fixed):
            return fixed
        return None

    for enc, dec in (
        ("cp437", "gbk"),
        ("cp437", "gb18030"),
        ("latin-1", "gbk"),
        ("latin-1", "gb18030"),
    ):
        fixed = _try(enc, dec)
        if fixed:
            return fixed
    return name


def _is_executable_tool(path: Path) -> bool:
    """判断路径是否为当前平台可执行的辅助工具。"""
    if not path.is_file():
        return False
    name = path.name.lower()
    if os.name == "nt":
        return name.endswith(".exe") or os.access(path, os.X_OK)
    # POSIX：不要选中 Windows PE（如捆绑的 UnRAR.exe）。
    if name.endswith(".exe"):
        return False
    return os.access(path, os.X_OK)


def _find_helper_tool(*names: str) -> Optional[str]:
    """在捆绑目录与 PATH 中查找当前平台可用的辅助解压工具。"""
    from shutil import which

    # Windows 安装版：优先使用捆绑的 UnRAR.exe。
    # 其它平台：优先系统 PATH 中的原生工具，再回退到 vendor。
    search_order: List[Path] = []
    if os.name == "nt":
        for directory in _vendor_candidates():
            for name in names:
                search_order.append(directory / name)
        for name in names:
            found = which(name)
            if found:
                search_order.append(Path(found))
    else:
        for name in names:
            found = which(name)
            if found:
                search_order.append(Path(found))
        for directory in _vendor_candidates():
            for name in names:
                search_order.append(directory / name)

    seen = set()
    for candidate in search_order:
        key = str(candidate)
        if key in seen:
            continue
        seen.add(key)
        if _is_executable_tool(candidate):
            return str(candidate)
    return None


def _configure_rar_tool() -> None:
    """配置 rarfile 使用捆绑或系统中的 UnRAR / 7z。"""
    global _RAR_TOOL_CONFIGURED
    if _RAR_TOOL_CONFIGURED or not HAS_RAR:
        return
    _RAR_TOOL_CONFIGURED = True

    if os.name == "nt":
        unrar_names = ("UnRAR.exe", "unrar.exe", "unrarw64.exe", "unrarw32.exe", "unrar")
        seven_names = ("7z.exe", "7za.exe", "7zz", "7z")
    else:
        unrar_names = ("unrar", "UnRAR")
        seven_names = ("7z", "7zz", "7za")

    unrar = _find_helper_tool(*unrar_names)
    if unrar:
        rarfile.UNRAR_TOOL = unrar  # type: ignore[attr-defined]

    seven = _find_helper_tool(*seven_names)
    if seven:
        rarfile.SEVENZIP_TOOL = seven  # type: ignore[attr-defined]

    try:
        rarfile.tool_setup(force=True)  # type: ignore[attr-defined]
    except Exception:
        # 工具可能仍不可用；真正解压时再给出明确错误。
        pass


def _ensure_rar_ready() -> None:
    if not HAS_RAR:
        raise ArchiveError(
            "解压 rar 需要安装 rarfile（pip install rarfile），"
            "并提供 UnRAR 工具（安装版已捆绑）。"
        )
    _configure_rar_tool()
    try:
        rarfile.tool_setup()  # type: ignore[attr-defined]
    except Exception as exc:  # pragma: no cover - 取决于运行环境
        raise ArchiveError(
            "未找到可用的 UnRAR / 7z 工具，无法解压 rar。"
            "请重新安装简压，或将 UnRAR 加入系统 PATH。"
        ) from exc


def _unique_path(path: Path) -> Path:
    """若目标路径已存在，则在文件名后追加 (1)/(2)… 直到不冲突。

    目录与无后缀文件同样适用：``demo`` → ``demo (1)``。
    """
    if not path.exists():
        return path
    stem, suffix = path.stem, path.suffix
    parent = path.parent
    # 处理形如 .tar.gz 的复合后缀
    name_lower = path.name.lower()
    for compound in (".tar.gz", ".tar.bz2", ".tar.xz"):
        if name_lower.endswith(compound):
            stem = path.name[: -len(compound)]
            suffix = path.name[-len(compound) :]
            break
    # 纯目录名（无后缀）时 stem 即 name
    if suffix == "" and path.name != stem:
        stem = path.name
    index = 1
    while True:
        candidate = parent / f"{stem} ({index}){suffix}"
        if not candidate.exists():
            return candidate
        index += 1


def _archive_stem(archive: Path) -> str:
    """压缩包主文件名：demo.zip → demo，data.tar.gz → data。"""
    name = archive.name
    lower = name.lower()
    for compound in (".tar.gz", ".tar.bz2", ".tar.xz"):
        if lower.endswith(compound):
            stem = name[: -len(compound)]
            return stem or archive.stem
    stem = archive.stem
    return stem or "archive"


def _top_level_names(member_names: Sequence[str]) -> List[str]:
    """从压缩包条目名中提取顶层文件/目录名（去重、保序）。"""
    seen = set()
    tops: List[str] = []
    for raw in member_names:
        name = _normalize_member_name(fix_archive_filename(raw))
        if not name:
            continue
        top = name.split("/", 1)[0]
        if not top or top in seen or top in (".", ".."):
            continue
        seen.add(top)
        tops.append(top)
    return tops


def _strip_member_prefix(name: str, prefix: Optional[str]) -> Optional[str]:
    """去掉唯一根目录前缀；若条目就是根目录本身则返回 None（跳过）。"""
    norm = _normalize_member_name(name)
    if not prefix:
        return norm
    if norm == prefix:
        return None
    head = prefix + "/"
    if norm.startswith(head):
        return norm[len(head) :]
    return norm


_WINDOWS_RESERVED = {
    "con",
    "prn",
    "aux",
    "nul",
    *(f"com{i}" for i in range(1, 10)),
    *(f"lpt{i}" for i in range(1, 10)),
}
_WINDOWS_ILLEGAL = set('<>:"|?*') | {chr(i) for i in range(32)}


def _sanitize_path_component(part: str) -> str:
    """清洗单层文件/目录名，避免 Windows 非法字符与保留名导致解压失败。"""
    cleaned = part.replace("\x00", "_")
    if os.name == "nt":
        cleaned = "".join("_" if ch in _WINDOWS_ILLEGAL else ch for ch in cleaned)
        cleaned = cleaned.rstrip(" .")
        stem = cleaned.split(".", 1)[0]
        if stem.lower() in _WINDOWS_RESERVED:
            cleaned = "_" + cleaned
    return cleaned or "_"


def _relpath_for_extract(name: str, strip_prefix: Optional[str]) -> Optional[str]:
    """得到可落盘的相对路径；非法 / 穿越条目返回 None（跳过，不中断整包）。"""
    rel = _strip_member_prefix(name, strip_prefix)
    if not rel:
        return None
    rel = rel.replace("\\", "/").strip("/")
    if not rel or rel in (".", ".."):
        return None
    parts: List[str] = []
    for part in rel.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            return None
        parts.append(_sanitize_path_component(part))
    if not parts:
        return None
    return "/".join(parts)


def _output_path_inside(dest: Path, rel: str) -> Optional[Path]:
    """把相对路径接到 dest 下；穿越则返回 None。"""
    dest.mkdir(parents=True, exist_ok=True)
    dest_resolved = dest.resolve()
    target = dest.joinpath(*rel.split("/"))
    try:
        resolved = target.resolve()
    except OSError:
        return None
    if dest_resolved != resolved and dest_resolved not in resolved.parents:
        return None
    return target


def _ensure_directory(path: Path) -> None:
    """确保 path 是目录；若被同名文件占用则把文件让开。"""
    if path.exists() and path.is_file():
        path.rename(_unique_path(path.parent / f"{path.name}.file"))
    path.mkdir(parents=True, exist_ok=True)


def _prepare_file_path(path: Path) -> Path:
    """准备写出文件：创建父目录；若路径已是目录则改用唯一文件名。"""
    _ensure_directory(path.parent)
    if path.exists() and path.is_dir():
        return _unique_path(path)
    return path


def _is_zip_directory(info: zipfile.ZipInfo) -> bool:
    name = str(info.filename).replace("\\", "/")
    if name.endswith("/"):
        return True
    is_dir = getattr(info, "is_dir", None)
    if callable(is_dir):
        try:
            if is_dir():
                return True
        except Exception:
            pass
    if int(getattr(info, "file_size", 0) or 0) > 0:
        return False
    ext = int(getattr(info, "external_attr", 0) or 0)
    if ext & 0x10:  # DOS 目录位
        return True
    unix = ext >> 16
    return bool(unix) and stat.S_ISDIR(unix)


def _plan_extract_destination(
    archive: Path,
    output_dir: Optional[os.PathLike | str],
    member_names: Sequence[str],
) -> Tuple[Path, Optional[str]]:
    """始终在父目录下新建与压缩包同名的文件夹。

    - ``demo.zip`` → ``父目录/demo/``
    - 目标已存在时自动 ``demo (1)``、``demo (2)``…
    - 仅当包内唯一根目录与压缩包同名时剥掉该层，避免 ``proj/proj``
    """
    base = Path(output_dir) if output_dir else archive.parent
    stem = _archive_stem(archive)
    dest = _unique_path(base / stem)
    strip_prefix: Optional[str] = None
    tops = _top_level_names(member_names)
    if len(tops) == 1:
        only = tops[0]
        is_wrapper_dir = any(
            _normalize_member_name(fix_archive_filename(n)).startswith(only + "/")
            for n in member_names
        )
        if is_wrapper_dir and only == stem:
            strip_prefix = only
    return dest, strip_prefix


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
        return (
            base.with_suffix(base.suffix + ".zip")
            if base.suffix == ""
            else base.with_name(base.stem + ".zip")
        )
    parent = paths[0].resolve().parent
    return parent / "打包.zip"


def _pwd_bytes(password: Optional[str]) -> Optional[bytes]:
    if password is None:
        return None
    if isinstance(password, bytes):
        return password
    return password.encode("utf-8")


def _is_password_message(message: str) -> bool:
    text = message.lower()
    keywords = (
        "password",
        "passwd",
        "encrypted",
        "encrypt",
        "密码",
        "加密",
        "bad password",
        "wrong password",
        "required password",
    )
    return any(k in text for k in keywords)


def _raise_password_error(exc: BaseException) -> None:
    """若异常明显与密码相关，则转换为 PasswordRequiredError。"""
    msg = str(exc) or exc.__class__.__name__
    if _is_password_message(msg):
        raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc

    if HAS_RAR:
        for name in ("PasswordRequired", "RarWrongPassword"):
            cls = getattr(rarfile, name, None)
            if cls is not None and isinstance(exc, cls):
                raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc

    if HAS_7Z:
        cls = getattr(py7zr, "PasswordRequired", None)
        if cls is not None and isinstance(exc, cls):
            raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc


# ---------------------------------------------------------------------------
# 压缩
# ---------------------------------------------------------------------------

def compress_to_zip(
    inputs: Iterable[os.PathLike | str],
    output: Optional[os.PathLike | str] = None,
    progress: Optional[ProgressCallback] = None,
    compresslevel: int = 6,
    password: Optional[str] = None,
) -> Path:
    """将若干文件 / 目录压缩为一个 ZIP。

    :param inputs: 要压缩的文件或目录路径集合。
    :param output: 输出 zip 路径；缺省时自动推断。
    :param progress: 进度回调 ``(done, total, name)``。
    :param compresslevel: 压缩级别 0-9。
    :param password: 若提供，则使用 AES-256 加密（需要 pyzipper）。
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
    pwd = (password or "").strip() or None

    try:
        if pwd:
            if not HAS_PYZIPPER:
                raise ArchiveError("加密压缩需要安装 pyzipper（pip install pyzipper）。")
            with pyzipper.AESZipFile(  # type: ignore[attr-defined]
                out,
                "w",
                compression=pyzipper.ZIP_DEFLATED,  # type: ignore[attr-defined]
                encryption=pyzipper.WZ_AES,  # type: ignore[attr-defined]
                compresslevel=compresslevel,
            ) as zf:
                zf.setpassword(_pwd_bytes(pwd))
                for full, arc in _iter_files(paths):
                    if full is None:
                        zf.writestr(arc, "")
                        continue
                    zf.write(full, arc)
                    done += 1
                    if progress:
                        progress(done, total, arc)
        else:
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
    except ArchiveError:
        if out.exists():
            try:
                out.unlink()
            except OSError:
                pass
        raise
    except Exception as exc:  # 出错时清理半成品
        if out.exists():
            try:
                out.unlink()
            except OSError:
                pass
        raise ArchiveError(f"压缩失败：{exc}") from exc

    return out


# ---------------------------------------------------------------------------
# 列表 / 预览
# ---------------------------------------------------------------------------

def _zip_cjk_score(names: List[str]) -> int:
    return sum(1 for n in names for ch in n if "\u4e00" <= ch <= "\u9fff")


def _open_best_zip(archive: Path, password: Optional[str]):
    """打开 ZIP，自动选择中文文件名更正确的编码。"""
    pwd = _pwd_bytes(password)
    candidates = []
    # 默认编码 + 国内常见 GBK（Python 3.11+）
    attempts = [{}]
    if sys.version_info >= (3, 11):
        attempts.append({"metadata_encoding": "gbk"})
        attempts.append({"metadata_encoding": "utf-8"})

    last_exc: Optional[BaseException] = None
    for kwargs in attempts:
        zf = None
        try:
            if HAS_PYZIPPER:
                try:
                    zf = pyzipper.AESZipFile(archive, **kwargs)  # type: ignore[attr-defined]
                except TypeError:
                    if kwargs:
                        continue
                    zf = pyzipper.AESZipFile(archive)  # type: ignore[attr-defined]
            else:
                zf = zipfile.ZipFile(archive, **kwargs)
            if pwd:
                zf.setpassword(pwd)
            infos = zf.infolist()
            names = [fix_archive_filename(i.filename) for i in infos]
            score = _zip_cjk_score(names)
            # 也给“无需修复就已含汉字”的加分
            score += _zip_cjk_score([i.filename for i in infos])
            candidates.append((score, zf, infos))
        except Exception as exc:
            last_exc = exc
            if zf is not None:
                try:
                    zf.close()
                except Exception:
                    pass

    if not candidates:
        if last_exc is not None:
            _raise_password_error(last_exc)
            raise last_exc
        raise ArchiveError("无法打开 zip 压缩包。")

    candidates.sort(key=lambda item: item[0], reverse=True)
    best_score, best_zf, best_infos = candidates[0]
    # 关闭落选的句柄
    for score, zf, _infos in candidates[1:]:
        try:
            zf.close()
        except Exception:
            pass
    return best_zf, best_infos


def _list_zip(archive: Path, password: Optional[str]) -> List[ArchiveMember]:
    members: List[ArchiveMember] = []
    zf, infos = _open_best_zip(archive, password)
    try:
        for info in infos:
            name = fix_archive_filename(info.filename)
            is_dir = name.endswith("/")
            encrypted = bool(getattr(info, "flag_bits", 0) & 0x1)
            members.append(
                ArchiveMember(
                    name=name,
                    size=0 if is_dir else int(info.file_size),
                    compressed_size=0 if is_dir else int(info.compress_size),
                    is_dir=is_dir,
                    encrypted=encrypted,
                )
            )
    finally:
        zf.close()
    return members


def _list_tar(archive: Path) -> List[ArchiveMember]:
    members: List[ArchiveMember] = []
    with tarfile.open(archive) as tf:
        for info in tf.getmembers():
            members.append(
                ArchiveMember(
                    name=info.name + ("/" if info.isdir() and not info.name.endswith("/") else ""),
                    size=0 if info.isdir() else int(info.size),
                    compressed_size=0 if info.isdir() else int(info.size),
                    is_dir=info.isdir(),
                    encrypted=False,
                )
            )
    return members


def _list_plain(archive: Path) -> List[ArchiveMember]:
    lower = archive.name.lower()
    if lower.endswith(".gz"):
        strip = ".gz"
    elif lower.endswith(".bz2"):
        strip = ".bz2"
    else:
        strip = ".xz"
    out_name = archive.name[: -len(strip)] or archive.stem
    return [
        ArchiveMember(
            name=out_name,
            size=archive.stat().st_size,
            compressed_size=archive.stat().st_size,
            is_dir=False,
            encrypted=False,
        )
    ]


def _list_7z(archive: Path, password: Optional[str]) -> List[ArchiveMember]:
    if not HAS_7Z:
        raise ArchiveError("解压 7z 需要安装 py7zr（pip install py7zr）。")
    members: List[ArchiveMember] = []
    try:
        with py7zr.SevenZipFile(archive, mode="r", password=password) as zf:  # type: ignore
            if password is None and hasattr(zf, "needs_password") and zf.needs_password():
                raise PasswordRequiredError("压缩包已加密，请输入密码。")
            for info in zf.list():
                name = getattr(info, "filename", None) or getattr(info, "file_name", "")
                is_dir = bool(getattr(info, "is_directory", False))
                if not is_dir and str(name).endswith("/"):
                    is_dir = True
                uncompressed = int(getattr(info, "uncompressed", 0) or 0)
                compressed = int(getattr(info, "compressed", 0) or 0)
                encrypted = bool(
                    getattr(info, "encrypted", False)
                    or (hasattr(zf, "needs_password") and zf.needs_password())
                )
                members.append(
                    ArchiveMember(
                        name=str(name),
                        size=0 if is_dir else uncompressed,
                        compressed_size=0 if is_dir else compressed,
                        is_dir=is_dir,
                        encrypted=encrypted,
                    )
                )
    except PasswordRequiredError:
        raise
    except Exception as exc:
        _raise_password_error(exc)
        if _is_password_message(str(exc)):
            raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc
        raise
    return members


def _open_rar(archive: Path, password: Optional[str]):
    """打开 rar，并尽量兼容中文文件名编码。"""
    _ensure_rar_ready()
    # RAR5 多为 UTF-8；旧版中文 rar 常见 GBK/ANSI。
    charsets: Tuple[Optional[str], ...] = (None, "utf-8", "gbk", "gb18030")
    last_exc: Optional[BaseException] = None
    for charset in charsets:
        rf = None
        try:
            kwargs = {}
            if charset:
                kwargs["charset"] = charset
            rf = rarfile.RarFile(archive, **kwargs)  # type: ignore
            if password:
                rf.setpassword(password)
            # 触发一次列表以验证编码是否可读
            _ = rf.infolist()
            return rf
        except PasswordRequiredError:
            if rf is not None:
                try:
                    rf.close()
                except Exception:
                    pass
            raise
        except Exception as exc:
            last_exc = exc
            if rf is not None:
                try:
                    rf.close()
                except Exception:
                    pass
            continue
    if last_exc is not None:
        _raise_password_error(last_exc)
        raise ArchiveError(f"读取 rar 失败：{last_exc}") from last_exc
    raise ArchiveError("读取 rar 失败。")


def _list_rar(archive: Path, password: Optional[str]) -> List[ArchiveMember]:
    members: List[ArchiveMember] = []
    try:
        with _open_rar(archive, password) as rf:
            archive_needs_pw = bool(getattr(rf, "needs_password", lambda: False)())
            if archive_needs_pw and not password:
                raise PasswordRequiredError("压缩包已加密，请输入密码。")
            infos = rf.infolist()
            if archive_needs_pw and not infos:
                raise PasswordRequiredError("压缩包已加密，请输入正确密码。")
            for info in infos:
                name = fix_archive_filename(info.filename)
                is_dir = info.is_dir() if hasattr(info, "is_dir") else name.endswith("/")
                needs_pw = archive_needs_pw
                if hasattr(info, "needs_password"):
                    try:
                        needs_pw = needs_pw or bool(info.needs_password())
                    except Exception:
                        needs_pw = needs_pw or bool(getattr(info, "flag_bits", 0) & 0x4)
                members.append(
                    ArchiveMember(
                        name=name,
                        size=0 if is_dir else int(info.file_size),
                        compressed_size=0 if is_dir else int(getattr(info, "compress_size", 0) or 0),
                        is_dir=is_dir,
                        encrypted=needs_pw,
                    )
                )
    except PasswordRequiredError:
        raise
    except ArchiveError:
        raise
    except Exception as exc:
        _raise_password_error(exc)
        if _is_password_message(str(exc)):
            raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc
        raise ArchiveError(f"读取 rar 失败：{exc}") from exc
    return members


def list_archive(
    archive: os.PathLike | str,
    password: Optional[str] = None,
) -> List[ArchiveMember]:
    """列出压缩包内的文件条目（用于预览）。"""
    archive_path = Path(archive)
    if not archive_path.is_file():
        raise ArchiveError(f"文件不存在：{archive_path}")

    lower = archive_path.name.lower()
    try:
        if lower.endswith(_ZIP_SUFFIXES):
            return _list_zip(archive_path, password)
        if lower.endswith(_TAR_SUFFIXES) or tarfile.is_tarfile(archive_path):
            return _list_tar(archive_path)
        if lower.endswith(".7z"):
            return _list_7z(archive_path, password)
        if lower.endswith(".rar"):
            return _list_rar(archive_path, password)
        if lower.endswith(_PLAIN_SUFFIXES):
            return _list_plain(archive_path)
        raise ArchiveError(f"不支持的压缩格式：{archive_path.name}")
    except (ArchiveError, PasswordRequiredError):
        raise
    except Exception as exc:
        _raise_password_error(exc)
        raise ArchiveError(f"读取压缩包失败：{exc}") from exc


def archive_is_encrypted(archive: os.PathLike | str) -> bool:
    """尽力判断压缩包是否加密（不抛出密码错误时返回 False）。"""
    try:
        members = list_archive(archive)
    except PasswordRequiredError:
        return True
    except ArchiveError:
        return False
    return any(m.encrypted for m in members)


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


def _normalize_member_name(name: str) -> str:
    return name.replace("\\", "/").strip("/")


def _extract_zip(
    archive: Path,
    dest: Path,
    progress: Optional[ProgressCallback],
    password: Optional[str],
    strip_prefix: Optional[str] = None,
):
    zf, members = _open_best_zip(archive, password)
    extracted = 0
    try:
        total = len(members) or 1
        for i, member in enumerate(members, start=1):
            fixed = fix_archive_filename(member.filename).replace("\\", "/")
            rel = _relpath_for_extract(fixed, strip_prefix)
            if rel is None:
                if progress:
                    progress(i, total, fixed)
                continue
            out = _output_path_inside(dest, rel)
            if out is None:
                if progress:
                    progress(i, total, rel)
                continue
            try:
                if _is_zip_directory(member):
                    _ensure_directory(out)
                else:
                    out = _prepare_file_path(out)
                    data = zf.read(member)
                    out.write_bytes(data)
                extracted += 1
            except PasswordRequiredError:
                raise
            except Exception as exc:
                _raise_password_error(exc)
                if _is_password_message(str(exc)):
                    raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc
                # 单个条目失败不中断整包（非法名、不支持的压缩算法等）
                if progress:
                    progress(i, total, rel)
                continue
            if progress:
                progress(i, total, rel)
    finally:
        zf.close()
    if extracted == 0 and members:
        raise ArchiveError("解压失败：压缩包中没有可写出的有效文件。")


def _extract_tar(
    archive: Path,
    dest: Path,
    progress: Optional[ProgressCallback],
    strip_prefix: Optional[str] = None,
):
    extracted = 0
    with tarfile.open(archive) as tf:
        members = tf.getmembers()
        total = len(members) or 1
        for i, member in enumerate(members, start=1):
            rel = _relpath_for_extract(member.name, strip_prefix)
            if rel is None:
                if progress:
                    progress(i, total, member.name)
                continue
            out = _output_path_inside(dest, rel)
            if out is None:
                if progress:
                    progress(i, total, rel)
                continue
            try:
                if member.isdir():
                    _ensure_directory(out)
                    extracted += 1
                elif member.isfile() or member.isreg():
                    src = tf.extractfile(member)
                    if src is None:
                        if progress:
                            progress(i, total, rel)
                        continue
                    out = _prepare_file_path(out)
                    with src, open(out, "wb") as dst:
                        while True:
                            chunk = src.read(1024 * 1024)
                            if not chunk:
                                break
                            dst.write(chunk)
                    extracted += 1
                else:
                    # 符号链接 / 设备文件等跳过，避免整包失败
                    if progress:
                        progress(i, total, rel)
                    continue
            except PasswordRequiredError:
                raise
            except Exception:
                if progress:
                    progress(i, total, rel)
                continue
            if progress:
                progress(i, total, rel)
    if extracted == 0 and members:
        raise ArchiveError("解压失败：压缩包中没有可写出的有效文件。")


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


def _hoist_stripped_root(dest: Path, strip_prefix: str) -> None:
    """若解压后出现 dest/prefix/…，把内容提升到 dest/，去掉多余的一层。"""
    nested = dest / strip_prefix
    if not nested.exists():
        return
    for child in list(nested.iterdir()):
        target = dest / child.name
        if target.exists():
            target = _unique_path(target)
        try:
            child.rename(target)
        except OSError:
            # 跨设备等失败时尝试拷贝
            import shutil

            if child.is_dir():
                shutil.copytree(child, target, dirs_exist_ok=True)
                shutil.rmtree(child, ignore_errors=True)
            else:
                shutil.copy2(child, target)
                try:
                    child.unlink()
                except OSError:
                    pass
    try:
        nested.rmdir()
    except OSError:
        import shutil

        shutil.rmtree(nested, ignore_errors=True)


def _extract_7z(
    archive: Path,
    dest: Path,
    progress: Optional[ProgressCallback],
    password: Optional[str],
    strip_prefix: Optional[str] = None,
):
    if not HAS_7Z:
        raise ArchiveError("解压 7z 需要安装 py7zr（pip install py7zr）。")
    try:
        with py7zr.SevenZipFile(archive, mode="r", password=password) as zf:  # type: ignore
            if password is None and hasattr(zf, "needs_password") and zf.needs_password():
                raise PasswordRequiredError("压缩包已加密，请输入密码。")
            names = zf.getnames()
            total = len(names) or 1
            if progress:
                progress(max(1, total // 100), total, archive.name)
            zf.extractall(path=dest)
            if progress:
                progress(total, total, archive.name)
        if strip_prefix:
            _hoist_stripped_root(dest, strip_prefix)
    except PasswordRequiredError:
        raise
    except Exception as exc:
        _raise_password_error(exc)
        if _is_password_message(str(exc)):
            raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc
        raise


def _extract_rar(
    archive: Path,
    dest: Path,
    progress: Optional[ProgressCallback],
    password: Optional[str],
    strip_prefix: Optional[str] = None,
):
    try:
        with _open_rar(archive, password) as rf:
            archive_needs_pw = bool(getattr(rf, "needs_password", lambda: False)())
            if archive_needs_pw and not password:
                raise PasswordRequiredError("压缩包已加密，请输入密码。")
            members = rf.infolist()
            if password is None and any(
                hasattr(m, "needs_password") and m.needs_password() for m in members
            ):
                raise PasswordRequiredError("压缩包已加密，请输入密码。")
            if archive_needs_pw and not members:
                raise PasswordRequiredError("压缩包已加密，请输入正确密码。")
            # 跳过穿越 / 非法路径，避免单个坏条目导致整包失败。
            safe_members = []
            for member in members:
                safe_name = fix_archive_filename(member.filename).replace("\\", "/")
                rel = _relpath_for_extract(safe_name, None)
                if rel is None or _output_path_inside(dest, rel) is None:
                    continue
                safe_members.append(member)
            if not safe_members:
                raise ArchiveError("解压失败：压缩包中没有可写出的有效文件。")
            total = len(safe_members) or 1
            if progress:
                # 1% 起步，让进度条从动画切到确定模式并保持可见
                progress(max(1, total // 100), total, archive.name)
            # 头加密 RAR5 下逐文件 extract 可能 CRC 失败；全安全时 extractall 更可靠。
            if len(safe_members) == len(members):
                rf.extractall(dest)
            else:
                for member in safe_members:
                    try:
                        rf.extract(member, path=dest)
                    except Exception as exc:
                        _raise_password_error(exc)
                        continue
            # 若落盘文件名乱码，尝试重命名为修正后的中文名。
            for member in members:
                raw = member.filename
                fixed = fix_archive_filename(raw).replace("\\", "/")
                if raw == fixed:
                    continue
                src = dest / raw
                dst = dest / fixed
                if src.exists() and not dst.exists():
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    try:
                        src.rename(dst)
                    except OSError:
                        pass
            if progress:
                progress(total, total, archive.name)
        if strip_prefix:
            _hoist_stripped_root(dest, strip_prefix)
    except PasswordRequiredError:
        raise
    except ArchiveError:
        raise
    except Exception as exc:
        _raise_password_error(exc)
        if _is_password_message(str(exc)):
            raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc
        raise ArchiveError(f"解压 rar 失败：{exc}") from exc


def extract_archive(
    archive: os.PathLike | str,
    output_dir: Optional[os.PathLike | str] = None,
    progress: Optional[ProgressCallback] = None,
    password: Optional[str] = None,
) -> Path:
    """将压缩包解压到目录。

    :param archive: 压缩包路径。
    :param output_dir: 输出「父」目录；缺省为压缩包所在目录。
        实际会在其下新建与压缩包同名的文件夹；若已存在则自动加 `` (1)``、`` (2)``…
        若包内唯一根目录与压缩包同名，会剥掉该层避免套两层。
    :param progress: 进度回调 ``(done, total, name)``。
    :param password: 解压加密压缩包时的密码。
    :returns: 实际解压到的目录。
    """
    archive_path = Path(archive)
    if not archive_path.is_file():
        raise ArchiveError(f"文件不存在：{archive_path}")

    pwd = (password or "").strip() or None
    # 先列出条目，用于决定目标目录与是否剥根目录
    try:
        members = list_archive(archive_path, password=pwd)
        member_names = [m.name for m in members]
    except PasswordRequiredError:
        raise
    except Exception:
        member_names = []

    dest, strip_prefix = _plan_extract_destination(
        archive_path, output_dir, member_names
    )
    dest.mkdir(parents=True, exist_ok=True)
    lower = archive_path.name.lower()

    try:
        if lower.endswith(_ZIP_SUFFIXES):
            _extract_zip(archive_path, dest, progress, pwd, strip_prefix)
        elif lower.endswith(_TAR_SUFFIXES) or tarfile.is_tarfile(archive_path):
            _extract_tar(archive_path, dest, progress, strip_prefix)
        elif lower.endswith(".7z"):
            _extract_7z(archive_path, dest, progress, pwd, strip_prefix)
        elif lower.endswith(".rar"):
            _extract_rar(archive_path, dest, progress, pwd, strip_prefix)
        elif lower.endswith(_PLAIN_SUFFIXES):
            _extract_plain(archive_path, dest, progress)
        else:
            raise ArchiveError(f"不支持的压缩格式：{archive_path.name}")
    except (ArchiveError, PasswordRequiredError):
        raise
    except Exception as exc:
        _raise_password_error(exc)
        raise ArchiveError(f"解压失败：{exc}") from exc

    return dest


def extract_member(
    archive: os.PathLike | str,
    member_name: str,
    output_dir: os.PathLike | str,
    password: Optional[str] = None,
) -> Path:
    """从压缩包中解压单个文件到目录，返回解压后的文件路径。

    用于预览窗口双击打开：先解到临时目录，再用系统默认程序打开。
    """
    archive_path = Path(archive)
    if not archive_path.is_file():
        raise ArchiveError(f"文件不存在：{archive_path}")

    dest = Path(output_dir)
    dest.mkdir(parents=True, exist_ok=True)
    want = _normalize_member_name(member_name)
    if not want:
        raise ArchiveError("无效的文件名。")
    pwd = (password or "").strip() or None
    lower = archive_path.name.lower()

    try:
        if lower.endswith(_ZIP_SUFFIXES):
            return _extract_zip_member(archive_path, want, dest, pwd)
        if lower.endswith(_TAR_SUFFIXES) or tarfile.is_tarfile(archive_path):
            return _extract_tar_member(archive_path, want, dest)
        if lower.endswith(".7z"):
            return _extract_7z_member(archive_path, want, dest, pwd)
        if lower.endswith(".rar"):
            return _extract_rar_member(archive_path, want, dest, pwd)
        if lower.endswith(_PLAIN_SUFFIXES):
            # 单文件压缩：直接解压整个包
            _extract_plain(archive_path, dest, None)
            # 找出写出的文件
            strip = (
                ".gz" if lower.endswith(".gz") else
                ".bz2" if lower.endswith(".bz2") else ".xz"
            )
            out_name = archive_path.name[: -len(strip)] or archive_path.stem
            out_path = dest / out_name
            if out_path.is_file():
                return out_path
            raise ArchiveError(f"解压后未找到文件：{out_name}")
        raise ArchiveError(f"不支持的压缩格式：{archive_path.name}")
    except (ArchiveError, PasswordRequiredError):
        raise
    except Exception as exc:
        _raise_password_error(exc)
        raise ArchiveError(f"打开文件失败：{exc}") from exc


def _extract_zip_member(
    archive: Path, member_name: str, dest: Path, password: Optional[str]
) -> Path:
    zf, infos = _open_best_zip(archive, password)
    try:
        target = None
        for info in infos:
            fixed = _normalize_member_name(fix_archive_filename(info.filename))
            if fixed == member_name:
                target = info
                break
        if target is None:
            raise ArchiveError(f"压缩包中找不到：{member_name}")
        if target.filename.endswith("/") or getattr(target, "is_dir", lambda: False)():
            raise ArchiveError("不能打开目录，请选择文件。")

        # 写出时使用修正后的相对路径，避免乱码
        rel = _relpath_for_extract(
            fix_archive_filename(target.filename).replace("\\", "/"), None
        )
        if rel is None:
            raise ArchiveError(f"无法写出不安全的路径：{member_name}")
        out_path = _output_path_inside(dest, rel)
        if out_path is None:
            raise ArchiveError(f"无法写出不安全的路径：{member_name}")
        out_path = _prepare_file_path(out_path)
        try:
            data = zf.read(target)
        except RuntimeError as exc:
            _raise_password_error(exc)
            raise
        out_path.write_bytes(data)
        return out_path
    finally:
        zf.close()


def _extract_tar_member(archive: Path, member_name: str, dest: Path) -> Path:
    with tarfile.open(archive) as tf:
        target = None
        for info in tf.getmembers():
            if _normalize_member_name(info.name) == member_name:
                target = info
                break
        if target is None:
            raise ArchiveError(f"压缩包中找不到：{member_name}")
        if target.isdir():
            raise ArchiveError("不能打开目录，请选择文件。")
        rel = _relpath_for_extract(target.name, None)
        if rel is None:
            raise ArchiveError(f"无法写出不安全的路径：{member_name}")
        out_path = _output_path_inside(dest, rel)
        if out_path is None:
            raise ArchiveError(f"无法写出不安全的路径：{member_name}")
        out_path = _prepare_file_path(out_path)
        src = tf.extractfile(target)
        if src is None:
            raise ArchiveError(f"无法读取：{member_name}")
        with src, open(out_path, "wb") as dst:
            while True:
                chunk = src.read(1024 * 1024)
                if not chunk:
                    break
                dst.write(chunk)
        return out_path


def _extract_7z_member(
    archive: Path, member_name: str, dest: Path, password: Optional[str]
) -> Path:
    if not HAS_7Z:
        raise ArchiveError("解压 7z 需要安装 py7zr（pip install py7zr）。")
    try:
        with py7zr.SevenZipFile(archive, mode="r", password=password) as zf:  # type: ignore
            if password is None and hasattr(zf, "needs_password") and zf.needs_password():
                raise PasswordRequiredError("压缩包已加密，请输入密码。")
            names = list(zf.getnames())
            match = None
            for name in names:
                if _normalize_member_name(name) == member_name:
                    match = name
                    break
            if match is None:
                raise ArchiveError(f"压缩包中找不到：{member_name}")
            zf.extract(path=dest, targets=[match])
            out_path = _safe_join(dest, match)
            if out_path.is_dir():
                raise ArchiveError("不能打开目录，请选择文件。")
            if not out_path.is_file():
                raise ArchiveError(f"解压后未找到文件：{member_name}")
            return out_path
    except (ArchiveError, PasswordRequiredError):
        raise
    except Exception as exc:
        _raise_password_error(exc)
        if _is_password_message(str(exc)):
            raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc
        raise ArchiveError(f"打开 7z 内文件失败：{exc}") from exc


def _extract_rar_member(
    archive: Path, member_name: str, dest: Path, password: Optional[str]
) -> Path:
    if not HAS_RAR:
        raise ArchiveError("解压 rar 需要安装 rarfile，并提供 UnRAR 工具。")
    _configure_rar_tool()
    try:
        with rarfile.RarFile(archive) as rf:  # type: ignore
            if password:
                rf.setpassword(password)
            if password is None and hasattr(rf, "needs_password") and rf.needs_password():
                raise PasswordRequiredError("压缩包已加密，请输入密码。")
            match = None
            for info in rf.infolist():
                name = getattr(info, "filename", None) or getattr(info, "name", "")
                if _normalize_member_name(str(name)) == member_name:
                    match = info
                    break
            if match is None:
                raise ArchiveError(f"压缩包中找不到：{member_name}")
            is_dir = getattr(match, "isdir", lambda: False)()
            if is_dir:
                raise ArchiveError("不能打开目录，请选择文件。")
            rf.extract(match, path=dest)
            name = getattr(match, "filename", None) or getattr(match, "name", member_name)
            out_path = _safe_join(dest, str(name))
            if not out_path.is_file():
                raise ArchiveError(f"解压后未找到文件：{member_name}")
            return out_path
    except (ArchiveError, PasswordRequiredError):
        raise
    except Exception as exc:
        _raise_password_error(exc)
        if _is_password_message(str(exc)):
            raise PasswordRequiredError("压缩包已加密，请输入正确密码。") from exc
        raise ArchiveError(f"打开 rar 内文件失败：{exc}") from exc


# 模块导入时尽量配置好 rar 工具，避免首次解压才探测。
if HAS_RAR:
    try:
        _configure_rar_tool()
    except Exception:  # pragma: no cover
        pass
