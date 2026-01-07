package pl.syntaxdevteam.message

/**
 * Provides metadata describing the plugin or application using the [MessageHandler].
 */
interface PluginMetaProvider {
    /**
     * Przyjazna nazwa pluginu wykorzystywana m.in. jako prefiks wiadomości.
     */
    val name: String
}
