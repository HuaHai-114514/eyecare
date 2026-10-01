# AGENTS.md — 护眼时光 EyeCare 项目接手须知

你是本项目的长期维护者。用户只提需求 / 报 bug，你负责**改码 → 构建 → 验证 → （用户验收后）升版本 → 推 git**。
请先通读本文件，再动手。**不要跳过"工作流约定"和"环境坑"。**

---

## 一、项目是什么

- **应用**：「护眼时光」——纯本地、无联网、无广告的 Android 20-20-20 护眼提醒 App。
- **包名 / applicationId**：`com.java.myapplication`
- **技术栈**：Kotlin + Jetpack Compose，MVVM，单 Activity
- **SDK**：minSdk 24 / targetSdk 35 / compileSdk 35，JDK 17
- **仓库**：`git@github.com:HuaHai-114514/eyecare.git`（SSH 推送，remote=origin，分支 main）
- **当前版本**：versionCode **32** / versionName **2.4.2**（tag `v2.4.2` 已推远端）
- **运行设备**：HyperOS（小米）真机

---

## 二、工作区与构建（**照抄可用，勿改**）

```bash
cd /root/DSH/EyeCare/bdf4b9b6-4b62-4e3e-996f-f38d6669018c

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/root/Android

./gradlew testDebugUnitTest --offline    # 单元测试
./gradlew assembleDebug   --offline      # debug 包
./gradlew assembleRelease --offline      # release 包（debug.keystore 自动签名）
```

**硬性要求：**
- **必须加 `--offline`**（依赖在本地 Gradle 缓存，联网会失败）。
- AAPT2 已强制 linux-aarch64；`android.enableResourceOptimizations=false`（否则 proot 下出**空 APK**）。
- 产物：`app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`
- 交付 APK 需复制到 `/sdcard/Download/EyeCare-vX.Y.Z.apk`
- 改码后**至少跑通** `testDebugUnitTest`（现约 12+ 个用例，应全绿、零警告）。

---

## 三、工作流约定（**最重要，务必遵守**）

采用「先测试、后上传」两阶段流程：

**阶段 1 — 改码期间**（用户提需求到验收前）
1. 只改代码，**不动 versionCode / versionName**，**不动 README / CHANGELOG**。
2. 构建 + 跑单测 + 出 APK 放到 `/sdcard/Download/`。
3. **停下来**，告诉用户 APK 路径 + MD5，等其装到真机实测。

**阶段 2 — 用户说「可以了 / 上传吧」之后**，一次性做完：
1. 升 versionCode / versionName（`app/build.gradle.kts`）。
2. 出正式（release 签名）APK，记录字节数 + MD5。
3. 更新 `README.md` 的版本历史与更新日志简表（**严格降序，最新在最上**）、补 `CHANGELOG.md`（记录调试取证过程）、写 `releases/vX.Y.Z.md`。
4. `git commit` + `git tag vX.Y.Z` + `git push`（含 tag）。

**提交信息格式**：`feat: vX.Y.Z ...` / `fix: ...` / `docs: ...`
**版本号只在 tag/release 体现**，不写死在仓库描述或代码硬编码里。

---

## 四、代码地图（关键文件）

- `MainActivity.kt`：单 Activity；onResume→`viewModel.onForeground` + `AppForeground=true`；onPause→`onBackground` + `false`
- `EyeCareApplication.kt`：动态注册 `ScreenStateReceiver`（SCREEN_ON/OFF/USER_PRESENT；Android 8+ 隐式广播只能动态注册）
- `AppForeground.kt`：`@Volatile var isForeground`（判断到点是走 App 内休息页还是悬浮窗）
- `timer/EyeTimer.kt`（核心状态机，约 688 行）
  - `LONG_OFF_RESET_MS = 5*60*1000L`：息屏 >5 分钟 → 亮屏清零；<5 分钟 → 续算。**自 v2.3.12 起逻辑未变**
  - 方法：`onScreenOn / onScreenOff / syncScreenState / resync / onWorkDeadline / beginRest / finishRest / skipRest / postponeRest`
  - `rollOverIfDayChanged`：跨天归零（v2.4.x 新增，把 0 点前的段结算到昨天）
  - `awaitingRest`（超时态）：亮屏=恢复超时累计，**不重置**
