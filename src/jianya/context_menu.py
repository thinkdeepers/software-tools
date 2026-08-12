"""Windows 右键菜单 + 文件关联集成。

通过写入 ``HKEY_CURRENT_USER\\Software\\Classes`` 完成注册，
因此**不需要管理员权限**，且只影响当前用户。

安装后效果：
- 常见压缩包（.zip/.7z/.rar/.tar/.gz/.tgz/.bz2/.xz…）图标变为简压图标；
- 双击压缩包默认由简压打开并预览内容；
- 右键任意文件/文件夹可「压缩为 ZIP（简压）」；
- 右键压缩包顶部显示「解压到当前文件夹（简压）」。
"""

from __future__ import annotations

import os
import sys
from typing import Iterable, List, Optional

# 需要关联 / 注册解压菜单的扩展名。
_EXTRACT_EXTS = [
    ".zip", ".7z", ".rar", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".tbz2", ".txz",
]

_COMPRESS_KEY = "Jianya.Compress"
_EXTRACT_KEY = "Jianya.Extract"
_OPEN_KEY = "Jianya.Open"
_PROGID = "Jianya.Archive"
_APP_NAME = "简压"
_CAPABILITIES_PATH = f"Software\\{_APP_NAME}\\Capabilities"
_REGISTERED_APPS = "Software\\RegisteredApplications"

_EXTRACT_LABEL = "解压到当前文件夹（简压）"
_OPEN_LABEL = "用简压打开"
_COMPRESS_LABEL = "压缩为 ZIP（简压）"


class ContextMenuError(Exception):
    """右键菜单 / 文件关联相关错误。"""


def _require_windows():
    if os.name != "nt":
        raise ContextMenuError("文件关联与右键菜单目前仅支持 Windows 系统。")


def _exe_basename() -> str:
    if getattr(sys, "frozen", False):
        return os.path.basename(sys.executable)
    return "简压.exe"


def _launcher(action: str = "") -> str:
    """构造用于右键菜单 / 打开命令的启动命令，末尾带占位的 \"%1\"。

    - 打包成 exe 时直接调用自身。
    - 源码运行时使用 pythonw + 入口脚本。
    - ``action`` 为空时表示双击打开（进入预览）。
    """
    action = (action or "").strip()
    prefix = f"{action} " if action else ""

    if getattr(sys, "frozen", False):
        exe = sys.executable
        return f'"{exe}" {prefix}"%1"'

    # 源码运行：优先使用无控制台窗口的 pythonw。
    python = sys.executable
    pythonw = os.path.join(os.path.dirname(python), "pythonw.exe")
    if os.path.exists(pythonw):
        python = pythonw

    # 通过项目根目录的 main.py 启动，确保能找到 src 包。
    main_py = os.path.join(_project_root(), "main.py")
    return f'"{python}" "{main_py}" {prefix}"%1"'


def _project_root() -> str:
    # src/jianya/context_menu.py -> 项目根目录
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.abspath(os.path.join(here, os.pardir, os.pardir))


def _icon() -> str:
    """返回图标路径（exe 或 ico）。资源管理器会用它作为压缩包图标。"""
    if getattr(sys, "frozen", False):
        # 使用 exe 第 0 号图标（已由 PyInstaller 嵌入 app.ico）。
        return f"{sys.executable},0"
    ico = os.path.join(_project_root(), "assets", "app.ico")
    if os.path.exists(ico):
        return ico
    return ""


def _set_sz(winreg, path: str, name: Optional[str], value: str) -> None:
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, path) as key:
        winreg.SetValueEx(key, name, 0, winreg.REG_SZ, value)


def _set_none(winreg, path: str, name: str) -> None:
    """写入 REG_NONE（OpenWithProgids 常用）。"""
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, path) as key:
        winreg.SetValueEx(key, name, 0, winreg.REG_NONE, b"")


