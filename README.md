<div align="center">

# 🌿 护眼时光 · EyeCare

**每 20 分钟，望向 6 米外的远方 20 秒。**

纯本地 · 无联网 · 无广告 · 无追踪的 Android 20-20-20 护眼提醒应用

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![API](https://img.shields.io/badge/API-24%20~%2035-00C853)](#)
[![Version](https://img.shields.io/badge/version-v2.6.11-2E7D32)](releases/v2.6.11.md)
[![隐私](https://img.shields.io/badge/%E9%9A%90%E7%A7%81-%E9%9B%B6%E8%81%94%E7%BD%91-brightgreen)](#-设计边界)

</div>

---

## 📖 这是什么

「护眼时光」把 **20-20-20 法则** 变成一条你不需要记的规则：

> 每连续用眼 **20 分钟** → 向 **20 英尺（约 6 米）** 外远眺 **20 秒**。

到点自动提醒、可选全屏休息页、休息结束轻响一声，然后安静地进入下一个循环。没有账号、没有网络请求、没有广告位——所有数据只写在本机的 `SharedPreferences` 里，卸载即清除。

---

## ✨ 功能一览

| 页面 | 作用 |
|---|---|
| **⏱️ 计时** | 主界面。用眼 / 休息倒计时环、今日概览，以及**健康用眼知识轮播**；到点可「开始休息 / 稍后再说」 |
| **📊 报告** | 日 / 周 / 月用眼与休息统计：柱状图、达标率、周期对比 |
| **⚙️ 设置** | 提醒时长、到点自动全屏、休息结束提示音（可选音源 + 振动）、久坐提醒、免打扰时段、夜间模式——**直接铺在页面上，改一下立即生效**；另含提醒可靠性自检与「关于」 |

### 🔄 计时流程

```text
工作倒计时归零
      │
      ▼
  通知提醒（可选全屏休息页）
      │
      ▼
    休息阶段  ──→  休息结束：响一声提示音 + 静默记录
      │                        │
      │                        ▼
      │                   回到工作循环
      ▼
 到点未休息 → 超出时间继续计入统计，每 N 分钟重复提醒（超时二次提醒）
```

- 休息结束的处理是**幂等**的：界面到点与系统闹钟同时到达也只处理一次。
- 全流程只有**一个发声路径**：`RestSoundPlayer` 播一次，通知永久静默。

---

## 🗂️ 项目结构

```text
app/src/main/java/com/java/myapplication/
├── MainActivity.kt              单 Activity 入口
├── EyeCareApplication.kt        应用级初始化（动态注册屏幕状态广播）
├── data/                        设置 / 统计 / 计时状态 / 科普文案 / 应用信息 / 免责声明 / 引导标记
├── timer/                       状态机、闹钟调度、广播接收器、久坐提醒
├── notify/                      通知、提示音、振动、全屏休息悬浮窗服务
└── ui/                          各页面 Composable、轮播组件、自检面板、首次引导、主题
```

---

## 🚀 构建

需要 Android SDK 与 **JDK 17**：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew testDebugUnitTest --offline   # 单元测试（纯逻辑，无需真机）
./gradlew assembleDebug   --offline     # 打包 Debug APK
./gradlew assembleRelease --offline     # 打包 Release APK（已签名）

# 产物：
#   app/build/outputs/apk/debug/app-debug.apk
#   app/build/outputs/apk/release/app-release.apk
```

> **为什么要 `--offline`？**
> 适配无代理网络环境，依赖已在本地 Gradle 缓存中。
>
> `gradle.properties` 中已关闭 AAPT2 守护进程、并关闭 AGP 9 的资源优化（`android.enableResourceOptimizations=false`）——否则在 proot 环境会产出缺失 `AndroidManifest` 的空 APK。
>
> 单元测试覆盖免打扰时段判断、设置项解析 / 格式化、超时统计与跨天切分等纯函数。

---

## 📋 已知限制

| 现象 | 处理 |
|---|---|
| 「到点自动全屏」不弹 | 去 **设置 → 提醒可靠性自检**：亮屏时在别的 App 里依赖 **悬浮窗（后台全屏）**（Android 15/16 上全屏通知已失效，改走悬浮窗）；Android 14+ 另需开启 **全屏通知**。息屏 / 锁屏按设计不弹 |
| 到点完全没有提醒 | 检查通知权限、精确闹钟、电池优化（自检页逐项看） |
| 熄屏 / 重启后不再提醒 | 部分 ROM 禁止自启动：去 **应用管理 → 护眼时光 → 允许自启动** |
| 免打扰时段不生效 | 设计如此：免打扰只对久坐提醒生效，用眼 / 休息提醒不受影响 |
| 提示音没声音 | 检查系统 **通知音量**（与短信、微信同档）；静音 / 勿扰时按设计不响；关闭提示音会连同振动一起停 |
| 休息结束不振动 | 振动走「通知」用途，跟随系统通知振动设置 |
| 休息结束响两声 | 音源选到了双脉冲文件：去设置页点「试听」确认，换内置单脉冲音（清音 / 柔音 / 沉音） |
| 统计数字偏少 | 统计保留最近 90 天；无记录的日子不计入达标率分母 |

---

## 🔒 设计边界

- **不联网、不上报**，不申请与核心功能无关的权限；不需要摄像头、无障碍权限。
- **全流程只有一个发声路径**（v2.3.10 起）：提示音由 `RestSoundPlayer` 播一次，通知永久静默。
- **不含医疗建议**：本应用是习惯提醒工具，不提供诊断或治疗方案。
- **卸载即清除**全部本地数据（`SharedPreferences`）。

---

## 📦 版本历史

| 版本 | versionCode | APK MD5 |
|---|---:|---|
| v2.6.11 | 34 | `1a019c962f046fc9c01ac24abb54db95` |
| v2.4.3 | 33 | `8c9b1226b32e47826e4958e96d2e5596` |
| v2.4.2 | 32 | `9b70603091d9272f1e1df716a0b9ad17` |
| v2.4.0 | 30 | `c937b2ec574a61d486440f15e1166780` |
| v2.3.15 | 29 | `4ab377a275c93b705e6fb634dc563bd7` |
| v2.3.14 | 28 | `01ab98d6879b622a89eba03059976713` |
| v2.3.13 | 27 | `fbd5f22bbfc9d99557414df718c56206` |
| v2.3.12 | 26 | `1e5c6ba3e48290c67d6842c78247bbe8` |
| v2.3.11 | 25 | `53a83e5e82eb3f87c0739f19a6540f9b` |
| v2.3.10 | 24 | `cb8c1f0bb17f1d8e9750a05f5fcdf4dd` |
| v2.3.9 | 23 | `2b3eb122cf6a2d0a9ee8f54f6eef16fe` |
| v2.3.8 | 22 | `df9117b4bf73902745abdb11369b2b34` |
| v2.3.7 | 21 | `161f056758210493244de8902048656c` |
| v2.3.6 | 20 | `1a6ba6cc728714c9541fcebbf61d1a6e` |
| v2.3.5 | 19 | `45eec9b41a21d6f9ef668d84ee142071` |
| v2.3.4 | 18 | `694b8639b6b9a171babd90a9f09efc20` |
| v2.3.3 | 17 | `1558f76f0e054f9339166b574e0abd53` |
| v2.3.2 | 16 | `6e5b65c7546b16db5421594ebed7dd5e` |
| v2.3.1 | 15 | `658b931601b5ccf9bb5a413c34f4a77a` |

> v2.4.1（versionCode 31）为报告页横轴乱码的过渡修复版，未单独归档 tag。

### 🆕 更新日志

| 版本 | 说明 |
|---|---|
| **v2.6.11** | 修复**用眼时长统计不实**（虚高与漏记并存）：改为「已结算水位线」幂等结算，并在所有重置累计的出口同步清零水位线；修复**页面切换掉帧卡顿**（尤其冷启动后第一次切换）：改用 `HorizontalPager` + 弹簧，位移走 `graphicsLayer` 合成层不触发重排；动效统一为 **Apple 弹簧规范**（默认临界阻尼不弹跳，仅手势带动量时才回弹）；补 baseline profile；并按动效规范复审全项目 11 处交互 |
| v2.4.3 | 修复**息屏后再亮屏误弹超时提醒**：息屏 ≥1 分钟即结算并清零用眼计时，亮屏从 0 安静起算；超时提醒只在亮屏持续未休息时才弹 |
| v2.4.2 | 修复 Android 15/16 上**到点自动全屏失效**（改悬浮窗 + 前台服务）；修复报告页 30 天档横轴乱码；开关加二次确认；后台全屏休息页补上**圆环进度条** |
| v2.4.1 | 报告页 30 天档横轴乱码的**过渡修复版**（versionCode 31，未单独归档 tag） |
| v2.4.0 | 新增**超时二次提醒**（超时继续计入统计，每 N 分钟重复提醒）；科普页改**主页卡片轮播**；补过渡动效 |
| v2.3.15 | 设置页**即时生效 + 卡片流**（取消「修改设置」弹窗）；底部导航与顶栏 emoji 换**矢量图标**；休息结束提示音音源改**下拉选择** |
| v2.3.14 | 仓库地址随 GitHub 用户名迁移更新（`HuaHai-141225` → `HuaHai-114514`） |
| v2.3.13 | 新增免责声明、应用内标注仓库地址、首次启动引导（免责 + 可靠性设置两步） |
| v2.3.12 | 休息结束提示音改为**可选音源**（系统通知铃声 / 5 个内置音效），默认跟随系统 |
| v2.3.11 | 提示音改为**内置单脉冲音**，根治「响两声」；播放器换 `MediaPlayer`；设置页加「试听」 |
| v2.3.10 | 发声权收回应用（通知永久静默）；振动改用「通知」用途（此前被系统忽略） |
| v2.3.9 | 曾改由通知渠道发声，部分 ROM 无效，v2.3.10 已废弃 |
| v2.3.8 | 提示音换系统通知铃声 + 短振动，移除时长选项 |
| v2.3.7 | 提示音限时播放，防闹钟铃声过长 |
| v2.3.6 | 修复通知正文写死分钟数、Android 14+ 全屏提醒失效；新增可靠性自检页与提示音 |

<details>
<summary><b>🔍 v2.3.13 详情</b></summary>

- **免责声明**（`data/Disclaimer.kt`）：首次启动强制展示，外部点击 / 返回键无法绕过；设置页「关于」可随时回看。
- **仓库标注**（`data/AppInfo.kt`）：集中管理地址与版本号；设置页「关于」展示版本号、可点击仓库短地址、「打开仓库」「免责声明」按钮。
- **首次引导**（`ui/OnboardingDialog.kt`）：两步式。第 2 步复用自检面板的检查项（通知权限 / 精确闹钟 / 全屏通知 / 悬浮窗 / 电池优化 / 自启动），可「稍后设置」跳过；完成标记存于独立的 `eye_care_onboarding`。
- 为复用，`DiagnosticsPanel.kt` 的 `DiagItem` / `DiagRow` / `collectDiagnostics` 改为 `internal`。

</details>

> 更早版本的详细改动记录（含调试取证过程）见 [CHANGELOG.md](CHANGELOG.md)。

---

<div align="center">

**Happy Coding! 🌿**

</div>