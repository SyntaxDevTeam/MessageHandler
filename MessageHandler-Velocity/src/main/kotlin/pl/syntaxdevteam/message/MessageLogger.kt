package pl.syntaxdevteam.message

/**
 * Minimal logging abstraction used by [MessageHandler] to report state changes.
 */
interface MessageLogger {
    /**
     * Loguje komunikat sukcesu/informacyjny.
     */
    fun success(message: String)

    /**
     * Loguje komunikat błędu.
     */
    fun err(message: String)

    companion object {
        val NO_OP: MessageLogger = object : MessageLogger {
            override fun success(message: String) = Unit
            override fun err(message: String) = Unit
        }
    }
}