def _get_sz(winreg, path: str, name: Optional[str] = None) -> Optional[str]:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, path) as key:
            value, _ = winreg.QueryValueEx(key, name)
            return value
    except OSError:
        return None


def _delete_key_tree(winreg, path: str) -> None:
    """递归删除注册表键（先删子键）。"""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, path, 0, winreg.KEY_READ) as key:
            while True:
                try:
                    sub = winreg.EnumKey(key, 0)
                except OSError:
                    break
                _delete_key_tree(winreg, f"{path}\\{sub}")
        winreg.DeleteKey(winreg.HKEY_CURRENT_USER, path)
    except FileNotFoundError:
        pass
    except OSError:
        pass


def _create_verb(
    winreg,
    base_path: str,
    verb_key: str,
    label: str,
    command: str,
    *,
    position: Optional[str] = None,
) -> None:
    """在 ``base_path`` 下创建一个右键动词（含 command 子键）。"""
    verb_path = f"{base_path}\\{verb_key}"
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, verb_path) as key:
        winreg.SetValueEx(key, None, 0, winreg.REG_SZ, label)
        # MUIVerb 让新版资源管理器更稳定显示文案
        winreg.SetValueEx(key, "MUIVerb", 0, winreg.REG_SZ, label)
        icon = _icon()
        if icon:
            winreg.SetValueEx(key, "Icon", 0, winreg.REG_SZ, icon)
        if position:
            winreg.SetValueEx(key, "Position", 0, winreg.REG_SZ, position)
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, f"{verb_path}\\command") as key:
        winreg.SetValueEx(key, None, 0, winreg.REG_SZ, command)


def _delete_verb(winreg, base_path: str, verb_key: str):
    _delete_key_tree(winreg, f"{base_path}\\{verb_key}")


def _register_progid(winreg, open_cmd: str, extract_cmd: str) -> None:
    """注册 ProgID：双击打开预览；右键顶部为「解压到当前文件夹」。"""
    classes = "Software\\Classes"
    progid_path = f"{classes}\\{_PROGID}"
    shell = f"{progid_path}\\shell"

    # 先清旧 shell，避免残留默认动词导致双击变成解压
    _delete_key_tree(winreg, shell)

    _set_sz(winreg, progid_path, None, "简压压缩包")
    _set_sz(winreg, progid_path, "FriendlyTypeName", "简压压缩包")

    icon = _icon()
    if icon:
        _set_sz(winreg, f"{progid_path}\\DefaultIcon", None, icon)

    # 先注册解压（Position=Top → 右键菜单靠前）
    _create_verb(
        winreg,
        shell,
        _EXTRACT_KEY,
        _EXTRACT_LABEL,
        extract_cmd,
        position="Top",
    )
    # 再注册 open：双击默认动作 = 预览
    _create_verb(winreg, shell, "open", _OPEN_LABEL, open_cmd)
    _set_sz(winreg, shell, None, "open")


def _register_application(winreg, open_cmd: str, extract_cmd: str) -> None:
    """注册到 Applications，便于“打开方式”与默认应用识别。"""
    app = _exe_basename()
    base = f"Software\\Classes\\Applications\\{app}"
    shell = f"{base}\\shell"
    _delete_key_tree(winreg, shell)

    _set_sz(winreg, base, "FriendlyAppName", _APP_NAME)
    icon = _icon()
    if icon:
        _set_sz(winreg, f"{base}\\DefaultIcon", None, icon)
    _create_verb(
        winreg,
        shell,
        _EXTRACT_KEY,
        _EXTRACT_LABEL,
        extract_cmd,
        position="Top",
    )
    _create_verb(winreg, shell, "open", _OPEN_LABEL, open_cmd)
    _set_sz(winreg, shell, None, "open")
    # 声明支持的扩展名
    for ext in _EXTRACT_EXTS:
        _set_sz(winreg, f"{base}\\SupportedTypes", ext, "")

    # App Paths：帮助系统定位 exe
    if getattr(sys, "frozen", False):
        app_paths = (
            "Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\"
            + _exe_basename()
        )
        _set_sz(winreg, app_paths, None, sys.executable)
        _set_sz(winreg, app_paths, "Path", os.path.dirname(sys.executable))


