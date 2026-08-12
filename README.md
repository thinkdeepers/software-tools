# 简压（Jianya）

免费、简洁、**无广告、无弹窗、无捆绑**的压缩 / 解压小工具。

界面上只有两个按钮 —— **压缩** 和 **解压**。压缩统一输出为最常用的 **ZIP** 格式，
解压支持常见的多种压缩格式；安装后还能在文件上**右键**直接压缩或解压，
**双击压缩包可预览内容**，并支持**密码加密压缩 / 解压加密包**。

---

## 特点

- **极简界面**：只有"压缩"和"解压"两个大按钮，零学习成本。
- **压缩为 ZIP**：统一输出最通用的 zip 格式；可选 AES-256 密码加密。
- **解压多格式**：`zip` / `tar` / `tar.gz` / `tgz` / `tar.bz2` / `tar.xz` / `gz` / `bz2` / `xz` / `7z` / `rar`。
- **预览**：双击压缩包打开内容列表；列表中双击文件可打开，双击嵌套压缩包再开一层预览；关闭时清理临时文件。
- **密码**：加密压缩；解压加密 zip / 7z / rar 时自动提示输入密码。
- **默认打开 + 图标**：安装后 zip/7z/rar 等压缩包显示简压图标，双击即用简压预览。
- **右键菜单**：右键任意文件/文件夹即可"压缩为 ZIP"，右键压缩包即可"解压到此处"。
- **纯净**：没有广告、没有后台更新。
- **安全**：解压时防止路径穿越（Zip Slip）攻击。

## 界面预览

```
┌─────────────────────────────────────┐
│              简压                    │
│ 压缩统一为 ZIP · 解压支持常见格式 · 可加密 │
│                                      │
│    ┌──────────┐   ┌──────────┐      │
│    │   压缩   │   │   解压   │      │
│    └──────────┘   └──────────┘      │
│                                      │
│   [=========进度条=========]         │
│   选择文件开始压缩…                  │
│                                      │
│   [设为默认打开] [取消默认打开]      │
└─────────────────────────────────────┘
```

双击压缩包时进入预览窗口：

```
┌──────── 预览 — demo.zip ────────────┐
│ demo.zip                             │
│ 3 个项目                             │
│ ┌──────────────────────────────────┐ │
│ │ 名称          大小    压缩后      │ │
│ │ readme.txt    1.2 KB  600 B       │ │
│ │ photo.jpg     2.1 MB  2.0 MB      │ │
│ └──────────────────────────────────┘ │
│ [关闭]     [解压到当前文件夹] [解压到…] │
└──────────────────────────────────────┘
```

预览列表中**双击文件名**可用系统默认程序打开该文件；
双击其中的**嵌套压缩包**会再开一层预览；加密文件会先提示输入密码。
关闭预览或退出程序时会清理双击打开产生的临时文件。
「解压到当前文件夹」解压到压缩包所在目录，「解压到…」解压到你选中的文件夹（不会再套一层同名目录）。

## 下载 / 安装（安装版，非便携）

简压是需要**安装到电脑**的软件（不提供便携免安装版）。

- **安装程序**：仓库内已附带打包好的安装包 [`release/简压安装程序.exe`](release/)，
  双击运行安装向导即可。默认按**当前用户**安装到 `%LocalAppData%\Programs\简压`（无需管理员权限），
  并创建开始菜单/桌面快捷方式；安装时可勾选将简压设为压缩包默认打开程序（显示简压图标）。
- **卸载**：从"设置 → 应用"或开始菜单中的"卸载 简压"进行卸载，会自动清理文件关联与右键菜单。
- **自动构建**：每次推送都会由 GitHub Actions 在 Windows 上重新打包并制作安装程序，
  可在 Actions 运行页下载产物 `简压安装程序`；打 `v*` 标签时还会自动发布到 Release。

> 安装程序内的主程序使用真实的 Windows Python + PyInstaller 打包，为标准的 Windows PE
> 可执行文件；安装包由 Inno Setup 生成。若你不放心第三方二进制，也可以按下文自行打包。
> 安装版会在安装目录内嵌**控制台版** UnRAR（不是自解压 SFX），可直接解压 rar。

## 使用方式

### 方式一：直接运行源码（需要 Python 3.8+）

