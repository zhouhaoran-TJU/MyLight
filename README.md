# LightroomAndroid

一个无第三方运行时依赖的原生 Android 图片调色 App 原型。启动后默认打开相册选择图片，功能按类似 Lightroom 的分区组织：尺寸、色彩、曲线、效果。所有滑杆和曲线拖动都会实时触发预览渲染。

## 功能

- 尺寸：自由裁剪、原图比例、1:1、4:3、16:9、任意角度旋转、左/右 90 度旋转。
- 色彩：明亮度、高光、阴影、对比度、饱和度、色温、色调、曝光。
- 原色 / HSL：红、橙、黄、绿、青、蓝、紫、洋红的色相、饱和度、明亮度。
- 曲线：亮度曲线、红色曲线、绿色曲线、蓝色曲线。
- 效果：晕影、去模糊、氛围、褪色。
- 预设：Clean、Vivid、Warm、Cool、Matte、Film、Mono。
- 图标：使用 `app/src/images/screenshot-20260525-202118.png` 生成各密度 launcher icon。

## 构建

已在项目内准备隔离构建环境后，可执行：

```bash
./build_debug_apk.sh
```

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Web 调试页：

```text
http://127.0.0.1:8765/web-debug/
```

也可用 Android Studio 直接打开本目录，或在安装 Android SDK 与 Gradle 后执行：

```bash
gradle :app:assembleDebug
```

工程使用 Android Gradle Plugin 7.4.2，要求本机可访问 `google()` 和 `mavenCentral()`。