def _clear_user_choice(winreg, ext: str) -> None:
    """尝试清除 Explorer 的 UserChoice，使 HKCU Classes 关联生效。

    Win10/11 的 UserChoice 带哈希保护，删除后可能被系统重建；
    仍尽量清除，以便 HKCU ProgID 有机会接管。失败时静默忽略。
    """
    base = (
        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\"
        f"FileExts\\{ext}"
    )
    # 先清 UserChoice / UserChoiceLatest（含可能的 Hash 子结构）
    for leaf in ("UserChoice", "UserChoiceLatest"):
        _delete_key_tree(winreg, f"{base}\\{leaf}")
    # 写入 OpenWithProgids，确保出现在打开方式中
    try:
        _set_none(winreg, f"{base}\\OpenWithProgids", _PROGID)
    except OSError:
        pass
    # OpenWithList 增加简压
    try:
        _set_sz(winreg, f"{base}\\OpenWithList\\a", None, _exe_basename())
        _set_sz(winreg, f"{base}\\OpenWithList", "MRUList", "a")
    except OSError:
        pass
    # 取消「始终使用此应用」提示的屏蔽
    try:
        toast = (
            "Software\\Microsoft\\Windows\\CurrentVersion\\"
            "ApplicationAssociationToasts"
        )
        _set_sz(winreg, toast, f"{_PROGID}_{ext}", "0")
    except OSError:
        pass


def _associate_extension(winreg, ext: str, open_cmd: str, extract_cmd: str) -> None:
    """把扩展名关联到 ProgID。

    不在 ``.ext\\shell`` 下挂动词：那里的默认动词容易覆盖 ProgID，
    导致双击变成解压。右键解压改由 ProgID + SystemFileAssociations 提供。
    """
    classes = "Software\\Classes"
    ext_path = f"{classes}\\{ext}"

    # 清掉扩展名下旧的 shell（可能把默认动词设成了解压）
    _delete_key_tree(winreg, f"{ext_path}\\shell")

    # 设为默认 ProgID（当前用户下优先于系统默认，除非存在受保护的 UserChoice）。
    _set_sz(winreg, ext_path, None, _PROGID)

    # 出现在「打开方式」列表中。
    _set_sz(winreg, f"{ext_path}\\OpenWithProgids", _PROGID, "")
    try:
        _set_none(winreg, f"{ext_path}\\OpenWithProgids", _PROGID)
    except OSError:
        pass

    # PerceivedType 帮助资源管理器把它识别为压缩包类型。
    _set_sz(winreg, ext_path, "PerceivedType", "compressed")
    _set_sz(winreg, ext_path, "Content Type", "application/x-compressed")

    # 清掉可能被其它软件锁定的 UserChoice
    _clear_user_choice(winreg, ext)


def _register_extract_everywhere(winreg, open_cmd: str, extract_cmd: str) -> None:
    """在 SystemFileAssociations 只注册「解压」，保证右键靠前出现解压项。

    不在此处注册「打开」，避免右键第一项变成打开；双击打开由 ProgID 的 open 负责。
    """
    classes = "Software\\Classes"
    for ext in _EXTRACT_EXTS:
        base = f"{classes}\\SystemFileAssociations\\{ext}\\shell"
        # 清掉旧的打开项，只保留解压
        _delete_verb(winreg, base, _OPEN_KEY)
        _create_verb(
            winreg,
            base,
            _EXTRACT_KEY,
            _EXTRACT_LABEL,
            extract_cmd,
            position="Top",
        )


