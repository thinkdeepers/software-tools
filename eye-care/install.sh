#!/usr/bin/env bash
# 安装护眼卫士到用户目录
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="$HOME/.local/share/eye-care"
BIN_DIR="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"

echo "==> 安装护眼卫士到 $INSTALL_DIR"

mkdir -p "$INSTALL_DIR" "$BIN_DIR" "$DESKTOP_DIR"

# 复制项目文件
cp -r "$SCRIPT_DIR/src" "$SCRIPT_DIR/assets" "$SCRIPT_DIR/requirements.txt" "$SCRIPT_DIR/run.sh" "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/run.sh"

# 安装 Python 依赖
pip install -q -r "$INSTALL_DIR/requirements.txt"

# 创建启动命令
cat > "$BIN_DIR/eye-care" << EOF
#!/usr/bin/env bash
exec "$INSTALL_DIR/run.sh" "\$@"
EOF
chmod +x "$BIN_DIR/eye-care"

# 安装桌面快捷方式
sed "s|__INSTALL_DIR__|$INSTALL_DIR|g" "$SCRIPT_DIR/eye-care.desktop" > "$DESKTOP_DIR/eye-care.desktop"

echo ""
echo "安装完成！"
echo "  启动命令: eye-care"
echo "  或在应用菜单中搜索「护眼卫士」"
echo ""
echo "首次使用请确保 ~/.local/bin 在 PATH 中："
echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
