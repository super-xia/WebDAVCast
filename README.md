# WebDAVCast — WebDAV 投屏播放器 (Android)

手机上浏览 WebDAV 服务器里的视频，直接播放，还能一键投到电视上看。

## 下载

Release 里下 APK 直接装：[v1.0.2 APK](https://github.com/super-xia/WebDAVCast/releases/download/v1.0.2/WebDAVCast-1.0.2-release.apk)

## 用法

1. 首页填三项：**服务器清单页地址**、**WebDAV 用户名**、**WebDAV 密码**，点"查询可用的服务器"
2. 选一台服务器，进目录翻视频
3. 点视频进播放页：顶部 16:9 小窗 + 文件名，下面是投屏区
4. 点播放器右下角全屏按钮 → 横屏沉浸全屏看（纯画面、无文件名/按钮）；再点或按返回键退出，回到播放页原样

## 播放器

- **自绘控制条**：透明底板，白色矢量图标——音频 | 上一个 | 快退5s | 播放/暂停 | 快进5s | 下一个 | 倍速
- **倍速**：点击右侧 `1x` 循环切换 0.5x→0.75x→1x→1.25x→1.5x→2x
- **进度条**：点击跳转、拖动 seek（松手才定位，不卡顿）
- **手势**：左滑快退/右滑快进 10 秒；点视频显示/隐藏控制条，3 秒无操作自动隐藏
- **全屏**：三分区双击（左=退5s、中=暂停/播放、右=进5s）；左侧上下滑调亮度、右侧上下滑调音量
- **历史续播**：观看进度自动保存（每 5 秒），下次从上次位置继续；播放记录里点视频直达原目录

## 投屏

- 点"📺 搜索电视"自动发现局域网电视/盒子（SSDP），选中即投
- 没搜到点"手动"输电视 IP（`192.168.1.100` 或带端口）
- 直连模式：电视直接从 WebDAV 服务器拉流，退出 App 也能播，账号密码内嵌在投屏 URL 里

## 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleRelease
```

签名（release keystore 自备）：

```bash
B=$ANDROID_SDK/build-tools/34.0.0
$B/zipalign -f -p 4 app-release-unsigned.apk aligned.apk
$B/apksigner sign --ks <your.keystore> --out WebDAVCast-1.0.2-release.apk aligned.apk
```

## 环境

- compileSdk/targetSdk 34，minSdk 26
- Kotlin + Jetpack Compose (Material3) + Media3 1.3.1 (ExoPlayer + OkHttpDataSource)
- 首页输入不预置，打开是空的，自己填；填过的地址账号会记住
- 应用图标：品牌蓝底 + 播放三角 + 投屏波纹

## 文件结构

- `MainActivity.kt` — 首页/浏览/播放/历史四页 + 全屏与手势逻辑
- `CtrlIcons.kt` — 控制条白色矢量图标
- `AddressResolver.kt` — 清单页解析 `host:port/path,名称`
- `WebDavClient.kt` — Basic 认证 PROPFIND 目录树
- `DlnaCaster.kt` / `DlnaDevice.kt` — SSDP 发现 + 直连投屏
- `Prefs.kt` — SharedPreferences（清单地址/账号/服务器缓存/播放历史）