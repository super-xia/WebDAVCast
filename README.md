# WebDAVCast — WebDAV 投屏播放器 (Android)

手机上浏览 WebDAV 服务器里的视频，直接播放，还能一键投到电视上看。

## 下载

Release 里下 APK 直接装：[v1.0 APK](https://github.com/super-xia/WebDAVCast/releases/download/v1.0/WebDAVCast-1.0-release.apk)

## 用法

1. 首页填三项：**服务器清单页地址**、**WebDAV 用户名**、**WebDAV 密码**，点"查询可用的服务器"
2. 选一台服务器，进目录翻视频
3. 点视频进播放页：顶部 16:9 小窗 + 文件名，下面是投屏区
4. 点播放器右下角全屏按钮 → 横屏沉浸全屏看；返回键或再点一次退出，回到播放页原样

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
$B/apksigner sign --ks <your.keystore> --out WebDAVCast-1.0-release.apk aligned.apk
```

## 环境

- compileSdk/targetSdk 34，minSdk 26
- Kotlin + Jetpack Compose (Material3) + Media3 1.3.1 (ExoPlayer + OkHttpDataSource)
- 首页输入不预置，打开是空的，自己填；填过的地址账号会记住

## 文件结构

- `MainActivity.kt` — 首页/浏览/播放三页 + 全屏逻辑
- `AddressResolver.kt` — 清单页解析 `host:port/path,名称`
- `WebDavClient.kt` — Basic 认证 PROPFIND 目录树
- `DlnaCaster.kt` / `DlnaDevice.kt` — SSDP 发现 + 直连投屏
- `Prefs.kt` — SharedPreferences（清单地址/账号/服务器缓存）
