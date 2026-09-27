# 白名单随行 · AllowMate

[![Build](https://github.com/yiyezq162/AllowMate/actions/workflows/build.yml/badge.svg)](https://github.com/yiyezq162/AllowMate/actions/workflows/build.yml)

自动维护 Po0 的 IPv4 白名单：家里、公司的宽带出口变了，或手机换了移动网络，AllowMate 会把对应的白名单槽位更新成新的 /24 网段。Android 9+，标准模式无需 root。

> 当前源码为 **0.6.0-preview**，属于测试预发布，不是稳定正式版。

## 下载

在 [Releases](https://github.com/yiyezq162/AllowMate/releases) 页面下载：

| 文件 | 说明 |
| --- | --- |
| `AllowMate-<版本>.apk` | 推荐安装 |
| `AllowMate-Runtime-<版本>.zip` | 可选的 Magisk / KernelSU 模块，标准模式不需要 |
| `AllowMate-<版本>-debug.apk` | 仅供开发调试 |
| `SHA256SUMS-runtime*` | 以上文件的校验和 |

预览版使用同一开发证书签名，可直接覆盖安装旧版。不要为了解决签名冲突而卸载，否则会丢失本机配置。

## 快速上手

1. **设置** → 添加 Token（或粘贴官方接口链接）→ 检查连接。首次默认暂停且只读。
2. **白名单** → 点按槽位，给它起名并选择用途：
   - **固定网络**：家、公司等。绑定当前 Wi-Fi 后，该网络出口变化时自动更新这一格。
   - **设备移动**：跟随这台手机的移动数据出口。每台手机最多一个，多台手机各用独立槽位。
   - **保留用途**：不自动更新。
3. 打开“授权本机管理”并保存。保存只修改本机配置，不会立即写入 Po0。
4. **设置** → 开启自动同步，再到 **概览** 恢复检查。

没绑定 Wi-Fi 的固定槽也可以在槽位页用“手动更新一次”，现场核对后更新一次。更多细节见 [槽位说明](docs/SLOTS.md)。

## 安全设计

- **只动授权的槽位**：未知记录、其他设备管理的槽位、无槽号记录一律受保护，不删除、不清空、不迁槽。
- **严格识别固定网络**：必须同时匹配已授权的 Wi-Fi 名称、接入点 (BSSID)、加密方式和本次连接，绝不根据 IP 或网段猜测。可防误连，但无法防范刻意伪造的同名热点；同名的新接入点只会提示确认。
- **写入前后都核对**：查询 → 写前重读 → 记录待确认操作 → 定向写入 → 再次查询核验。平台没有 CAS，写前重读无法完全消除最后一刻的竞态，请勿让其他客户端共用同一槽位。
- **不信任代理出口**：检测到 VPN 或系统代理时跳过；国内出口与平台识别不一致时禁止写入。
- **少查接口**：切换网络约 3 秒后查询 Po0；网络不变时每 10 分钟只在本机对比出口 IP，变化了或满一小时才查询 Po0。手动检查随时可用；失败自动退避，并遵守平台的限流要求。系统休眠时可能延后。
- Android 12+ 读取 Wi-Fi 名称需要精确位置权限（不获取位置）。固定网络要在后台自动更新，还需在槽位的“后台识别”中把位置权限设为“始终允许”，否则只在打开应用时识别；移动数据槽不需要。Android 9–11 请使用手动更新。

## 隐私

Token 由 Android Keystore 加密；状态和 Wi-Fi 授权存放在不参与备份的私有目录。日志、诊断和模块通信不包含 SSID、BSSID 或 Token。国内出口默认通过 ip.3322.net 查询（网络不变时约每 10 分钟一次），该站点会看到你的出口 IP。请勿公开原始状态、接口链接或真实设备日志。

## 后台运行

标准模式（免 root）靠系统唤起应用，不需要常驻：

- **切换网络**：向系统登记网络变化，应用即使已被清理（未被强行停止）也会被唤起；同一网络上本机 IPv4 变化（如移动数据重新拨号）也会触发。
- **10 分钟兜底**：使用 Doze 期间也允许的闹钟，每 10 分钟对比一次出口；另有 15 分钟一次的系统任务作为备份。重启后自动恢复。

国产系统常会清理后台应用，请在「设置 → 后台运行」中允许不受电池优化限制，并在系统设置里允许自启动、后台运行，在最近任务中锁定本应用。强行停止后，所有唤起都会失效，直到再次打开应用。

## 运行模式

一般保持**标准模式**即可。**模块增强**（Magisk / KernelSU）只在网络变化时帮忙唤起检查，不保存 Token、不代替 APK 发请求，也不保证常驻。安装与回退见 [安装说明](docs/INSTALL.md) 和 [模块说明](module/README.md)。

## 开发

### 本地构建

需要 JDK 17 与 Android SDK（Compile/Target SDK 36，Build Tools 36.0.0，Min SDK 28）。设置 `JAVA_HOME` / `ANDROID_HOME`，脚本也会使用 `.tools/` 下的本地 JDK。

```sh
sh scripts/build.sh :core:test :app:assembleDebug :app:assemblePreview :app:lintPreview
sh scripts/package.sh
(cd dist && shasum -a 256 -c SHA256SUMS-runtime06)
```

产物在 `dist/`，文件名中的版本号取自 `app/build.gradle.kts`。

界面测试只在专用、无真实凭据的 API 36 ARM64 模拟器上运行（默认 `emulator-5580`），`SafeTestRunner` 会拒绝真机：

```sh
sh scripts/test-emulator.sh
```

### 自动构建与发布

GitHub Actions（[`.github/workflows/build.yml`](.github/workflows/build.yml)）会在每次推送和 Pull Request 时运行核心测试与 Lint，构建 APK 和模块 ZIP，结果可在 Actions 页面下载（保留 30 天）。

发布新版本：

1. 修改 `app/build.gradle.kts` 的 `versionName` / `versionCode`，以及 `module/module.prop` 的 `version` / `versionCode`。
2. 提交并推送后打标签：`git tag v<版本号> && git push origin v<版本号>`（与 `versionName` 一致）
3. Actions 会自动创建一个**草稿** Release 并附上全部文件。检查说明后点击 Publish 即可公开。

签名：已发布版本都用同一个开发证书签名（SHA-256 `f6a7aff…26be`），手机才能覆盖升级，模块也只信任这个证书。Actions 从仓库 Secret `DEBUG_KEYSTORE_BASE64` 读取该密钥；未配置时只能生成临时签名的测试包，打标签发布会直接失败。

### 工程结构

- `core/`：纯决策逻辑、槽位事务、协议、迁移与确定性测试
- `app/`：Compose 界面、私有存储、Android Wi-Fi 适配与调度
- `module/`：Magisk / KernelSU 辅助触发模块
- `scripts/`：构建、打包与模拟器测试

旧的 Profile / Budget 与 SlotPlan 仅用于兼容历史数据；当前统一使用 SlotLayout / LayoutSync。

更多文档：[槽位说明](docs/SLOTS.md) · [安装与恢复](docs/INSTALL.md) · [测试说明](docs/TESTING.md) · [改造报告](docs/IMPLEMENTATION.md)
