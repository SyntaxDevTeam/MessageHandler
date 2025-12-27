package pl.syntaxdevteam.message

import com.velocitypowered.api.plugin.PluginContainer
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.slf4j.Logger
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap

/**
 * Entry-point for using [MessageHandler] on Velocity proxies with minimal boilerplate.
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
     * Initializes the handler using default Velocity integrations sourced from the
     * provided [pluginContainer]. The handler is stored in [messages] for convenient reuse.
     */
    @JvmStatic
    fun initialize(
        pluginContainer: PluginContainer,
        dataDirectory: Path,
        logger: Logger
    ): MessageHandler {
        return configure(
            resources = VelocityResourceProvider(pluginContainer, dataDirectory),
            meta = VelocityPluginMetaProvider(pluginContainer),
            logger = VelocityMessageLogger(logger)
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

private class VelocityResourceProvider(
    private val pluginContainer: PluginContainer,
    dataDirectory: Path
) : ResourceProvider {
    override val dataFolder: File = dataDirectory.toFile().also { it.mkdirs() }
    private val configFile = dataDirectory.resolve("config.yml").toFile()
    private val yaml = Yaml(LoaderOptions())
    private val classLoader: ClassLoader = pluginContainer.getInstance()
        .map { it.javaClass.classLoader }
        .orElse(VelocityResourceProvider::class.java.classLoader)
    private val config: MutableMap<String, Any?> = loadConfig()

    private fun loadConfig(): MutableMap<String, Any?> {
        if (!configFile.exists()) {
            classLoader.getResourceAsStream("config.yml")?.use { input ->
                configFile.parentFile.mkdirs()
                Files.copy(input, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } ?: run {
                configFile.parentFile.mkdirs()
                configFile.createNewFile()
            }
            if (!configFile.exists()) {
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
        val target = dataFolder.toPath().resolve(resourcePath).toFile()
        if (target.exists() && !replace) return
        classLoader.getResourceAsStream(resourcePath)?.use { input ->
            target.parentFile.mkdirs()
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun getResourceStream(resourcePath: String) =
        classLoader.getResourceAsStream(resourcePath)

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

private class VelocityPluginMetaProvider(
    private val pluginContainer: PluginContainer
) : PluginMetaProvider {
    override val name: String
        get() = pluginContainer.description.name.orElse(pluginContainer.description.id)
}

private class VelocityMessageLogger(
    private val delegate: Logger
) : MessageLogger {
    private val miniMessage = MiniMessage.miniMessage()
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    override fun success(message: String) {
        delegate.info(toPlain(message))
    }

    override fun err(message: String) {
        delegate.error(toPlain(message))
    }

    private fun toPlain(message: String): String {
        return plainSerializer.serialize(miniMessage.deserialize(message))
    }
}
