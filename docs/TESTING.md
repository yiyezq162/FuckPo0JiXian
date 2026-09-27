# 0.4.0-preview 验证状态

预发布用于扩大真机测试范围。以下历史结论来自本地开发记录；公开仓库不附原始账户和设备证据，不能作为独立可复现的生产验收报告。

| 范围 | 当前结论 |
| --- | --- |
| 核心状态机和安全策略 | 本地历史 85 项 JVM 测试通过，可从源码重新运行 |
| 模拟器 | API 36 ARM64 历史 18 项运行模式/UI/视觉套件通过，另有显示矩阵检查；属于夹具证据 |
| 构建 | preview 非 debug，R8 / 资源压缩，开发签名；发布前重新执行核心测试、构建与 Lint |
| 小米 Android 16 / KernelSU | 有真实只读请求、专用槽位更新、事件唤起、INFO / Action 和禁用退出的分阶段证据 |
| HyperOS 冷启动 | 依赖自启动许可，拒绝唤起时显示降级；不承诺所有后台窗口通过 |
| 深 Doze | 未完成真实 HTTP 闭环；实现降级并退出，不保证即时同步 |
| 功耗 | 只有插电短样本；自然待机、整夜保活、省电优势未知 |
| 其他机型 / Magisk / root 撤销 | 需要独立验收；Android 9–15 未完整执行兼容性测试 |
| 业务连通 | API 或白名单成功不等于业务入口连接成功 |

## 本地检查

```sh
sh scripts/build.sh :core:test :app:assembleDebug :app:assemblePreview :app:lintPreview
sh scripts/package.sh
(cd dist && shasum -a 256 -c SHA256SUMS-runtime04)
```

只在专用、无真实凭据的 API 36 ARM64 模拟器运行 `scripts/test-emulator.sh`。真机专用 instrumentation 类会读写应用状态，部分方法能触发真实平台写入，必须理解范围并单独授权，不可批量运行来验证安装成功。

## 手动测试反馈

记录机型、Android / ROM、APK 版本、标准或增强模式、同步开关、时间以及前台/切网/后台场景的脱敏结果。将“已选择模式”“已握手”“新请求完成”“写后读回”“业务连接”分别记录。第二台手机必须使用独立空闲槽位，先只读再考虑自动同步。
