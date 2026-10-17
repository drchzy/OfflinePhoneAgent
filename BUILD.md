# Android APK 构建与发布

适用于仓库 `drchzy/OfflinePhoneAgent`。当前项目使用 Kotlin / Jetpack Compose，`minSdk=30`（Android 11+），JDK 17，Gradle 8.9，`compileSdk=35`。

## 构建策略（2026-10-17 更新）

**预览版只执行一次 Release 编译，不再先执行 Debug 编译。**

- 旧流程：`gradle :app:assembleDebug` → `gradle :app:assembleRelease` → 校验/上传/发布。
- 新流程：**准备既有签名 → `gradle :app:assembleRelease --stacktrace` → 校验 → 上传 → GitHub Prerelease**。
- `assembleRelease` 会编译 Kotlin / Compose 并完成打包；无需额外编译 Debug 才能发现常规编译错误。
- 避免两次构建可减少重复任务及耗时，但具体节省幅度取决于 Gradle 缓存与 GitHub runner 状态，**不承诺固定百分比**。
- **没有签名 Secrets 时直接失败**，不创建临时签名或用 Debug APK 替代。保留原签名以支持覆盖安装及 App 内更新。
- GitHub Action 使用 `concurrency.cancel-in-progress: true`，同一分支后续提交会取消尚未完成的旧构建。请以**最新成功运行**为准。

## 一、预览版（开发分支）

工作流：[`.github/workflows/preview-apk.yml`](.github/workflows/preview-apk.yml)

触发条件：推送到 `feat/compose-movie-20261017`，或 GitHub Actions 中手动运行该工作流。

主要步骤：

1. Checkout → 安装 Java 17、Android SDK 35、Gradle 8.9。
2. 计算版本：`APP_VERSION_CODE=github.run_number+32`；`APP_VERSION_NAME=1.0.<CODE>-preview`。
3. 从仓库 Secrets 读取**既有**签名资料，签名缺失则失败。
4. **仅运行 `gradle :app:assembleRelease --stacktrace`**。
5. 将 `app/build/outputs/apk/release/app-release.apk` 复制成 `preview-apk/OfflinePhoneAgent-v<版本>.apk`。
6. 上传同名 GitHub Actions Artifact，校验签名版文件存在。
7. 创建 `v<版本>` GitHub Prerelease，附带可直接安装的 APK，`make_latest: false`。

下载：进入 [GitHub Releases](https://github.com/drchzy/OfflinePhoneAgent/releases)，选择最新的 `-preview` 条目下载 APK；也可在 App 的 **检查更新** 中获取可用预览版。

> 注意：`releases/latest` 通常指正式版，可能不会指向预览版。检查构建请访问 [Actions](https://github.com/drchzy/OfflinePhoneAgent/actions)，不要把旧版 APK 当成最新构建。

## 二、正式版（main）

工作流：[`.github/workflows/android-apk.yml`](.github/workflows/android-apk.yml)

- `main` 分支提交信息包含 `[build]` 时触发，或在 Actions 中手动运行。
- 使用固定 Release 签名，**仅执行 `gradle :app:assembleRelease --stacktrace`**。
- 版本由工作流 `github.run_number` 生成，上传 Actions Artifact 并发布正式 GitHub Release。
- 预览分支功能在合并到 `main` 前不会自动进入正式版。

## 三、签名配置

仓库设置：**Settings → Secrets and variables → Actions → Repository secrets**。

支持下面两种来源：

1. `KEYSTORE_SET`：原项目使用的一体化签名 Secret，按现有工作流规则解析。
2. 或分别设置 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

签名私钥、口令、keystore 文件**不要提交到 GitHub 源码、日志或文档**。构建过程中创建临时 `.jks`，退出时清理。

## 四、如何确认构建成功

1. 在 Actions 中找到目标分支**最新**的 `Preview Android APK` 工作流运行。
2. 核对 `Compile and sign release APK` 为 ✅ Success。
3. 核对 `Upload installable APK artifacts`、`Validate signed preview output`、`Create prerelease for in-app preview updates` 都成功。
4. 在 Releases 中确认与 Actions 版本号相同的 `OfflinePhoneAgent-v...apk` 实际存在。
5. 手机覆盖安装前确认 APK 使用**同一个签名密钥**；签名不一致不能直接覆盖安装。

需要额外诊断本地编译时，可手动运行：

```bash
gradle :app:assembleDebug --stacktrace
```

上面是**本地开发排错**命令，默认预览工作流不再执行它。若需要无签名调试包，应独立建立 Debug 调试工作流，不要降级预览版签名要求。

## 五、常见故障

- **Compile and sign release APK 失败**：展开 Actions 该步骤查看 Gradle / Kotlin / Android 错误；通常先修代码再重新提交。
- **Signing secrets are missing**：检查仓库 Secrets 名称、权限以及既有签名数据；不要临时换签名。
- **运行被取消**：同一分支有新提交触发了更新构建，检查最新 Actions Run。
- **Release 没有附件**：检查 Artifact 上传、输出路径和 Prerelease 发布步骤，确认目标 APK 实际存在。
- **旧版检查不到新预览版**：检查 AppUpdater 是否支持读取 Prerelease，或从对应 GitHub Release 手动下载。
- **安装提示签名冲突**：停止覆盖安装并核对签名来源，不建议在未备份应用数据时直接卸载。

## 六、相关文件

- `app/build.gradle.kts`：SDK、版本号、签名和依赖配置。
- `.github/workflows/preview-apk.yml`：预览版自动签名、构建与发布。
- `.github/workflows/android-apk.yml`：正式版构建与发布。
- `app/src/main/java/com/drchzy/offlinephoneagent/AppUpdater.kt`：应用内版本检测与升级。

**原则：开发分支提交 → 一次 Release 编译 → 确认签名 → 校验产物 → 发布 APK → 手机实测。**