def _register_capabilities(winreg, exts: Iterable[str]) -> None:
    """注册到「默认应用」列表，便于用户在系统设置中选择简压。"""
    _set_sz(winreg, _CAPABILITIES_PATH, "ApplicationName", _APP_NAME)
    _set_sz(
        winreg,
        _CAPABILITIES_PATH,
        "ApplicationDescription",
        "免费、简洁、无广告的压缩/解压工具",
    )
    icon = _icon()
    if icon:
        _set_sz(winreg, _CAPABILITIES_PATH, "ApplicationIcon", icon)
    for ext in exts:
        _set_sz(winreg, f"{_CAPABILITIES_PATH}\\FileAssociations", ext, _PROGID)

    _set_sz(winreg, _REGISTERED_APPS, _APP_NAME, _CAPABILITIES_PATH)


def _try_set_as_default(exts: Iterable[str]) -> None:
    """尽量通过系统 COM API 把简压设为各扩展名的默认程序（Win8+）。"""
    try:
        import ctypes
        from ctypes import wintypes
        from uuid import UUID

        ole32 = ctypes.windll.ole32
        ole32.CoInitialize(None)

        class GUID(ctypes.Structure):
            _fields_ = [
                ("Data1", ctypes.c_ulong),
                ("Data2", ctypes.c_ushort),
                ("Data3", ctypes.c_ushort),
                ("Data4", ctypes.c_ubyte * 8),
            ]

            def __init__(self, uuid_str: str):
                u = UUID(uuid_str)
                ctypes.Structure.__init__(self)
                self.Data1 = u.time_low
                self.Data2 = u.time_mid
                self.Data3 = u.time_hi_version
                for i, b in enumerate(u.bytes[8:]):
                    self.Data4[i] = b

        # CLSID_ApplicationAssociationRegistration / IApplicationAssociationRegistration
        CLSID = GUID("{591209c7-767b-42b2-9fba-44ee4615f2c7}")
        IID = GUID("{4e530b0a-e611-4c77-a3ac-9031d022281b}")

        punk = ctypes.c_void_p()
        hr = ole32.CoCreateInstance(
            ctypes.byref(CLSID),
            None,
            1,  # CLSCTX_INPROC_SERVER
            ctypes.byref(IID),
            ctypes.byref(punk),
        )
        if hr != 0 or not punk.value:
            return

        # vtable: QI, AddRef, Release, QueryCurrentDefault,
        # QueryAppIsDefault, QueryAppIsDefaultAll, SetAppAsDefault, SetAppAsDefaultAll
        vtbl = ctypes.cast(punk, ctypes.POINTER(ctypes.c_void_p)).contents
        vtbl_ptr = ctypes.cast(vtbl, ctypes.POINTER(ctypes.c_void_p))

        SetAppAsDefault = ctypes.WINFUNCTYPE(
            ctypes.HRESULT,
            ctypes.c_void_p,
            wintypes.LPCWSTR,
            wintypes.LPCWSTR,
            ctypes.c_int,
        )(vtbl_ptr[6])

        AT_FILEEXTENSION = 0
        for ext in exts:
            try:
                SetAppAsDefault(punk, _APP_NAME, ext, AT_FILEEXTENSION)
            except Exception:
                pass

        Release = ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtbl_ptr[2])
        Release(punk)
    except Exception:
        pass


