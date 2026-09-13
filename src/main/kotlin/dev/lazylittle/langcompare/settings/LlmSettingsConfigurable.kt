package dev.lazylittle.langcompare.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class LlmSettingsConfigurable : BoundConfigurable("Language Comparison") {

    private val apiKeyField = JBPasswordField()
    private val debounceField = JBTextField()
    private val autoBox = JBCheckBox("Translate automatically while selecting code")
    private val streamBox = JBCheckBox("Use streaming responses")

    override fun createPanel(): DialogPanel {
        val settings = LlmSettings.instance
        val st = settings.state
        apiKeyField.text = settings.apiKey ?: ""
        debounceField.text = st.debounceMillis.toString()
        autoBox.isSelected = st.autoTranslate
        streamBox.isSelected = st.streaming
        return panel {
            group("LLM (OpenAI-compatible API)") {
                row("Base URL:") {
                    textField().bindText(st::baseUrl)
                        .comment(
                            "OpenAI-compatible chat endpoint base, e.g. https://api.openai.com/v1 — " +
                                "works with DeepSeek, Moonshot, Qwen, Ollama, One-API/New-API gateways, ..."
                        )
                }
                row("API Key:") { cell(apiKeyField).resizableColumn() }
                row("HTTP Proxy:") {
                    textField().bindText(st::proxy)
                        .comment(
                            "Optional. 'http://host:port' to use a proxy (for api.openai.com etc.), " +
                                "'direct' to force no proxy (use when a domestic endpoint like open.bigmodel.cn " +
                                "is hijacked by IDE/system proxy). Empty = inherit IDE/system proxy settings."
                        )
                }
                row("Model:") {
                    textField().bindText(st::model)
                        .comment("e.g. gpt-4o-mini, deepseek-chat, qwen-plus, ...")
                }
                row {
                    comment(
                        "Note: a ChatGPT (Plus/Pro) subscription cannot be used as an API directly. " +
                            "Expose it through an OpenAI-compatible gateway (e.g. One-API/New-API) and fill its Base URL here."
                    )
                }
            }
            group("Translation Targets") {
                row("Source language:") {
                    textField().bindText(st::sourceLanguage)
                        .comment("Optional override, e.g. Python, Node.js. Empty = auto-detect from the file.")
                }
                row("Target languages:") {
                    textField().bindText(st::targetLanguages)
                        .comment("Comma separated list, e.g. Java, Go, Kotlin, TypeScript, Rust")
                }
                row { cell(autoBox) }
                row("Debounce (ms):") { cell(debounceField) }
                row { cell(streamBox) }
            }
        }
    }

    override fun isModified(): Boolean {
        val st = LlmSettings.instance.state
        return super.isModified() ||
            String(apiKeyField.password) != (LlmSettings.instance.apiKey ?: "") ||
            debounceField.text.trim().toIntOrNull() != st.debounceMillis ||
            autoBox.isSelected != st.autoTranslate ||
            streamBox.isSelected != st.streaming
    }

    override fun apply() {
        super.apply()
        val settings = LlmSettings.instance
        settings.apiKey = String(apiKeyField.password)
        settings.state.debounceMillis =
            debounceField.text.trim().toIntOrNull()?.coerceIn(200, 10_000) ?: settings.state.debounceMillis
        settings.state.autoTranslate = autoBox.isSelected
        settings.state.streaming = streamBox.isSelected
    }

    override fun reset() {
        super.reset()
        val st = LlmSettings.instance.state
        apiKeyField.text = LlmSettings.instance.apiKey ?: ""
        debounceField.text = st.debounceMillis.toString()
        autoBox.isSelected = st.autoTranslate
        streamBox.isSelected = st.streaming
    }
}
