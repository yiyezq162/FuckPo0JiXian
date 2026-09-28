# 去他妈的鸡险 · FuckPo0JiXian

[![Build](https://github.com/yiyezq162/FuckPo0JiXian/actions/workflows/build.yml/badge.svg)](https://github.com/yiyezq162/FuckPo0JiXian/actions/workflows/build.yml)

自动维护 Po0 的 IPv4 白名单：家里、公司的宽带出口变了，或手机换了移动网络，FuckPo0JiXian 会把对应的白名单槽位更新成新的 /24 网段。Android 9+（标准模式无需 root），以及 macOS / Windows 桌面端。

> 当前源码为 **0.8.1-preview**，属于测试预发布，不是稳定正式版。

## 下载

在 [Releases](https://github.com/yiyezq162/FuckPo0JiXian/releases) 页面下载：

| 文件 | 说明 |
| --- | --- |
| `FuckPo0JiXian-<版本>.apk` | 推荐安装 |
| `FuckPo0JiXian-Runtime-<版本>.zip` | 可选的 Magisk / KernelSU 模块，标准模式不需要 |
| `FuckPo0JiXian-<版本>-debug.apk` | 仅供开发调试 |
| `SHA256SUMS-runtime*` | 以上文件的校验和 |
| `FuckPo0JiXian-Desktop-<版本>-macos-arm64.dmg` | Mac（Apple 芯片）桌面端 |
| `FuckPo0JiXian-Desktop-<版本>-windows-x64.msi` / `.exe` | Windows 桌面端，二选一 |
| `SHA256SUMS-desktop-*` | 桌面端校验和 |

## 快速上手

1. **设置** → 添加 Token（或粘贴官方接口链接）→ 检查连接。首次默认暂停且只读。
2. **白名单** → 点按槽位，给它起名并选择用途：
   - **固定网络**：家、公司等。绑定当前 Wi-Fi 后，该网络出口变化时自动更新这一格。
   - **设备移动**：跟随这台手机的移动数据出口。每台手机最多一个，多台手机各用独立槽位。
   - **保留用途**：不自动更新。
3. 打开“授权本机管理”并保存。保存只修改本机配置，不会立即写入 Po0。
4. **设置** → 开启自动同步，再到 **概览** 恢复检查。

没绑定 Wi-Fi 的固定槽也可以在槽位页用“手动更新一次”，现场核对后更新一次。更多细节见 [槽位说明](docs/SLOTS.md)。

## 多台设备

Po0 没有备注字段，也不区分是谁写的，所以各设备之间不通信：**每台设备只授权自己负责的槽位**，其他槽位标为“其他设备”并写上设备名（如 Mac、Windows），被改动时显示“其他设备已更新”。

- 家里的手机和电脑想一起维护“家”这一格：两边都授权这一格并打开“与其他设备共管”，谁先发现出口变化谁更新，另一台会跟上而不是报冲突。
- 「导出脱敏数据」（不含 Token、账户标识、Wi-Fi 与路由器信息，IP 只保留前两段）可用于复盘；在另一台设备上「从剪贴板导入」，会自动标注导出方管理的槽位，不授权、不写入。

## 桌面端（macOS / Windows）

设置方式与手机相同：添加 Token → 在白名单里给本机负责的槽位授权 → 固定网络点“绑定当前网络” → 打开自动同步。主窗口左侧是「概览 / 白名单 / 记录 / 设置」，白名单左边选槽位、右边直接编辑。关闭窗口后在菜单栏 / 托盘继续运行，菜单里能看到状态和出口，可立即检查、暂停或打开主窗口；可在设置里开启开机启动。

- **按路由器识别网络**（默认网关及其 MAC 地址），有线、Wi-Fi 都适用，不需要定位权限。换路由器后重新绑定。
- **出口直接从本机网卡核对**：出口通过绑定物理网卡的 STUN（哔哩哔哩、小米等国内服务器，失败时用 ip.3322.net）获取，不受系统代理和 Clash 等 VPN / TUN 影响。Po0 请求优先走物理网卡；部分 Windows TUN 下直连 TCP 收不到回包，此时查询会经 TUN 发出，但只有 Po0 看到的出口与直连出口一致才会写入，代理节点的 IP 不会进白名单。
- “外出跟随”槽适合笔记本：在未绑定的网络上跟随本机出口。
- Token 保存在 macOS 钥匙串 / Windows DPAPI 中。
- 预览版未签名：Mac 首次打开被拦截时，到「系统设置 → 隐私与安全性」点“仍要打开”（目前仅 Apple 芯片版）；Windows 可能出现 SmartScreen 提示，点“更多信息 → 仍要运行”。

## 更新

三端都在「设置」里检查更新（每天自动查一次，不会自动下载）。点“下载并安装”后先核对 GitHub 给出的 SHA-256，再安装：

- **Android**：由系统弹出安装确认，首次需允许本应用“安装未知应用”；只接受同一签名的 APK，配置保留。运行辅助模块仍在 Magisk / KernelSU 中更新。
- **Mac**：应用退出后自动替换并重新打开（需要对应用所在位置有写权限，否则会打开安装包，拖进“应用程序”即可）。
- **Windows**：应用退出后用安装包就地升级（当前用户安装，无需管理员），完成后自动重新打开。

## 安全设计

- **只动授权的槽位**：未知记录、其他设备管理的槽位、无槽号记录一律受保护，不删除、不清空、不迁槽。
- **严格识别固定网络**：必须同时匹配已授权的 Wi-Fi 名称、接入点 (BSSID)、加密方式和本次连接，绝不根据 IP 或网段猜测。可防误连，但无法防范刻意伪造的同名热点；同名的新接入点只会提示确认。
- **写入前后都核对**：查询 → 写前重读 → 记录待确认操作 → 定向写入 → 再次查询核验。平台没有 CAS，写前重读无法完全消除最后一刻的竞态，请勿让其他客户端共用同一槽位。
- **不信任代理出口**：检测到 VPN 或系统代理时跳过；国内出口与平台识别不一致时禁止写入。
- **少查接口**：切换网络约 3 秒后查询 Po0；网络不变时每 10 分钟（设置里可调为 2–59 分钟）只在本机对比出口 IP，变化了或满一小时才查询 Po0。手动检查随时可用；失败自动退避，并遵守平台的限流要求。系统休眠时可能延后。
- Android 12+ 读取 Wi-Fi 名称需要精确位置权限（不获取位置）。固定网络要在后台自动更新，还需在槽位的“后台识别”中把位置权限设为“始终允许”，否则只在打开应用时识别；移动数据槽不需要。Android 9–11 请使用手动更新。

## 隐私

Token 由 Android Keystore 加密；状态和 Wi-Fi 授权存放在不参与备份的私有目录。日志、诊断和模块通信不包含 SSID、BSSID 或 Token。国内出口默认通过 ip.3322.net 查询（网络不变时约每 10 分钟一次），该站点会看到你的出口 IP。请勿公开原始状态、接口链接或真实设备日志。

## 后台运行

标准模式（免 root）靠系统唤起应用，不需要常驻：

- **切换网络**：向系统登记网络变化，应用即使已被清理（未被强行停止）也会被唤起；同一网络上本机 IPv4 变化（如移动数据重新拨号）也会触发。
- **10 分钟兜底**：使用 Doze 期间也允许的闹钟，每 10 分钟对比一次出口；另有 15 分钟一次的系统任务作为备份。重启后自动恢复。

国产系统常会清理后台应用，请在「设置 → 后台运行」中允许不受电池优化限制、打开「不显示后台任务」（一键清理最近任务时不会清掉本应用），并在系统设置里允许自启动和后台运行。强行停止后，所有唤起都会失效，直到再次打开应用。

## 运行模式

一般保持**标准模式**即可。**模块增强**（Magisk / KernelSU）只在网络变化时帮忙唤起检查，不保存 Token、不代替 APK 发请求，也不保证常驻。安装与回退见 [安装说明](docs/INSTALL.md) 和 [模块说明](module/README.md)。

## 开发

### 本地构建

需要 JDK 17 与 Android SDK（Compile/Target SDK 36，Build Tools 36.0.0，Min SDK 28）。设置 `JAVA_HOME` / `ANDROID_HOME`，脚本也会使用 `.tools/` 下的本地 JDK。

桌面端（Compose Multiplatform，与安卓共用 `core`）只需 JDK 17：`sh scripts/build.sh :desktop:run` 直接运行，`:desktop:packageDmg` 在 Mac 上打包；Windows 安装包只能在 Windows 上打（CI 已包含）。

```sh
sh scripts/build.sh :core:test :app:assembleDebug :app:assemblePreview :app:lintPreview
sh scripts/package.sh
(cd dist && shasum -a 256 -c SHA256SUMS-runtime07)
```

产物在 `dist/`，文件名中的版本号取自 `app/build.gradle.kts`。

界面测试只在专用、无真实凭据的 API 36 ARM64 模拟器上运行（默认 `emulator-5580`），`SafeTestRunner` 会拒绝真机：

```sh
sh scripts/test-emulator.sh
```

### 自动构建与发布

GitHub Actions（[`.github/workflows/build.yml`](.github/workflows/build.yml)）会在每次推送和 Pull Request 时运行核心测试与 Lint，构建 APK 和模块 ZIP，并分别在 macOS / Windows 上测试和打包桌面端，结果可在 Actions 页面下载（保留 30 天）。

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
