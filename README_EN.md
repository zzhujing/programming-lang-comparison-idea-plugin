<div align="right">

[中文](README.md) | English

</div>

# Programming Language Comparison

Compare code across programming languages without leaving your JetBrains IDE: select a snippet (Python → Java, Go → Java, Node.js → Java …) and see the equivalent code translated in real time by any OpenAI-format LLM.

![usage demo](docs/images/usage.gif)

## ✨ Features

- **Real-time comparison**: select code and the translation streams in right below the selection; source language is auto-detected, or set it manually
- **Comments stripped**: the translated output contains no comments — pure code only
- **Parallel target languages**: Java / Go / Kotlin / TypeScript … all at once; results are cached, so re-selecting is instant
- **Any OpenAI-format service**: bigmodel, OpenAI, DeepSeek, Moonshot, Qwen, One-API/New-API gateways, …
- **Tool window mirror**: the `LangCompare` tool window shows per-language tabs for easy copying
- **Proxy support**: configure a proxy in the plugin, or inherit the IDE/system proxy

## 📦 Install

```bash
./gradlew buildPlugin
# Artifact: build/distributions/programming-lang-comparison-idea-plugin-<version>.zip
```

In the IDE: `Settings → Plugins → ⚙ → Install Plugin from Disk` and pick the zip.

> Requires IntelliJ Platform 2025.1+. The first build downloads an IDEA Community distribution and takes a while.

## 🚀 Usage

1. Open `Settings → Tools → Language Comparison` and fill in **Base URL / API Key / Model**
2. Select any code in the editor — it translates automatically; or right-click → `Compare to Other Languages`
3. Clear the selection or right-click → `Clear Language Comparison` to remove the blocks
4. Open the bottom `LangCompare` tool window to view and copy full translations

> Shortcuts: unbound by default — assign keystrokes to `Compare to Other Languages` / `Clear Language Comparison` in `Settings → Keymap` (e.g. `⌃⌘Z` / `⌃⌘X`).

## ⚙️ Configuration

| Option | Description                                                                                                               |
| --- |---------------------------------------------------------------------------------------------------------------------------|
| Base URL | OpenAI-compatible endpoint base (with `/v1`), e.g. `https://open.bigmodel.cn/api/paas/v4`, `https://api.openai.com/v1`    |
| API Key | The service's key (stored in the IDE credential store, never in plain text)                                               |
| Model | Model name, e.g. `glm-5.3-flash`,  `deepseek-flash`                                                                       |
| HTTP Proxy | Optional proxy for LLM requests (e.g. `http://127.0.0.1:7890`); `direct` forces no proxy; empty inherits IDE/system proxy |
| Source language | Optional source-language override, e.g. `Python`, `Node.js`; empty = auto-detect                                          |
| Target languages | Comma-separated targets, e.g. `Java, Go, Kotlin, TypeScript, Rust`                                                        |
| Translate automatically | Translate while selecting (when off, manual trigger only)                                                                 |
| Debounce (ms) | Debounce interval for auto trigger (200–10000)                                                                            |
| Use streaming responses | Stream tokens as they are generated                                                                                       |

## ❓ FAQ

- **Slow responses?** Switching to a lighter model is the biggest win (e.g. `glm-5.3-flash`); keep selections small; the plugin already streams, runs targets in parallel, caches results and reuses connections.
- **TLS handshake failed?** The error now includes the actual connection mode `[connection: ...]`. If a directly reachable service is being routed through a proxy, fill `direct`; if the service needs a proxy (e.g. api.openai.com), configure HTTP Proxy.
- **ChatGPT subscription?** A ChatGPT subscription cannot be used as an API directly — expose it through a One-API/New-API-style gateway and use its Base URL.
- **401 / model not found?** Check the API key permissions and that the model name matches the Base URL.

## 🛠️ Development

```bash
./gradlew runIde   # launch a sandbox IDE with the plugin
```

- Requirements: JDK 21; IntelliJ Platform Gradle Plugin 2.5.0 + platform 2025.1
- Layout: `editor/` selection watching & orchestration (core), `llm/` OpenAI-format client (SSE streaming), `settings/` state & Settings UI, `ui/` tool window, `actions/` editor popup actions
