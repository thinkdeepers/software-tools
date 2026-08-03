# 第三方工具

本目录存放随简压分发的辅助解压工具。

## UnRAR.exe

- 来源：[RARLab UnRAR for Windows](https://www.rarlab.com/rar_add.htm)
- 用途：供 `rarfile` 解压 `.rar` 压缩包
- 许可：UnRAR 工具可免费随软件分发（请保留原作者版权声明）

获取方式：

```bash
bash scripts/fetch_vendor_tools.sh
```

打包 Windows 安装程序时会自动下载并捆绑进 `简压.exe`。
