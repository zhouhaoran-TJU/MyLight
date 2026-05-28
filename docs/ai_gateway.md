# MyLight AI 网关协议

MyLight Android 客户端不会内置大模型 API Key。App 会把低分辨率 JPEG、当前调色参数和用户指令 POST 到你配置的后端网关，由后端调用 OpenAI、Gemini、Claude 或其他多模态模型。

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
