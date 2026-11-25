# dydock · 抖音底栏液态玻璃

面向抖音 Android 客户端的 LSPosed 模块：**把底部导航栏换成悬浮液态玻璃 Dock**，只动底栏这一块，其余界面保持抖音原样。

> 当前版本：**0.5.5**（versionCode 2505）
>
> 下载：**[GitHub Releases](https://github.com/x59420/douyin-liquid-glass/releases)**
>
> 推送到 `main` 且构建成功后，GitHub Actions 会自动出包并发版。

---

## 这一版改了什么（0.5.5）

1. **系统导航栏透明真正生效**
   以前只调了 `setNavigationBarColor(TRANSPARENT)`，但窗口没带 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`，系统直接忽略 —— 设了等于没设。现在补上这个 flag，并关掉 `setNavigationBarContrastEnforced`（手势导航下那层对比度灰底）。

2. **被宿主改回去会自动重贴**
   抖音在 `onResume`、切 tab、进出全屏之后会把自己的栏色和 ui flags 整段写回去。现在这套逻辑是幂等的，轮询里每 600ms 复查一次，被改回去就重新设为透明。

3. **隐藏状态栏以后不再黑一条**
   状态栏隐藏时把它设成透明，并补 `LAYOUT_FULLSCREEN` 让内容真的画到那一块；同时清掉抖音铺在顶部的那层从黑到透明的渐变。**只在宿主自己隐藏状态栏时才动**，主界面不碰（否则会凭空多一条黑边）。从全屏退回来时，把我们加的透明和内容延伸收回去，用的还是抖音原来那份颜色。

4. **底部原生菜单 / 底部背景不透明**
   底栏外壳往上找 6 层，清掉「够宽、不高、落在屏幕下半部」那些层的 `background` 和 `foreground`；另外单独找盖在底栏上的渐变压暗层（判据：盖住底栏、≥0.8 屏宽、≤0.35 屏高、没有可见文字、没有可点控件 —— 纯装饰层正好全中，列表和评论区不会误伤）。每 1.2 秒复查，宿主重建底栏后重新铺的底再清一遍。

5. **Dock 跟随原生底栏显隐**
   - 跟得及时：宿主藏底栏，以前 400ms 才认，现在 160ms；
   - 误判能自己退出来：原来「疑似误报 → 本次运行永久关掉这条判据」，导致之后宿主真藏底栏时 Dock 还杵在那儿；现在底栏脱离窗口或页面被整屏盖住就恢复；
   - 不再被自己收掉：底栏设成 GONE 后外壳可能跟着缩小，这种「尺寸塌陷但仍标着可见」的层不算宿主行为。

> Dock 的**位置**照旧钉死不动（滚动、底栏位移都不跟），只有显隐跟原生走。

---

## 功能

- 四个导航图标装在一块长条玻璃里，右边是独立的圆形「＋」，整组水平居中
- 默认 64dp 高、每格 76dp 宽、左右 24dp 安全边距、离底 8dp（都可在设置里调）
- 玻璃背景实时采 Dock 背后那块真实画面（内容树 + 视频层），交给 Miuix 的 blur / lens 渲染
  - Android 13+：真折射（RuntimeShader）
  - Android 8–12：磨砂回退，没有折射与泛光
- 未读角标、按压泛光、左右拖动 + 弹簧回位
- 点击转发给抖音原生导航项（取的是当前底栏节点，宿主重建后照样点得动）
- 长按视频倍速、双指清屏、评论页、他人主页、整屏覆盖页、展开浮层时自动收起

## 安装

1. 从 [Releases](https://github.com/x59420/douyin-liquid-glass/releases) 下载最新 APK
2. 安装，LSPosed 里启用 **dydock**
3. 作用域勾选抖音（`com.ss.android.ugc.aweme`）
4. 强制停止抖音，重新打开

**设置入口**：抖音「我的」→ 右上角菜单 → **dydock**（在「设置」左边）。可调启用开关、「＋」、文字标签、尺寸、日志、恢复默认；改完一秒内生效，不用重开抖音。

## 只改这些地方

| 区域 | 行为 |
| --- | --- |
| 四个主 tab 的底部导航栏 | 替换成液态玻璃 Dock |
| 顶栏、搜索栏、信息流、评论区、聊天页、个人页、弹窗 | 保持抖音原样 |
| 底部深色底 / 渐变遮罩、状态栏 / 导航栏 | 透明化（见上文） |
| 长按倍速、清屏、评论页、整屏覆盖页 | Dock 收起，退出后恢复 |

原生 Dock 始终隐藏，只用于转发点击和读角标。

> 抖音会持续更新，内部结构可能变化；识别逻辑不保证兼容所有版本，效果需在目标设备上验证。

## 系统要求

- Android 8.0（API 26）及以上
- LSPosed（Xposed API 82 或兼容接口）
- 抖音 Android 客户端

## 构建

需要 JDK 21、Gradle 9.7.1、Android SDK Platform `android-37.0` + Build Tools `37.0.0`。

```bash
ANDROID_HOME=/path/to/android-sdk bash build.sh
```

`build.sh` 会补 Xposed API jar、取回签名口令、跑 `assembleRelease`，产物在 `out/dydock-<版本>.apk`。

三个必须注意的点：

- `iso/**` 是按名字反射加载的，`app/proguard-rules.pro` 里要 `-keep`（连它引用的 `ksu/**`），否则 R8 会把玻璃层裁光，表现是「装了没反应」。
- `versionCode` 必须大于设备上已装的版本，否则安卓以「降级」为由拒绝覆盖安装。
- `keystore/dydock.jks` 必须一直用同一份，换了就不能覆盖安装。

## 版本历史

| 版本 | 要点 |
| --- | --- |
| 0.5.5 | 状态栏 / 导航栏透明真正生效；清底部黑底与顶部色带；Dock 跟随原生底栏显隐 |
| 0.5.4 | 视频页不再黑玻璃；只保留「我的」菜单一个入口 |
| 0.5.3 | 设置改完立刻生效；入口固定在「设置」左边 |
| 0.5.2 | 菜单入口简化为纯文字 `dydock` |
| 0.5.1 | 设置界面去彩虹色；评论区判定收窄；入口不盖原生项 |
| 0.5.0 | 入口搬到「我的」菜单；整屏设置界面；显隐统一、位置钉死 |
| 0.4.3 | 点当前 tab 也能触发原生行为；背景采样按指纹判重 |
| 0.4.2 | 背景不再整窗截屏，改为内容树 + 视频层采样 |
| 0.4.1 | 修「Dock 出来一下就消失」 |
| 0.4.0 | 基于 0.3.3 重做（0.3.4 / 0.3.5 作废）：位置、显隐、黑背景 |
| 0.3.x | Compose + Miuix 渲染器重写、崩溃防护、图标与手势 |
| 0.2.x | 修闪退（主题控制器重建、位图回收后绘制）、体积压缩 |
| 0.1.x | 首个可用版本 |

## 开源致谢

- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）：折射着色器、高光、色散、弹簧动画
- [lengroo/pdd-LiquidGlass](https://github.com/lengroo/pdd-LiquidGlass)（MIT）：只接管指定区域、用宿主内容当玻璃背景的思路
- [tiann/KernelSU](https://github.com/tiann/KernelSU)：`FloatingBottomBar` 及 `liquid/*`、`animation/*`、`modifier/*` 的移植来源（见 [NOTICE-KERNELSU](NOTICE-KERNELSU.md)）
- [compose-miuix-ui](https://github.com/miuix-kotlin-multiplatform/compose-miuix-ui)：KernelSU 那份底栏改编自其 `IosLiquidGlassNavigationBar` 示例（Apache-2.0）

液态玻璃相关的折射、色散、内阴影、泛光、拖动阻尼等效果均来自上述项目，**版权归原作者**。

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。第三方衍生部分的版权与许可见 [NOTICE](NOTICE) / [NOTICE-KERNELSU](NOTICE-KERNELSU.md)。

本项目仅供学习、研究与个人**非商业**用途；为非官方第三方模块，与字节跳动及抖音官方无隶属关系。「抖音」及相关商标归其各自所有者所有。修改第三方应用可能因宿主更新而失效，也可能带来兼容性或账号方面的风险，请自行判断是否使用。
