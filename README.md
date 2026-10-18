# OfflinePhoneAgent

一个轻量的 Android 工作台，使用 Kotlin、Jetpack Compose、Media3 构建。

## 界面

- **Codex**：设备码授权、5 小时/每周剩余额度、重置时间、刷新和桌面 Widget。
- **Movie**：动态配置解析、影视推荐、关键词搜索、影片详情、播放线路和分集、Media3 HLS/DASH/普通视频播放。
- **设置**：深浅色主题、API 配置、应用内检查更新。
- 已移除原手机自动化指令和无障碍服务入口。

## Movie 配置

在右上角设置中配置以下内容：

- **API BaseURL**：可以不填；应用会尝试从默认配置 JSON 中读取 `api_service`，并保存最后成功的地址。
- **配置 JSON 地址**：默认 `https://app-site.ecoliving168.com/domain_v5.json`，可自行修改。
- **AES Key / IV / HMAC Key**：仅本机填写，用于对接自己有权使用的兼容接口。不要把第三方密钥提交到公开 GitHub 仓库。

Movie 的协议适配源自提供的 1.0.0.8 文档，但远程域名、真实响应体、后台账户权限尚未验证。如果推荐、搜索或解析请求失败，请先提供一个可用的 BaseURL 和真实返回样本。当前没有实现后台登录、收藏、购买解锁或其他绕过授权功能。

## 开发

- Kotlin 2.0.21、Android Gradle Plugin 8.7.3
- Jetpack Compose + Material 3 + Coil + Media3
- compileSdk 35、minSdk 30（Android 11 起）

本地编译：

```bash
gradle :app:assembleDebug
```

## GitHub 发布

> **构建与发布操作说明**：[BUILD.md](BUILD.md)。2026-10-17 起，开发分支预览版已取消重复的 `assembleDebug`，直接一次 `assembleRelease` 生成原签名 APK，校验后自动发布 Prerelease；签名缺失则失败。

现有 `.github/workflows/android-apk.yml` 工作流在 main 分支提交信息含 `[build]` 时触发，使用现有固定签名数据构建 APK，并发布带版本号的 GitHub Release。签名密钥必须配置在仓库 Secrets 中，否则工作流按设计失败，防止覆盖安装时签名变化。

检查更新继续使用原来的 `AppUpdater`，旧版相同签名才能覆盖安装。

## 当前限制

- 提供了 Movie 网络适配器和播放器，但尚未使用真实 Movie 服务器进行端到端测试。
- v1 搜索筛选的 sort/res_type 可能需要按线上接口实例修正。
- 影视播放遵循服务端返回的可播放状态，不提供绕过内容授权机制。
