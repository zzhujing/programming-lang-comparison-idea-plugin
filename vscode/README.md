# Programming Language Comparison for VS Code

在 VS Code 里选中一段代码，由任意 OpenAI 格式的 LLM 实时类比成目标语言（Python → Java、Go → Rust ……），流式显示在选区末行下方的可折叠评论块中。与 [JetBrains 版](../jetbrains/)共享同一套协议与逻辑设计。

## ✨ 功能

- **选区实时类比**：选中代码自动防抖触发，也可手动执行命令；源语言自动检测，也可手动指定
- **代码下方展示**：结果以带语法高亮的 Markdown 代码块显示在选区末行正下方，可折叠
- **流式渲染**：边生成边显示（约 120ms 节流刷新）
- **关闭思考模式**：默认在 OpenAI 格式请求中直接附带 `thinking: disabled`（bigmodel/GLM 支持），首 token 显著加快
- **输入体积上限**：超过 `maxInputTokens` 的选区在发请求前直接拒绝，避免又慢又贵
- **结果缓存**：同一段代码重复选中即时出结果
- **任意 OpenAI 格式服务**：bigmodel、OpenAI、DeepSeek、Moonshot、Qwen、One-API/New-API 网关等
- **API Key 安全存储**：存放在 VS Code SecretStorage，不写进 settings.json

> 与 JetBrains 版的差异：VS Code 没有块级 inlay API，结果显示为选区下方的评论线程；暂未内置代理设置（透明代理/TUN 场景无需配置）。

## 📦 构建

```bash
cd vscode
npm install
npm run compile          # 类型检查 + 产出 out/
npx @vscode/vsce package # 产出 programming-lang-comparison-<version>.vsix
```

安装：VS Code 扩展面板 `⋯ → Install from VSIX...`，或 `code --install-extension programming-lang-comparison-0.1.0.vsix`。

调试：`cd vscode && npm install` 后用 VS Code 打开 `vscode/` 目录，按 `F5` 启动扩展开发宿主。

## 🚀 使用

1. 命令面板执行 `LangCompare: Open Settings`，会打开 VS Code 统一设置页并过滤出本扩展的全部配置（Base URL / Model / Target Language 等）；再执行 `LangCompare: Set API Key` 填入密钥
2. 选中一段代码，稍候自动翻译，结果出现在选区末行下方；也可命令面板执行 `LangCompare: Translate Selection`
3. `LangCompare: Clear` 清除结果；评论块标题栏的复制按钮可一键复制

## ⚙️ 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `langcompare.baseUrl` | `https://api.openai.com/v1` | OpenAI 兼容接口地址（含 `/v1`），如 `https://open.bigmodel.cn/api/paas/v4` |
| `langcompare.model` | `gpt-4o-mini` | 模型名，如 `glm-5.3-flash` |
| `langcompare.targetLanguage` | `Java` | 单一目标语言 |
| `langcompare.sourceLanguage` | 空 | 源语言覆盖；留空按文件语言自动检测 |
| `langcompare.autoTranslate` | `true` | 选中代码时自动翻译 |
| `langcompare.debounceMillis` | `500` | 自动触发防抖间隔（200–10000） |
| `langcompare.streaming` | `true` | 流式返回，边生成边显示 |
| `langcompare.disableThinking` | `true` | 请求附带 `thinking: disabled`；服务商拒绝未知字段时关闭 |
| `langcompare.maxInputTokens` | `4000` | 输入 token 估算上限，超出直接拒绝；0 = 不限制 |

## ❓ FAQ

- **响应慢？** 换轻量模型 + 保持 `disableThinking` 开启是最大的提速手段；选区精简，超过上限会被拒绝。
- **401 / model not found？** 检查 API Key 权限，以及模型名与 Base URL 是否配套。
- **需要代理？** 当前版本未内置代理设置，使用系统级/TUN 代理即可。

## 🛠️ 开发

- TypeScript + esbuild-free（`tsc` 直出），零运行时依赖，Node 18+ 内置 `fetch`
- 结构：`src/llm.ts` OpenAI 格式客户端（SSE 流式、token 估算、代码围栏提取）、`src/extension.ts` 选区监听与编排（结果以评论线程展示在代码下方）
