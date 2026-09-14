# Programming Language Comparison

在编辑器里选中一段代码，由任意 OpenAI 格式的 LLM 实时类比成目标语言（Python → Java、Go → Rust ……）。同一套能力提供两个编辑器插件实现：

| 目录 | 编辑器 | 技术栈 | 文档 |
| --- | --- | --- | --- |
| [jetbrains/](jetbrains/) | IntelliJ IDEA / PyCharm 等 JetBrains IDE | Kotlin + IntelliJ Platform SDK | [中文](jetbrains/README.md) · [English](jetbrains/README_EN.md) |
| [vscode/](vscode/) | VS Code | TypeScript + VSCode Extension API | [中文](vscode/README.md) |

两个插件共享同一套设计：OpenAI 格式 `chat/completions` 协议 + SSE 流式渲染、`thinking: disabled` 关思考模式、输入 token 上限、选区防抖与结果缓存。JetBrains 版在选区正下方以块级 inlay 显示结果；VS Code 版以评论线程显示在选区末行下方。

## GitHub 自动化

仓库内置三个 GitHub Actions 工作流（定义在 [.github/workflows/](.github/workflows/)）：

| 工作流 | 触发条件 | 作用 |
| --- | --- | --- |
| `ci.yml` | push 到 `main`、所有 PR | 编译 JetBrains 插件（`buildPlugin`）与 VS Code 扩展（`tsc` + `vsce package`） |
| `release.yml` | 手动触发（Actions 页 Run workflow）或推送 `v*` 标签 | 校验两个子项目版本一致 → 构建 `*.zip`（JetBrains）与 `*.vsix`（VS Code）→ 自动发布 GitHub Release |
| `pr-review.yml` | PR 创建/更新 | 调用 OpenAI 格式 LLM 自动 review，结果以评论贴在 PR 上（同一 PR 只维护一条评论，持续更新） |

### 发布新版本

一次发布可以覆盖多个 PR，由维护人主动触发：

1. 把要发布的 PR 全部合并进 `main`；
2. 更新版本号并推到 `main`（单独一个小提交或 PR 即可）：`jetbrains/build.gradle.kts` 与 `vscode/package.json` 必须一致；
3. 到 GitHub **Actions → Release → Run workflow**（分支选 `main`），自动构建并发布 `v<版本号>` 的 Release，并在 main HEAD 上创建对应 git tag；
4. 也可以用命令行代替第 3 步：`git tag v0.1.0 && git push origin v0.1.0`（标签须为 `v + 版本号`）。

> 若版本对应的 Release 已存在（通常是忘了升版本号），工作流会直接报错拦下，不会覆盖历史发布。

### PR Review 配置

在仓库 **Settings → Secrets and variables → Actions** 中添加：

| Secret | 必填 | 说明 |
| --- | --- | --- |
| `LLM_API_KEY` | ✅ | OpenAI 兼容 API Key |
| `LLM_BASE_URL` | 可选 | 默认 `https://api.openai.com/v1`，如 `https://open.bigmodel.cn/api/paas/v4` |
| `LLM_MODEL` | 可选 | 默认 `gpt-4o-mini`，如 `glm-4.7-flash`、`deepseek-chat` |

> 出于安全考虑，fork 来源的 PR 不触发 LLM review（GitHub 不向 fork PR 注入 secrets）。review 逻辑见 [.github/scripts/pr_review.py](.github/scripts/pr_review.py)。

## 许可证

见 [LICENSE](LICENSE)。
