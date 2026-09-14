# Programming Language Comparison

在编辑器里选中一段代码，由任意 OpenAI 格式的 LLM 实时类比成目标语言（Python → Java、Go → Rust ……）。同一套能力提供两个编辑器插件实现：

| 目录 | 编辑器 | 技术栈 | 文档 |
| --- | --- | --- | --- |
| [jetbrains/](jetbrains/) | IntelliJ IDEA / PyCharm 等 JetBrains IDE | Kotlin + IntelliJ Platform SDK | [中文](jetbrains/README.md) · [English](jetbrains/README_EN.md) |
| [vscode/](vscode/) | VS Code | TypeScript + VSCode Extension API | [中文](vscode/README.md) |

两个插件共享同一套设计：OpenAI 格式 `chat/completions` 协议 + SSE 流式渲染、`thinking: disabled` 关思考模式、输入 token 上限、选区防抖与结果缓存。JetBrains 版在选区正下方以块级 inlay 显示结果；VS Code 版以评论线程显示在选区末行下方。

## 许可证

见 [LICENSE](LICENSE)。
