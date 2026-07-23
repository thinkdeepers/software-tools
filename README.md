# 简压（Jianya）

免费、简洁、**无广告、无弹窗、无捆绑**的压缩 / 解压小工具。

界面上只有两个按钮 —— **压缩** 和 **解压**。压缩统一输出为最常用的 **ZIP** 格式，
解压支持常见的多种压缩格式；安装后还能在文件上**右键**直接压缩或解压。

---

## 特点

- **极简界面**：只有"压缩"和"解压"两个大按钮，零学习成本。
- **压缩为 ZIP**：统一输出最通用的 zip 格式，随处可解压。
- **解压多格式**：`zip` / `tar` / `tar.gz` / `tgz` / `tar.bz2` / `tar.xz` / `gz` / `bz2` / `xz`，
  安装可选依赖后还支持 `7z` / `rar`。
- **右键菜单**：安装后右键任意文件/文件夹即可"压缩为 ZIP"，右键压缩包即可"解压到此处"。
- **纯净无依赖**：核心功能仅使用 Python 标准库，没有广告、没有后台更新。
- **安全**：解压时防止路径穿越（Zip Slip）攻击。

## 界面预览

```
┌─────────────────────────────────────┐
│              简压                    │
│   压缩统一为 ZIP · 解压支持常见格式  │
│                                      │
│    ┌──────────┐   ┌──────────┐      │
│    │   压缩   │   │   解压   │      │
│    └──────────┘   └──────────┘      │
│                                      │
│   [=========进度条=========]         │
│   选择文件开始压缩…                  │
│                                      │
│   [安装右键菜单] [移除右键菜单]      │
└─────────────────────────────────────┘
```

## 使用方式

### 方式一：直接运行源码（需要 Python 3.8+）

```bash
python main.py            # 打开图形界面
```

命令行用法（右键菜单底层调用的也是这些）：

```bash
python main.py --compress <文件或目录...>   # 压缩为 zip
python main.py --extract  <压缩包>          # 解压
python main.py --install                    # 注册 Windows 右键菜单
python main.py --uninstall                  # 移除 Windows 右键菜单
```

### 方式二：打包为 Windows 可执行文件（推荐给普通用户）

在 Windows 上：

```bash
pip install pyinstaller
python build_windows.py
```

生成的 `dist\简压.exe` 双击即可运行，无需安装 Python。

## 右键菜单集成（Windows）

打开程序后点击底部的「安装右键菜单」，或运行 `python main.py --install`。
安装过程写入 `HKEY_CURRENT_USER`，**不需要管理员权限**，只影响当前用户。

安装后：

- 右键**任意文件或文件夹** → **压缩为 ZIP（简压）**
- 右键**压缩包**（zip/7z/rar/tar/gz…）→ **解压到此处（简压）**

需要移除时点击「移除右键菜单」或运行 `python main.py --uninstall`。

## 可选：扩展解压格式

核心无需任何第三方库。若想解压 `7z` / `rar`：

```bash
pip install py7zr      # 7z 支持
pip install rarfile    # rar 支持（还需系统安装 unrar 或 bsdtar）
```

## 开发与测试

```bash
pip install pytest
python -m pytest
```

## 目录结构

```
.
├── main.py                 # 入口
├── build_windows.py        # PyInstaller 打包脚本
├── requirements.txt
├── src/jianya/
│   ├── core.py             # 压缩/解压核心逻辑
│   ├── gui.py              # 极简图形界面
│   ├── cli.py              # 命令行解析
│   └── context_menu.py     # Windows 右键菜单注册
└── tests/
    └── test_core.py
```

## 许可

免费使用。
