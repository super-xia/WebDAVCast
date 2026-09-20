# WebDAVCast — WebDAV 投屏播放器 (Android)

手机上浏览 WebDAV 服务器里的视频，直接播放，还能一键投到电视上看。

## 功能

- **浏览**：WebDAV 目录树翻视频，文件列表直接显示每个已看视频的播放进度
- **选集 / 上下集**：播放页列出当前目录所有视频，点选快速切集；控制条上一集/下一集跳相邻
- **播放器**：自绘透明控制条、倍速（0.5x~2x）、进度条点击跳转/拖动 seek
- **手势**：左右滑动快进快退 10 秒；全屏双击三分区（左退5s/中暂停/右进5s）、左侧竖滑调亮度、右侧竖滑调音量
- **全屏**：横屏沉浸式；控制条 3 秒无操作自动隐藏，隐藏后右上角显示系统时间
- **历史续播**：观看进度自动保存，下次续播；历史记录按目录聚合（同一目录只显示最后观看一条），点击直达原目录
- **投屏**：SSDP 自动发现局域网电视/盒子，支持手动输 IP，直连模式（电视直接从服务器拉流）

## 用法

1. 首页填三项：**服务器清单页地址**、**WebDAV 用户名**、**WebDAV 密码**，点"查询可用的服务器"
2. 选一台服务器，进目录翻视频
3. 点视频进播放页，点右下角全屏按钮 → 横屏沉浸全屏，再点或按返回键退出

## 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleRelease
```

release keystore 自备（项目未配置 keystore 时回退 debug 签名，保证公开仓库可编译）。

## 环境

- compileSdk/targetSdk 34，minSdk 26
- Kotlin + Jetpack Compose (Material3) + Media3 1.3.1 (ExoPlayer + OkHttpDataSource)

## 文件结构

- `MainActivity.kt` — 首页/浏览/播放/历史四页 + 全屏与手势逻辑
- `CtrlIcons.kt` — 控制条白色矢量图标
- `AddressResolver.kt` — 清单页解析 `host:port/path,名称`
- `WebDavClient.kt` — Basic 认证 PROPFIND 目录树
- `DlnaCaster.kt` / `DlnaDevice.kt` — SSDP 发现 + 直连投屏
- `Prefs.kt` — SharedPreferences（清单地址/账号/服务器缓存/播放历史）

## 相关项目

- 📺 [WebDAVCast TV（电视版）](https://github.com/super-xia/WebDAVCast-TV) — 遥控器电视端，同一套「清单页动态解析服务器地址 + WebDAV 浏览」核心逻辑，交互层换成遥控器焦点驱动
