# MyLight 发布检查清单

## 首次上架前

1. 确认正式包名：`com.zhouhaoran.mylight`。
2. 创建 release keystore，并妥善备份。
3. 复制 `release-signing.properties.example` 为 `release-signing.properties`，填入本地签名信息。
4. 准备隐私政策公开 URL，可使用 `docs/privacy-policy.md` 内容发布到 GitHub Pages 或其他静态页面。
5. 准备应用图标、5 张以上手机截图、应用介绍、权限说明。
6. 国内应用市场按要求完成开发者实名认证和 App 备案。

## 构建命令

生成 release APK：

```bash
./gradlew :app:assembleRelease
```

生成 Google Play 推荐的 AAB：

```bash
./gradlew :app:bundleRelease
```

当前 beta 固定下载包：

```bash
./gradlew :app:assembleBeta
cp app/build/outputs/apk/beta/app-beta.apk dist/MyLight-beta.apk
```

## 发版规则

- 每次发布必须递增 `versionCode`。
- 同步更新 `versionName` 和 `dist/version.json`。
- 更新 `dist/MyLight-beta.apk` 后再提交推送。
- 国内市场重点检查隐私弹窗、权限说明、安装包更新权限说明。