- `timer/AlarmScheduler.kt`：到点闹钟 / 兜底闹钟 / 超时提醒 / 久坐
- `timer/{ScreenStateReceiver,TimerReceiver,BootReceiver,SitReminder}.kt`
- `notify/RestOverlayService.kt`（v2.4.2 新增，约 407 行）：前台服务 + `TYPE_APPLICATION_OVERLAY` 自绘全屏休息页；内含 `ProgressRingView`（原生 Canvas 圆环，绿→橙→红）
- `notify/{RestNotifier,RestSoundPlayer,RestVibrator,RestSoundSource}.kt`
- `data/{SettingsStore,StatsStore,TimerStore,EyeTips,AppInfo,Disclaimer,OnboardingStore}.kt`：**全 SharedPreferences，不联网**
- `ui/`：`EyeCareApp`（导航）、`EyeCareViewModel`、`RestScreen`、`SettingsTab`、`DiagnosticsPanel`、`Cards`、`EyeProgressRing`、`DailyBarChart`、`EyeIcons`、`theme`
- 测试：`app/src/test/.../{DndWindow,AppSettingsTime,OverdueStat,DaySplitStat}Test.kt`

**提示音相关稳定约定**：不换自带音效，继续用 `RingtoneManager` 系统闹钟铃声（走闹钟音量，静音可响），播放层加时长上限。设置项 `restEndSoundSeconds` 默认 5 秒，可选 `[3,5,8]`，上限 60，`0`=不设上限。`stop()` 必须先 `removeCallbacks` 且设 `isLooping=false`。

---

## 五、环境坑（血泪教训，务必避开）

1. **中文 + 引号易被终端转义损坏**（`"秒"` → `秒`）。**改写文件优先用文件编辑工具**（create_file / edit_file）或写 Python 脚本；**避免复杂 heredoc / printf**。
2. **终端多行命令会被打断**——`super_admin:terminal` 里**用单行 + 分号 `;`**，不要依赖换行续行。
3. **adb 在本环境无法执行**，看不到设备运行进程 / 广播状态 → 运行时问题只能靠逻辑推演 + 让用户反馈。
4. `grep_code` 偶发串台（返回终端内容）→ 改用终端里的 `grep`。
5. `/root/` 是 proot 路径，Android shell（`super_admin:shell`）看不到；`/storage/emulated/0`、`/sdcard` 两边都可见。
6. proot overlay 会在 `.git/objects/` 生成 `.l2s.tmp_obj_*` 坏符号链接，复制/打包时用 tar 排除 `.gradle`、`.kotlin`、`app/build`、`*.apk`，必要时 `find ... -name '.l2s.tmp_obj_*' -delete`。

---

## 六、已知未决问题

1. **亮屏重置用眼计时**（用户反馈"又没了"，**已搁置**）：已彻查，代码自 v2.3.12 起未变，`offAt`/`leftOff` 判定完好。最可能场景落进 `awaitingRest`（到点未休息）或开了"自动全屏"。想查时**先加"计时诊断"面板**暴露 `workAccumMs / screenOnAt / offAt`，让用户亮灭屏自助观察，再定位。
2. **GitHub Release v2.4.2 未创建**：用户手动网页发布。材料已备：正文 `releases/v2.4.2.md`、APK `/sdcard/Download/EyeCare-v2.4.2.apk`（8,147,727 字节，MD5 `9b70603091d9272f1e1df716a0b9ad17`）。标题建议 `v2.4.2 · 2026-09-29`。

---

## 七、沟通风格

- 用户以轻松口吻交流，可适当亲切（Operit 侧助手被称作"鲨鱼娘 🦈"），但**技术结论必须严谨**。
- 汇报要**简洁**：用户明确讨厌繁杂。给结论 + 关键证据，不啰嗦。
- 有风险或不确定时**如实说**，不要在没有现场信息时盲改。

---

## 八、你的第一个动作

1. `cd` 到工作区，`git log --oneline -5` 和 `git status` 确认状态。
2. 跑一次 `./gradlew testDebugUnitTest --offline` 确认构建链可用。
3. 向用户回报：项目已就绪，等待需求。

Happy Coding! 🌿