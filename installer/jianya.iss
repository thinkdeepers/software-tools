; 简压 安装程序脚本（Inno Setup 6）
; 生成一个需要安装到电脑的安装包（非便携版）。
; 编译：ISCC.exe installer\jianya.iss   （需先用 build_windows.py 生成 dist\简压.exe）

#define MyAppName "简压"
#define MyAppVersion "1.1.10"
#define MyAppExeName "简压.exe"
#define MyAppPublisher "简压"

[Setup]
AppId={{7B3C2D1E-9A4F-4C2B-8E7A-0F1A2B3C4D5E}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
; 每用户安装到 LocalAppData，无需管理员权限；右键菜单写入 HKCU 保持一致。
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\{#MyAppName}
DisableProgramGroupPage=yes
DefaultGroupName={#MyAppName}
OutputDir=..\release
OutputBaseFilename=简压安装程序
SetupIconFile=..\assets\app.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加图标:"
; 默认勾选：安装后注册右键菜单与文件关联（可取消）
Name: "contextmenu"; Description: "注册右键菜单，并将简压设为压缩包默认打开程序（推荐）"; GroupDescription: "系统集成:"; Flags: checkedonce

[Files]
Source: "..\dist\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion
; 安装目录内嵌控制台 UnRAR（勿使用 rarlab 的 SFX 自解压包）
Source: "..\vendor\UnRAR.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\vendor\unrar_license.txt"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
; 安装完成后注册右键菜单与文件关联（勾选「系统集成」时）
Filename: "{app}\{#MyAppExeName}"; Parameters: "--install --quiet"; Tasks: contextmenu; Flags: runhidden waituntilterminated; StatusMsg: "正在注册右键菜单与文件关联..."
; 安装结束可选立即运行
Filename: "{app}\{#MyAppExeName}"; Description: "立即运行 {#MyAppName}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; 卸载前移除右键菜单
Filename: "{app}\{#MyAppExeName}"; Parameters: "--uninstall --quiet"; Flags: runhidden; RunOnceId: "RemoveContextMenu"