```bash
pip install -r requirements.txt   # 7z / rar / 加密 ZIP 支持
python main.py                    # 打开图形界面
```

命令行用法（右键菜单底层调用的也是这些）：

```bash
python main.py --compress <文件或目录...>   # 压缩为 zip
python main.py --compress <路径...> -p 密码 # 加密压缩
python main.py --extract  <压缩包>          # 解压
python main.py --extract  <压缩包> -p 密码  # 解压加密包
python main.py --open <压缩包>              # 打开预览
python main.py <压缩包>                     # 同上（双击关联）
python main.py --install                    # 设为默认打开 + 注册右键菜单
python main.py --uninstall                  # 取消默认打开 + 移除右键菜单
```

解压 rar 还需要系统提供 UnRAR（Linux: `sudo apt install unrar`；Windows 安装版已捆绑）。

### 方式二：自行制作安装程序（推荐给普通用户）

在 Windows 上：

```bash
pip install pyinstaller py7zr rarfile pyzipper
python build_windows.py       # 自动下载 UnRAR 并生成 dist\简压.exe

# 再用 Inno Setup 生成安装程序（需安装 Inno Setup 6）
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer\jianya.iss
```

生成的安装程序位于 `release\简压安装程序.exe`，分发给用户双击安装即可。

> 提示：右键菜单调用主程序完成压缩/解压后，会弹出一个"完成"提示框告知输出位置；
> 出错时会弹出错误提示。加密压缩包在缺少密码时会提示输入。

## 文件关联与右键菜单（Windows）

打开程序后点击底部的「设为默认打开」，或运行 `python main.py --install`。
安装过程写入 `HKEY_CURRENT_USER`，**不需要管理员权限**，只影响当前用户。

安装后：

- **zip / 7z / rar / tar / gz…** 压缩包在资源管理器中显示**简压图标**
- **双击**压缩包 → 打开简压预览窗口（再双击列表中的文件可直接打开）
- 右键**任意文件或文件夹** → **压缩为 ZIP（简压）**
- 右键**压缩包** → **解压到此处（简压）**

需要移除时点击「取消默认打开」或运行 `python main.py --uninstall`。

> 若个别扩展名已被其它软件锁定为「默认应用」（Windows 10/11 的 UserChoice），
> 可在 **设置 → 应用 → 默认应用** 中把对应格式改选为「简压」。

## 开发与测试

```bash
pip install -r requirements.txt pytest
python -m pytest
```

## 在 Linux / 云端环境打包（Wine）

仓库自带 Cursor 云端 Agent 环境配置，首次启动会自动安装 wine、Windows 版
Python 3.12、PyInstaller、Inno Setup 6 与 pytest（见 `.cursor/environment.json`
与 `.cursor/install.sh`）。之后一条命令即可在 Linux 上打出 Windows 安装程序：

```bash
bash .cursor/install.sh            # 首次准备环境（幂等，可重复运行）
bash scripts/build_windows_wine.sh # 生成 dist/简压.exe 与 release/简压安装程序.exe
```

> 说明：Wine 下的 Python 在标准输出被重定向时会报 `init_sys_streams` 错误，
> 上述脚本通过分配伪终端（`script`）规避该问题。

## 目录结构

```
.
├── main.py                     # 入口
├── build_windows.py            # PyInstaller 打包脚本（含 UnRAR 下载）
├── requirements.txt
├── assets/
│   ├── make_icon.py            # 生成应用图标
│   └── app.ico / app.png       # 应用图标
├── vendor/
│   └── UnRAR.exe               # Windows rar 解压工具（构建时下载）
├── installer/
│   └── jianya.iss              # Inno Setup 安装程序脚本
├── release/
│   └── 简压安装程序.exe        # 预编译好的 Windows 安装程序
├── .github/workflows/
│   └── build-windows.yml       # 自动打包 exe + 制作安装程序的 CI
├── src/jianya/
│   ├── core.py                 # 压缩/解压/预览/加密核心逻辑
│   ├── gui.py                  # 极简图形界面 + 预览窗口
│   ├── cli.py                  # 命令行解析
│   └── context_menu.py         # Windows 文件关联 + 右键菜单注册
└── tests/
    └── test_core.py
```

## 许可

免费使用。UnRAR 工具版权归 Alexander Roshal 所有，按其许可随软件分发。
