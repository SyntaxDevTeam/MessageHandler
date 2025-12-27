package pl.syntaxdevteam.message

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.md_5.bungee.api.plugin.Plugin
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.LinkedHashMap
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

/**
 * Entry-point for using [MessageHandler] on BungeeCord proxies with minimal boilerplate.
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
     * Initializes the handler using default BungeeCord integrations sourced from the
     * provided [plugin]. The handler is stored in [messages] for convenient reuse.
     */
    @JvmStatic
    fun initialize(plugin: Plugin, logger: Logger = plugin.logger): MessageHandler {
        return configure(
            resources = BungeeResourceProvider(plugin),
            meta = BungeePluginMetaProvider(plugin),
            logger = BungeeMessageLogger(logger)
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

private class BungeeResourceProvider(
    private val plugin: Plugin
) : ResourceProvider {
    override val dataFolder: File = plugin.dataFolder.also { it.mkdirs() }
    private val configFile = File(dataFolder, "config.yml")
    private val yaml = Yaml(LoaderOptions())
    private val config: MutableMap<String, Any?> = loadConfig()

    private fun loadConfig(): MutableMap<String, Any?> {
        if (!configFile.exists()) {
            plugin.getResourceAsStream("config.yml")?.use { input ->
                configFile.parentFile.mkdirs()
                Files.copy(input, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } ?: run {
                configFile.parentFile.mkdirs()
                configFile.createNewFile()
            }
        }

        if (!configFile.exists()) return mutableMapOf()

        return configFile.inputStream().use { stream ->
            yaml.load<Map<String, Any?>>(stream)?.toMutableDeepMap() ?: mutableMapOf()
        }
    }

    override fun <T> getConfigValue(path: String, default: T): T {
        val value = config.getPath(path)
        @Suppress("UNCHECKED_CAST")
        return value as? T ?: default
    }

    override fun saveResource(resourcePath: String, replace: Boolean) {
        val target = File(dataFolder, resourcePath)
        if (target.exists() && !replace) return
        plugin.getResourceAsStream(resourcePath)?.use { input ->
            target.parentFile.mkdirs()
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun getResourceStream(resourcePath: String) = plugin.getResourceAsStream(resourcePath)

    private fun Map<String, Any?>.getPath(path: String): Any? {
        var current: Any? = this
        for (part in path.split('.')) {
            current = (current as? Map<*, *>)?.get(part) ?: return null
        }
        return current
    }

    private fun Map<*, *>.toMutableDeepMap(): MutableMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in this) {
            val mappedValue = when (value) {
                is Map<*, *> -> value.toMutableDeepMap()
                is List<*> -> value.map { element ->
                    when (element) {
                        is Map<*, *> -> element.toMutableDeepMap()
                        else -> element
                    }
                }
                else -> value
            }
            result[key.toString()] = mappedValue
        }
        return result
    }
}

private class BungeePluginMetaProvider(
    private val plugin: Plugin
) : PluginMetaProvider {
    override val name: String
        get() = plugin.description.name
}

private class BungeeMessageLogger(
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
