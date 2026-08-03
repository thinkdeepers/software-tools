# 第三方工具

本目录存放随简压分发的辅助解压工具。

## UnRAR.exe

- 来源：[RARLab UnRAR for Windows](https://www.rarlab.com/rar_add.htm)
- 用途：供 `rarfile` 解压 `.rar` 压缩包
- 许可：UnRAR 工具可免费随软件分发（见 `unrar_license.txt`）

### 重要

rarlab 提供的 `unrarw64.exe` 是**自解压包 (SFX)**，不能直接当作 UnRAR 调用，
否则每次解压都会弹出 “WinRAR self-extracting archive” 窗口。

`scripts/fetch_vendor_tools.sh` / `build_windows.py` 会下载 SFX 并解包出真正的
**控制台版** `UnRAR.exe`（PE Subsystem = Console）。

安装程序会把该文件复制到安装目录（与 `简压.exe` 同级），运行时优先使用。

获取方式：

```bash
FORCE_FETCH=1 bash scripts/fetch_vendor_tools.sh
```
