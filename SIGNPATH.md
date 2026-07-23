# 使用 SignPath Foundation 为简压做代码签名

SignPath Foundation 为**合规的开源项目**免费提供代码签名证书与签名服务，
可消除浏览器下载警告与 Windows SmartScreen 的“未知发布者”提示。

> 说明：证书私钥保存在 SignPath 的 HSM 中，任何人都无法导出证书文件；
> 签名只能由**你授权、并绑定到本 GitHub 仓库**的 CI 触发。因此这份接入
> **必须由项目负责人本人完成申请与门户配置**，无法由他人代签。

本仓库已完成“可签名”改造：加入了 OSI 许可证（`LICENSE`，MIT），并在
`.github/workflows/build-windows.yml` 中预置了受密钥保护的签名步骤。你只需
完成下面的申请与配置即可。

## 一、资格前提（已满足/需确认）

- [x] OSI 认可的开源许可证：本仓库为 **MIT**（见 `LICENSE`）。
- [x] 源码公开于 GitHub。
- [x] 发行版可免费下载：CI 在打 `v*` 标签时发布 GitHub Release。
- [ ] 无恶意行为（压缩/解压工具，天然满足）。
- [ ] 建议先创建至少一个 GitHub Release（打一个 `v1.0.0` 标签即可）。

## 二、申请（你本人操作）

1. 打开 https://signpath.io/product/open-source ，点击 **Apply for free**。
2. 表单填写：
   - **仓库 URL**：本仓库地址；
   - **许可证**：MIT（指明有 `LICENSE` 文件）；
   - **下载地址**：GitHub Releases 页面 URL；
   - **签名用途**：消除 SmartScreen / 浏览器下载警告。
3. 提交后等待审核通过。

## 三、门户配置（审核通过后）

在 SignPath 门户中：

1. 安装 **SignPath GitHub App** 并授权访问本仓库（源码/构建策略需要）。
2. 创建并记录以下项（名称需与 CI 中的 slug 一致，或据实修改 CI）：
   - **Project slug**：`jianya`
   - **Signing policy slug**：`release-signing`
   - **Artifact configuration slug**：`installer`
     - 由于分发物是 **Inno Setup 安装程序**，请选择/配置为
       Inno Setup 安装包类型，使其**同时对安装程序及其内部的 `简压.exe`** 签名。
3. 获取 **API Token**（具备该 project/policy 的 submitter 权限）与
   **Organization ID**。

## 四、在 GitHub 配置密钥

仓库 **Settings → Secrets and variables → Actions** 新增：

- `SIGNPATH_API_TOKEN`：上一步的 API Token
- `SIGNPATH_ORG_ID`：Organization ID

配置后，CI 会在构建安装程序后自动：上传未签名产物 → 提交 SignPath 签名请求
→ 用已签名文件替换发行物 → 打 tag 时把**已签名**安装程序发布到 Release。
未配置密钥时这些步骤会**自动跳过**，不影响普通构建。

## 五、验证签名

下载已签名的安装程序后，可用 PowerShell 验证：

```powershell
Get-AuthenticodeSignature .\简压安装程序.exe | Format-List
```

`Status` 应为 `Valid`，`SignerCertificate` 显示 SignPath Foundation 颁发者。

## 相关链接

- SignPath Foundation：https://signpath.org/
- OSS 计划：https://signpath.io/product/open-source
- GitHub Action 文档：https://docs.signpath.io/trusted-build-systems/github
- 签名 Action 仓库：https://github.com/signpath/github-action-submit-signing-request
