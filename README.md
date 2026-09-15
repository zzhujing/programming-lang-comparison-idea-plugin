# Programming Language Comparison

选中一段代码，立刻看到它用另一种语言怎么写——Python → Java、Go → Rust、Node.js → Kotlin……全程不用离开编辑器，由任意 OpenAI 格式的 LLM 实时驱动。

![usage demo](jetbrains/docs/images/usage.gif)

## ✨ 它能做什么

- **选区实时类比**：选中代码即自动翻译，结果流式渲染在选区正下方；源语言自动检测，也可手动指定
- **目标语言语法高亮**：翻译结果按目标语言的真实词法着色，所见即所得
- **快**：结果缓存（同一段代码重复选中即时出）、默认关闭思考模式压低首 token 延迟、输入体积超限直接拦截、流式输出边生成边显示
- **接任意 OpenAI 格式服务**：bigmodel（GLM）、OpenAI、DeepSeek、Moonshot、Qwen、One-API/New-API 网关……只要 Base URL / API Key / Model 三件套
- **密钥安全**：API Key 存入系统凭据库（JetBrains）/ SecretStorage（VS Code），不写明文
- **工具窗口与代理**（JetBrains 版）：`LangCompare` 工具窗口同步展示、复制结果；可为 LLM 请求单独配代理

同一套能力提供两个编辑器实现：

| 目录 | 编辑器 | 技术栈 | 文档 |
| --- | --- | --- | --- |
| [jetbrains/](jetbrains/) | IntelliJ IDEA / PyCharm 等 JetBrains IDE | Kotlin + IntelliJ Platform SDK | [中文](jetbrains/README.md) · [English](jetbrains/README_EN.md) |
| [vscode/](vscode/) | VS Code | TypeScript + VSCode Extension API | [中文](vscode/README.md) |

## 📖 使用手册

### 1. 安装

两个版本都可以直接从 GitHub [Releases](https://github.com/zzhujing/programming-lang-comparison-idea-plugin/releases) 下载安装包。

**JetBrains（需 IDE 2025.1+）**：下载 `programming-lang-comparison-idea-plugin-<版本>.zip` → `Settings → Plugins → ⚙ → Install Plugin from Disk` 选中 zip → 重启 IDE。

**VS Code**：下载 `programming-lang-comparison-<版本>.vsix` → 扩展面板 `⋯ → Install from VSIX...`，或命令行 `code --install-extension <文件名>.vsix`。

### 2. 首次配置

| 入口 | JetBrains | VS Code |
| --- | --- | --- |
| 打开设置 | `Settings → Tools → Language Comparison` | 命令面板执行 `LangCompare: Open Settings` |
| 填 API Key | 设置页直接填写（存入 IDE 凭据库） | 命令面板执行 `LangCompare: Set API Key` |

必填三项（以 bigmodel 为例，换任何 OpenAI 兼容服务同理）：

| 配置 | 示例值 | 说明 |
| --- | --- | --- |
| Base URL | `https://open.bigmodel.cn/api/paas/v4` | OpenAI 兼容接口地址（含 `/v1`） |
| API Key | `xxxxxxxx` | 对应服务的密钥 |
| Model | `glm-5.3-flash` | 模型名，如 `deepseek-chat`、`gpt-4o-mini` |

再把 **Target language** 改成你想要的目标语言（默认 `Java`，可选 Go、Kotlin、Rust、TypeScript……），就可以开始用了。

### 3. 日常使用

1. **翻译**：在编辑器中选中一段代码，稍候结果自动出现在选区正下方（JetBrains 为块级 inlay，VS Code 为可折叠评论块）；若关闭了自动翻译，可右键 `Compare to Other Languages`（JetBrains）或在命令面板执行 `LangCompare: Translate Selection`（VS Code）
2. **查看与复制**：JetBrains 底部 `LangCompare` 工具窗口同步展示完整结果、方便复制；VS Code 评论块标题栏自带复制按钮
3. **清除结果**：右键 `Clear Language Comparison`（JetBrains）或命令面板 `LangCompare: Clear`（VS Code）
4. **切换目标语言**：改设置里的 Target language 即可，改完下次翻译立即生效

### 4. 常用配置速查

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| Target language | `Java` | 翻译成的目标语言 |
| Source language | 自动检测 | 手动指定源语言，如 `Python`、`Node.js` |
| Translate automatically | 开 | 选中即翻；关闭后仅手动触发 |
| Debounce (ms) | 500 | 自动触发防抖间隔（200–10000） |
| Streaming | 开 | 流式返回，边生成边显示 |
| Disable thinking | 开 | 请求附带 `thinking: disabled` 提速；服务商拒绝未知字段时关闭 |
| Max input tokens | 4000 | 输入体积上限（按 token 估算），超限直接拒绝；0 = 不限制 |
| HTTP Proxy（仅 JetBrains） | 继承 IDE | LLM 请求专用代理（如 `http://127.0.0.1:7890`）；`direct` 强制直连 |

更多调优技巧与常见问题（响应慢、TLS handshake、401、代理）见各版本文档：[jetbrains/README.md](jetbrains/README.md) · [vscode/README.md](vscode/README.md)

## 许可证

见 [LICENSE](LICENSE)。
