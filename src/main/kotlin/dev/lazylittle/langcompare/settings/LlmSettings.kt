package dev.lazylittle.langcompare.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "ProgrammingLangComparisonSettings", storages = [Storage("programming-lang-comparison.xml")])
class LlmSettings : PersistentStateComponent<LlmSettings.State> {

    class State {
        var baseUrl: String = "https://api.openai.com/v1"
        var model: String = "gpt-4o-mini"
        var sourceLanguage: String = ""
        var targetLanguage: String = "Java"
        var autoTranslate: Boolean = true
        var debounceMillis: Int = 500
        var streaming: Boolean = true
        var proxy: String = ""

        /** Rough cap on prompt size; 0 (or less) disables the check. */
        var maxInputTokens: Int = 4000

        /**
         * Sends GLM-style "thinking": {"type": "disabled"} to cut time-to-first-token.
         * User-controlled because strict providers (e.g. OpenAI) reject unknown fields.
         */
        var disableThinking: Boolean = true
    }

    // The API key is a secret: keep it in the IDE credential store, not in the XML state file.
    private val apiKeyAttributes = CredentialAttributes("PROGRAMMING_LANG_COMPARISON_API_KEY")

    @JvmField
    var state: State = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun targetLanguage(): String = state.targetLanguage.trim().ifEmpty { "Java" }

    private val passwordSafe: PasswordSafe
        get() = ApplicationManager.getApplication().getService(PasswordSafe::class.java)

    // Cached after the first read so frequent editor events never hit the credential store per event.
    @Volatile private var cachedApiKey: String? = null
    @Volatile private var apiKeyLoaded = false

    var apiKey: String?
        get() {
            if (!apiKeyLoaded) {
                cachedApiKey = passwordSafe.get(apiKeyAttributes)?.getPasswordAsString()
                apiKeyLoaded = true
            }
            return cachedApiKey
        }
        set(value) {
            if (value.isNullOrBlank()) {
                passwordSafe.set(apiKeyAttributes, null)
            } else {
                passwordSafe.set(apiKeyAttributes, Credentials(apiKeyAttributes.serviceName, value))
            }
            cachedApiKey = if (value.isNullOrBlank()) null else value
            apiKeyLoaded = true
        }

    companion object {
        val instance: LlmSettings
            get() = ApplicationManager.getApplication().getService(LlmSettings::class.java)
    }
}
