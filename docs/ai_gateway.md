# MyLight AI 网关协议

MyLight 支持三种 AI 模式：

- 本地模式：不需要网络和 API Key，根据图片统计信息和用户关键词在 App 内生成调色参数。
- 网关模式：App 把低分辨率 JPEG、当前调色参数和用户指令 POST 到你配置的后端网关，由后端调用 OpenAI、Gemini、Claude 或其他多模态模型。
- 开发者直连：App 直接调用 OpenAI Responses API 或 Gemini `generateContent` REST API。这个模式会把 API Key 保存在本机，仅建议自测，不建议正式分发默认开启。

正式分发仍推荐使用网关模式，避免在客户端暴露 API Key。

## 请求

方法：`POST`

请求头：

```http
Content-Type: application/json; charset=utf-8
Accept: application/json
X-MyLight-Client: android
```

主要字段：

```json
{
  "action": "auto_enhance | natural_edit | generate_filter",
  "prompt": "用户自然语言修图要求",
  "schemaVersion": 1,
  "current": {
    "geometry": {},
    "adjustments": {},
    "curves": []
  },
  "imageStats": {
    "averageLuminance": 0.48,
    "averageSaturation": 0.36,
    "darkRatio": 0.03,
    "brightRatio": 0.01
  },
  "image": {
    "mime": "image/jpeg",
    "width": 540,
    "height": 386,
    "base64": "..."
  }
}
```

## 响应

后端只返回 JSON，不返回解释性 Markdown。

```json
{
  "name": "清透人像",
  "message": "已提亮肤色并压低高光",
  "adjustments": {
    "exposure": 0.08,
    "brightness": 0.03,
    "highlights": -0.18,
    "shadows": 0.14,
    "contrast": 0.08,
    "saturation": 0.05,
    "temperature": 0.03,
    "tint": 0.02,
    "fade": 0.04,
    "vignette": -0.08,
    "dehaze": 0.03,
    "ambiance": 0.1,
    "sharpness": 0.08,
    "noiseReduction": 0.12,
    "grain": 0.02,
    "mixHue": [0, 0, 0, 0, 0, 0, 0, 0],
    "mixSaturation": [0.02, 0.04, 0, 0, 0, 0, 0, 0],
    "mixLuminance": [0.02, 0.05, 0, 0, 0, 0, 0, 0]
  },
  "curves": [
    [[0, 0], [64, 68], [128, 134], [192, 202], [255, 255]],
    [[0, 0], [255, 255]],
    [[0, 0], [255, 255]],
    [[0, 0], [255, 255]]
  ]
}
```

客户端会对所有数值做范围裁剪，并继续用本地 GPU shader 实时渲染。

## 开发者直连

OpenAI：

- Provider：`OpenAI`
- Model 示例：`gpt-4.1-mini`
- App 会请求：`https://api.openai.com/v1/responses`
- 图片以 `data:image/jpeg;base64,...` 形式放入 `input_image`

Gemini：

- Provider：`Gemini`
- Model 示例：`gemini-1.5-flash`
- App 会请求：`https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
- 图片以 `inline_data` 形式传入

MiMo：

- Provider：`MiMo`
- Model 示例：`mimo-v2-omni`
- App 会请求：`https://api.mimo-v2.com/v1/chat/completions`
- 鉴权同时发送 `Authorization: Bearer {API Key}` 和 `api-key: {API Key}`
- 图片以 OpenAI-compatible `image_url` data URL 形式传入
- 输出长度参数使用 `max_completion_tokens`

MiMo 百炼：

- Provider：`MiMo百炼`
- Model 示例：`xiaomi/mimo-v2.5-pro`
- App 会请求：`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
- 鉴权发送 `Authorization: Bearer {API Key}`
- 图片以 OpenAI-compatible `image_url` data URL 形式传入

直连模式只接受模型返回 JSON。若模型返回 Markdown、解释性文字或非 JSON 内容，客户端会尝试提取第一个 JSON 对象，失败时提示“AI 返回格式不正确”。

直连模式会按 `Provider + Model` 分别保存 API Key。切换 Provider 或模型后，App 会自动加载对应保存过的 Key；也可以使用：

- 保存API：把当前输入的 Key 保存到当前 Provider 和模型下。
- 选择API：从所有已保存 Key 中切换，选择后同步切换 Provider、模型和 Key。
- 清除API：清除当前 Provider 和模型下保存的 Key，并清空输入框。
