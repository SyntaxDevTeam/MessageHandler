package pl.syntaxdevteam.message

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.TimeUnit

@Suppress("unused")
class MessageHandler(
    private val resources: ResourceProvider,
    private val meta: PluginMetaProvider,
    private val logger: MessageLogger = MessageLogger.NO_OP
) {
    private val language = resources.getConfigValue("language", "EN").lowercase()
    private val messagesFile = File(resources.dataFolder, "lang/messages_$language.yml")

    private val componentCache: Cache<String, Component> =
        Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build()

    private val simpleCache: Cache<String, String> =
        Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build()

    private val cleanCache: Cache<String, String> =
        Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build()

    private val complexCache: Cache<String, List<Component>> =
        Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build()
    private val mM = MiniMessage.miniMessage()

    @Volatile
    private var yamlConfig: FileConfiguration = YamlConfiguration()

    @Volatile
    private var prefix: String = "[${meta.name}]"

    init {
        copyDefaultAndSync()
        reloadMessages()
    }

    private fun loadYaml(): FileConfiguration =
        YamlConfiguration.loadConfiguration(messagesFile)

    fun initial() {
        val author = getAuthorFromYamlComment() ?: "SyntaxDevTeam"
        logger.success("<gray>Loaded \"$language\" language file by: <white><b>$author</b></white>")
    }

    private fun getAuthorFromYamlComment(): String? {
        val path = "lang/messages_${language.lowercase()}.yml"
        val langFile = File(resources.dataFolder, path)
        if (!langFile.exists()) return null

        langFile.useLines { lines ->
            for (line in lines) {
                if (line.trim().startsWith("# Author:")) {
                    return line.substringAfter("# Author:").trim()
                }
            }
        }
        return null
    }

    private fun copyDefaultAndSync() {
        val resourcePath = "lang/messages_${language.lowercase()}.yml"
        val targetFile = File(resources.dataFolder, resourcePath)
        if (!targetFile.exists()) {
            targetFile.parentFile.mkdirs()
            resources.saveResource(resourcePath, false)
        }

        val langFile = File(resources.dataFolder, resourcePath)
        val defaultStream = resources.getResourceStream(resourcePath)
            ?: run {
                logger.err("Default language file for $language not found!")
                return
            }

        val defaultCfg = YamlConfiguration.loadConfiguration(defaultStream.reader())
        val currentCfg = YamlConfiguration.loadConfiguration(langFile)
        var updated = false

        fun syncSections(def: ConfigurationSection, cur: ConfigurationSection) {
            for (key in def.getKeys(false)) {
                if (!cur.contains(key)) {
                    cur[key] = def[key]
                    updated = true
                } else if (def.isConfigurationSection(key)) {
                    syncSections(
                        def.getConfigurationSection(key)!!,
                        cur.getConfigurationSection(key)!!
                    )
                }
            }
        }
        syncSections(defaultCfg, currentCfg)

        if (updated) {
            logger.success("Updating messages_${language.lowercase()}.yml with missing entries.")
            currentCfg.save(langFile)
        }
    }

    fun reloadMessages() {
        yamlConfig = loadYaml()
        prefix = yamlConfig.getString("prefix") ?: "[${meta.name}]"
        componentCache.invalidateAll()
        simpleCache.invalidateAll()
        cleanCache.invalidateAll()
        complexCache.invalidateAll()
    }

    private fun errorLogAndDefault(category: String, key: String): String {
        logger.err("Nie można załadować wiadomości $key z kategorii $category")
        return "Message not found!"
    }

    fun getPrefix(): String = prefix

    private fun createResolver(placeholders: Map<String, String>): TagResolver {
        if (placeholders.isEmpty()) return TagResolver.empty()
        val resolvers = placeholders.map { (k, v) -> Placeholder.parsed(k, v) }
        return TagResolver.resolver(resolvers)
    }

    private fun composeKey(category: String, key: String, placeholders: Map<String, String>): String {
        return buildString {
            append(category).append('.').append(key)
            if (placeholders.isNotEmpty()) {
                append('?')
                placeholders.entries.sortedBy { it.key }
                    .joinTo(this, "&") { "${it.key}=${it.value}" }
            }
        }
    }

    fun stringMessageToComponent(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return componentCache.get(cacheKey) {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            val full = "$prefix $raw"
            formatMixedTextToMiniMessage(full, resolver)
        }
    }

    fun stringMessageToComponentNoPrefix(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return componentCache.get("log.$cacheKey") {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            formatMixedTextToMiniMessage(raw, resolver)
        }
    }

    fun stringMessageToString(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return simpleCache.get(cacheKey) {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            val parsed = formatMixedTextToMiniMessage("$prefix $raw", resolver)
            mM.serialize(parsed)
        }
    }

    fun stringMessageToStringNoPrefix(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return cleanCache.get(cacheKey) {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            val parsed = formatMixedTextToMiniMessage(raw, resolver)
            mM.serialize(parsed)
        }
    }

    fun getMessageStringList(category: String, key: String): List<String> {
        return yamlConfig.getStringList("$category.$key")
    }

    /**
     * @deprecated Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach.
     *  Użyj zamiast niej: {@link #stringMessageToComponent}
     */
    @Deprecated(
        "Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach. Użyj: stringMessageToComponent",
        replaceWith = ReplaceWith("stringMessageToComponent(category, key, placeholders)"),
        level = DeprecationLevel.WARNING
    )
    fun getMessage(category: String, key: String, placeholders: Map<String, String> = emptyMap()): Component {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return componentCache.get(cacheKey) {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            val full = "$prefix $raw"
            formatMixedTextToMiniMessage(full, resolver)
        }
    }

    /**
     * @deprecated Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach.
     *  Użyj zamiast niej: {@link #stringMessageToString}
     */
    @Deprecated(
        "Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach. Użyj: stringMessageToString",
        replaceWith = ReplaceWith("stringMessageToString(category, key, placeholders)"),
        level = DeprecationLevel.WARNING
    )
    fun getSimpleMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return simpleCache.get(cacheKey) {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            val parsed = formatMixedTextToMiniMessage("$prefix $raw", resolver)
            mM.serialize(parsed)
        }
    }

    /**
     * @deprecated Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach.
     *  Użyj zamiast niej: {@link #stringMessageToStringNoPrefix}
     */
    @Deprecated(
        "Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach. Użyj: stringMessageToStringNoPrefix",
        replaceWith = ReplaceWith("stringMessageToStringNoPrefix(category, key, placeholders)"),
        level = DeprecationLevel.WARNING
    )
    fun getCleanMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return cleanCache.get(cacheKey) {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            val parsed = formatMixedTextToMiniMessage(raw, resolver)
            mM.serialize(parsed)
        }
    }

    /**
     * @deprecated Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach.
     *  Użyj zamiast niej: {@link #stringMessageToComponentNoPrefix}
     */
    @Deprecated(
        "Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach. Użyj: stringMessageToComponentNoPrefix",
        replaceWith = ReplaceWith("stringMessageToComponentNoPrefix(category, key, placeholders)"),
        level = DeprecationLevel.WARNING
    )
    fun getLogMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return componentCache.get("log.$cacheKey") {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            formatMixedTextToMiniMessage(raw, resolver)
        }
    }

    fun getSmartMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): List<Component> {
        val cacheKey = composeKey("smart.$category", key, placeholders)
        val resolver = createResolver(placeholders)
        return complexCache.get(cacheKey) {
            val path = "$category.$key"
            when (val rawValue = yamlConfig.get(path)) {
                is String -> {
                    listOf(
                        formatMixedTextToMiniMessage("$prefix $rawValue", resolver)
                    )
                }
                is List<*> -> {
                    rawValue.filterIsInstance<String>().map { line ->
                        formatMixedTextToMiniMessage(line, resolver)
                    }
                }
                else -> {
                    logger.err("There was an error loading the smart message $key from category $category")
                    listOf(Component.text("Message not found. Check console..."))
                }
            }
        }
    }

    /**
     * @deprecated Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach.
     *  Użyj zamiast niej: {@link #getMessageStringList}
     */
    @Deprecated(
        "Ta metoda została wycofana i zostanie usunięta w przyszłych wersjach. Użyj: getMessageStringList",
        replaceWith = ReplaceWith("getMessageStringList(category, key)"),
        level = DeprecationLevel.WARNING
    )
    fun getReasons(category: String, key: String): List<String> {
        return yamlConfig.getStringList("$category.$key")
    }

    fun formatLegacyText(message: String): Component {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message)
    }

    fun formatHexAndLegacyText(message: String): Component {
        val hexFormatted = message.replace("&#([a-fA-F0-9]{6})".toRegex()) {
            val hex = it.groupValues[1]
            "§x§${hex[0]}§${hex[1]}§${hex[2]}§${hex[3]}§${hex[4]}§${hex[5]}"
        }

        return LegacyComponentSerializer.legacySection().deserialize(hexFormatted)
    }

    fun miniMessageFormat(message: String): Component {
        return mM.deserialize(message)
    }

    fun getANSIText(component: Component): String {
        return ANSIComponentSerializer.ansi().serialize(component)
    }

    fun getPlainText(component: Component): String {
        return PlainTextComponentSerializer.plainText().serialize(component)
    }

    private fun convertWithLegacySerializer(
        message: String,
        serializer: LegacyComponentSerializer
    ): String {
        val pattern = Regex("(<[^>]+>|\\{[^}]+})")
        val result = StringBuilder()
        var lastIndex = 0

        for (match in pattern.findAll(message)) {
            val start = match.range.first
            if (start > lastIndex) {
                val nonTag = message.substring(lastIndex, start)
                val component = serializer.deserialize(nonTag)
                result.append(mM.serialize(component))
            }
            result.append(match.value)
            lastIndex = match.range.last + 1
        }
        if (lastIndex < message.length) {
            val component = serializer.deserialize(message.substring(lastIndex))
            result.append(mM.serialize(component))
        }
        return result.toString()
    }

    private fun convertLegacyToMiniMessage(message: String): String {
        val serializer = LegacyComponentSerializer.legacyAmpersand()
            .toBuilder()
            .hexColors()
            .build()
        return convertWithLegacySerializer(message, serializer)
    }

    private fun convertSectionSignToMiniMessage(message: String): String {
        val serializer = LegacyComponentSerializer.legacySection()
            .toBuilder()
            .hexColors()
            .build()
        return convertWithLegacySerializer(message, serializer)
    }

    private fun convertUnicodeEscapeSequences(input: String): String {
        return input.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { matchResult ->
            val codePoint = matchResult.groupValues[1].toInt(16)
            String(Character.toChars(codePoint))
        }
    }

    fun formatMixedTextToMiniMessage(message: String, resolver: TagResolver? = TagResolver.empty()): Component {
        var formattedMessage = message
        formattedMessage = convertSectionSignToMiniMessage(formattedMessage)
        formattedMessage = convertLegacyToMiniMessage(formattedMessage)
        if (formattedMessage.contains("\\u")) {
            formattedMessage = convertUnicodeEscapeSequences(formattedMessage)
        }
        return if (resolver != null) {
            mM.deserialize(formattedMessage, resolver)
        } else {
            mM.deserialize(formattedMessage)
        }
    }

    fun formatMixedTextToLegacy(message: String, resolver: TagResolver? = TagResolver.empty()): String {
        val component = formatMixedTextToMiniMessage(message, resolver)
        return LegacyComponentSerializer
            .legacyAmpersand()
            .toBuilder()
            .hexColors()
            .build()
            .serialize(component)
    }
}
