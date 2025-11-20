package pl.syntaxdevteam.message

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

/**
 * Provides a convenient entry-point mirroring the historical `SyntaxCore.messages`
 * accessor so that plugins can use the standalone [MessageHandler] with minimal
 * boilerplate on Bukkit/Spigot platforms.
 */
object SyntaxMessages {
    @Volatile
    private var handler: MessageHandler? = null

    /**
     * Returns the globally configured [MessageHandler].
     *
     * @throws IllegalStateException if [configure] or [initialize] has not been called yet.
     */
    val messages: MessageHandler
        get() = handler ?: error("SyntaxMessages has not been initialized. Call initialize(...) first.")

    /**
     * Initializes the handler using default Bukkit integrations sourced from the
     * provided [plugin]. The handler is stored in [messages] for convenient reuse.
     */
    @JvmStatic
    fun initialize(plugin: JavaPlugin, logger: Logger = plugin.logger): MessageHandler {
        return configure(
            resources = BukkitResourceProvider(plugin),
            meta = SpigotPluginMetaProvider(plugin),
            logger = BukkitMessageLogger(logger)
        )
    }

    /**
     * Configures the global [messages] handler using custom abstractions.
     */
    @JvmStatic
    @Synchronized
    fun configure(
        resources: ResourceProvider,
        meta: PluginMetaProvider,
        logger: MessageLogger = MessageLogger.NO_OP
    ): MessageHandler {
        val messageHandler = MessageHandler(resources, meta, logger)
        messageHandler.initial()
        handler = messageHandler
        return messageHandler
    }

    /**
     * Reloads the underlying message files using the currently configured handler.
     */
    @JvmStatic
    fun reload() {
        messages.reloadMessages()
    }

    /**
     * Clears the current handler. Primarily intended for tests where multiple
     * configurations are required within the same JVM.
     */
    @JvmStatic
    @Synchronized
    fun reset() {
        handler = null
    }
}

private class BukkitResourceProvider(
    private val plugin: JavaPlugin
) : ResourceProvider {
    override val dataFolder = plugin.dataFolder

    @Suppress("UNCHECKED_CAST")
    override fun <T> getConfigValue(path: String, default: T): T {
        return plugin.config.get(path) as? T ?: default
    }

    override fun saveResource(resourcePath: String, replace: Boolean) {
        plugin.saveResource(resourcePath, replace)
    }

    override fun getResourceStream(resourcePath: String) = plugin.getResource(resourcePath)
}

private class SpigotPluginMetaProvider(
    private val plugin: JavaPlugin
) : PluginMetaProvider {
    override val name: String
        get() = plugin.description.name
}

private class BukkitMessageLogger(
    private val delegate: Logger
) : MessageLogger {
    private val miniMessage = MiniMessage.miniMessage()
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    override fun success(message: String) {
        delegate.info(toPlain(message))
    }

    override fun err(message: String) {
        delegate.severe(toPlain(message))
    }

    private fun toPlain(message: String): String {
        return plainSerializer.serialize(miniMessage.deserialize(message))
    }
}