def install() -> None:
    """注册文件关联与右键菜单。"""
    _require_windows()
    import winreg  # type: ignore

    compress_cmd = _launcher("--compress")
    extract_cmd = _launcher("--extract")
    open_cmd = _launcher("--open")
    classes = "Software\\Classes"

    # 1) ProgID：双击 = 预览；右键顶部 = 解压到当前文件夹
    _register_progid(winreg, open_cmd, extract_cmd)

    # 2) Applications 注册（打开方式列表）
    _register_application(winreg, open_cmd, extract_cmd)

    # 3) 扩展名关联（清掉 .ext\\shell 旧动词，避免双击变解压）
    for ext in _EXTRACT_EXTS:
        _associate_extension(winreg, ext, open_cmd, extract_cmd)

    # 4) 出现在系统「默认应用」中
    _register_capabilities(winreg, _EXTRACT_EXTS)

    # 5) 右键：任意文件/文件夹 → 压缩
    _create_verb(
        winreg, f"{classes}\\*\\shell", _COMPRESS_KEY, _COMPRESS_LABEL, compress_cmd
    )
    _create_verb(
        winreg,
        f"{classes}\\Directory\\shell",
        _COMPRESS_KEY,
        _COMPRESS_LABEL,
        compress_cmd,
    )

    # 6) SystemFileAssociations：仅注册解压（靠前），不注册打开
    _register_extract_everywhere(winreg, open_cmd, extract_cmd)

    # 7) 尝试 COM 设为默认
    _try_set_as_default(_EXTRACT_EXTS)
    _notify_shell_changed()


def uninstall() -> None:
    """移除文件关联与右键菜单。"""
    _require_windows()
    import winreg  # type: ignore

    classes = "Software\\Classes"

    # 右键动词
    _delete_verb(winreg, f"{classes}\\*\\shell", _COMPRESS_KEY)
    _delete_verb(winreg, f"{classes}\\Directory\\shell", _COMPRESS_KEY)
    for ext in _EXTRACT_EXTS:
        base = f"{classes}\\SystemFileAssociations\\{ext}\\shell"
        _delete_verb(winreg, base, _EXTRACT_KEY)
        _delete_verb(winreg, base, _OPEN_KEY)
        # 扩展名下的动词
        _delete_verb(winreg, f"{classes}\\{ext}\\shell", _EXTRACT_KEY)
        _delete_verb(winreg, f"{classes}\\{ext}\\shell", _OPEN_KEY)

    # Applications / App Paths
    _delete_key_tree(winreg, f"{classes}\\Applications\\{_exe_basename()}")
    _delete_key_tree(
        winreg,
        "Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + _exe_basename(),
    )

    # 扩展名：仅当仍指向我们的 ProgID 时才清除默认值
    for ext in _EXTRACT_EXTS:
        ext_path = f"{classes}\\{ext}"
        current = _get_sz(winreg, ext_path, None)
        if current == _PROGID:
            try:
                with winreg.OpenKey(
                    winreg.HKEY_CURRENT_USER, ext_path, 0, winreg.KEY_SET_VALUE
                ) as key:
                    winreg.DeleteValue(key, None)
            except OSError:
                pass
        # OpenWithProgids
        try:
            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                f"{ext_path}\\OpenWithProgids",
                0,
                winreg.KEY_SET_VALUE,
            ) as key:
                winreg.DeleteValue(key, _PROGID)
        except OSError:
            pass

    # ProgID、Capabilities、RegisteredApplications
    _delete_key_tree(winreg, f"{classes}\\{_PROGID}")
    _delete_key_tree(winreg, f"Software\\{_APP_NAME}")
    try:
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER, _REGISTERED_APPS, 0, winreg.KEY_SET_VALUE
        ) as key:
            winreg.DeleteValue(key, _APP_NAME)
    except OSError:
        pass

    _notify_shell_changed()


def associated_extensions() -> List[str]:
    """返回会被关联的扩展名列表（供测试 / 文档使用）。"""
    return list(_EXTRACT_EXTS)


def _notify_shell_changed() -> None:
    """通知资源管理器刷新，使图标与关联立即生效。"""
    try:
        import ctypes

        SHCNE_ASSOCCHANGED = 0x08000000
        SHCNF_IDLIST = 0x0000
        ctypes.windll.shell32.SHChangeNotify(
            SHCNE_ASSOCCHANGED, SHCNF_IDLIST, None, None
        )
        # 再刷一次，部分环境一次不够
        ctypes.windll.shell32.SHChangeNotify(
            SHCNE_ASSOCCHANGED, SHCNF_IDLIST, None, None
        )
    except Exception:
        pass
