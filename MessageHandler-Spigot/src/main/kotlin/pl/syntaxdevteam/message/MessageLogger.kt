package pl.syntaxdevteam.message

/**
 * Minimal logging abstraction used by [MessageHandler] to report state changes.
 */
interface MessageLogger {
    fun success(message: String)
    fun err(message: String)

    companion object {
        val NO_OP: MessageLogger = object : MessageLogger {
            override fun success(message: String) = Unit
            override fun err(message: String) = Unit
        }
    }
}
