@echo off
chcp 65001 >nul
setlocal

cd /d "%~dp0"

echo ========================================
echo   护眼卫士 Windows 打包脚本
echo ========================================
echo.

python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Python，请先安装 Python 3.10+
    pause
    exit /b 1
)

echo [1/3] 安装依赖...
pip install -r requirements.txt pyinstaller -q
if errorlevel 1 (
    echo [错误] 依赖安装失败
    pause
    exit /b 1
)

echo [2/3] 开始打包 EXE...
pyinstaller --noconfirm --clean eye-care.spec
if errorlevel 1 (
    echo [错误] 打包失败
    pause
    exit /b 1
)

echo [3/3] 完成!
echo.
echo 输出文件: dist\eyecare.exe
echo 发布目录: release\护眼卫士.exe
echo.
pause
