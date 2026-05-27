# 用户反馈巡检流程

MyLight 的 App 内「意见反馈」会创建带 `feedback` 标签的 GitHub Issue。

`.github/workflows/feedback-triage.yml` 会每天汇总未关闭的反馈 Issue，并创建一个 `feedback-triage` 巡检 Issue。Agent 被唤起后应读取巡检 Issue，逐条判断反馈是否合理。

处理规则：

1. 合理反馈：给出优化方案，按优先级实现、编译、更新 APK、提交并推送。
2. 需要澄清：在原反馈 Issue 下回复需要补充的信息。
3. 暂不处理：说明原因，例如与产品方向不符、不可复现、成本过高或已有替代流程。
4. 已处理：在原反馈 Issue 下关联 commit 和新版 APK 下载地址，然后关闭 Issue。
