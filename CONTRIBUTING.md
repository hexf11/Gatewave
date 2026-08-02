# 贡献指南

感谢你参与 Gatewave。

## 开始之前

1. 先搜索现有议题，避免重复提交。
2. 功能建议请说明使用场景、预期行为和可能影响。
3. 安全问题请按照 `SECURITY.md` 私下报告。

## 本地开发

```bash
git clone https://github.com/hexf11/Gatewave.git
cd Gatewave
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
```

项目要求 JDK 17、Android SDK 36.1 和 Build Tools 36.1.0。

## 提交要求

- 保持 Kotlin 风格一致，避免引入无关格式化。
- 涉及代理、VPN、订阅或权限的改动需要说明威胁模型。
- 新功能应补充验证步骤；可测试逻辑应补充自动化测试。
- 提交前确保 Lint、单元测试和 Debug 构建通过。
- 提交信息应简洁说明改动目的。

## 拉取请求

拉取请求应包含：

- 改动背景和范围。
- 用户可见变化的截图或录屏。
- 已执行的验证命令和结果。
- 兼容性、隐私或安全影响。
