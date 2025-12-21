package pl.syntaxdevteam.message

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.papermc.paper.chat.ChatRenderer
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

    private fun <T> cacheMessage(
        category: String,
        key: String,
        placeholders: Map<String, String>,
        cache: Cache<String, T>,
        cacheKeyPrefix: String = "",
        formatHint: MessageFormat? = null,
        transform: (raw: String, resolver: TagResolver) -> T
    ): T {
        val cacheKey = buildString {
            append(cacheKeyPrefix)
            formatHint?.let { append("format:").append(it.name).append('|') }
            append(composeKey(category, key, placeholders))
        }
        val resolver = createResolver(placeholders)
        return cache.get(cacheKey) {
            val raw = yamlConfig.getString("$category.$key")
                ?: errorLogAndDefault(category, key)
            transform(raw, resolver)
        }
    }

    fun stringMessageToComponent(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        return cacheMessage(
            category,
            key,
            placeholders,
            componentCache
        ) { raw, resolver ->
            val full = "$prefix $raw"
            parseMixedMessage(full, resolver).component
        }
    }

    fun stringMessageToComponent(
        category: String,
        key: String,
        format: MessageFormat,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        return cacheMessage(
            category,
            key,
            placeholders,
            componentCache,
            formatHint = format
        ) { raw, resolver ->
            val full = "$prefix $raw"
            parseMixedMessage(full, resolver, format).component
        }
    }

    fun stringMessageToComponentNoPrefix(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        return cacheMessage(
            category,
            key,
            placeholders,
            componentCache,
            cacheKeyPrefix = "log."
        ) { raw, resolver ->
            parseMixedMessage(raw, resolver).component
        }
    }

    fun stringMessageToComponentNoPrefix(
        category: String,
        key: String,
        format: MessageFormat,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        return cacheMessage(
            category,
            key,
            placeholders,
            componentCache,
            cacheKeyPrefix = "log.",
            formatHint = format
        ) { raw, resolver ->
            parseMixedMessage(raw, resolver, format).component
        }
    }

    fun stringMessageToString(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        return cacheMessage(
            category,
            key,
            placeholders,
            simpleCache
        ) { raw, resolver ->
            val parsed = parseMixedMessage("$prefix $raw", resolver)
            serializeComponent(parsed)
        }
    }

    fun stringMessageToString(
        category: String,
        key: String,
        format: MessageFormat,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        return cacheMessage(
            category,
            key,
            placeholders,
            simpleCache,
            formatHint = format
        ) { raw, resolver ->
            val parsed = parseMixedMessage("$prefix $raw", resolver, format)
            serializeComponent(parsed)
        }
    }

    fun stringMessageToStringNoPrefix(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        return cacheMessage(
            category,
            key,
            placeholders,
            cleanCache
        ) { raw, resolver ->
            val parsed = parseMixedMessage(raw, resolver)
            serializeComponent(parsed)
        }
    }

    fun stringMessageToStringNoPrefix(
        category: String,
        key: String,
        format: MessageFormat,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        return cacheMessage(
            category,
            key,
            placeholders,
            cleanCache,
            formatHint = format
        ) { raw, resolver ->
            val parsed = parseMixedMessage(raw, resolver, format)
            serializeComponent(parsed)
        }
    }

    fun getMessageStringList(category: String, key: String): List<String> {
        return yamlConfig.getStringList("$category.$key")
    }

    /**
     * Builds a viewer-unaware [ChatRenderer] that formats chat messages using the provided template,
     * while keeping the original signed message intact.
     *
     * Available component placeholders: <player>, <message>.
     */
    fun signedChatRenderer(
        template: String,
        placeholders: Map<String, String> = emptyMap()
    ): ChatRenderer {
        return ChatRenderer.viewerUnaware { _, playerName, message ->
            val resolvers = placeholders.map { (key, value) ->
                Placeholder.parsed(key, value)
            } + listOf(
                Placeholder.component("player", playerName),
                Placeholder.component("message", message)
            )
            val resolver = TagResolver.resolver(*resolvers.toTypedArray())
            formatMixedTextToMiniMessage(template, resolver)
        }
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
        return stringMessageToComponent(category, key, placeholders)
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
        return stringMessageToString(category, key, placeholders)
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
        return stringMessageToStringNoPrefix(category, key, placeholders)
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
        return stringMessageToComponentNoPrefix(category, key, placeholders)
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
        return parseMixedMessage(message, resolver).component
    }

    fun formatTextToComponent(
        message: String,
        format: MessageFormat,
        resolver: TagResolver? = TagResolver.empty()
    ): Component {
        return parseMixedMessage(message, resolver, format).component
    }

    fun formatMixedTextToLegacy(message: String, resolver: TagResolver? = TagResolver.empty()): String {
        val parsed = parseMixedMessage(message, resolver)
        return serializeComponent(parsed, MessageFormat.LEGACY_AMPERSAND)
    }

    private fun parseMixedMessage(
        message: String,
        resolver: TagResolver?,
        formatHint: MessageFormat? = null
    ): ParsedMessage {
        val normalized = if (message.contains("\\u")) convertUnicodeEscapeSequences(message) else message
        val format = formatHint ?: detectMessageFormat(normalized)
        val component = when (format) {
            MessageFormat.MINI_MESSAGE -> deserializeMiniMessage(normalized, resolver)
            MessageFormat.LEGACY_SECTION -> deserializeMiniMessage(convertSectionSignToMiniMessage(normalized), resolver)
            MessageFormat.LEGACY_AMPERSAND -> deserializeMiniMessage(convertLegacyToMiniMessage(normalized), resolver)
            MessageFormat.PLAIN -> Component.text(normalized)
        }
        return ParsedMessage(component, format)
    }

    private fun deserializeMiniMessage(message: String, resolver: TagResolver?): Component {
        return if (resolver != null) {
            mM.deserialize(message, resolver)
        } else {
            mM.deserialize(message)
        }
    }

    private fun detectMessageFormat(message: String): MessageFormat {
        val hasMiniMessageTags = "<[^>]+>".toRegex().containsMatchIn(message)
        val hasSectionColors = "§[0-9a-fk-orA-FK-OR]".toRegex().containsMatchIn(message)
        val hasLegacyColors = "&[0-9a-fk-orA-FK-OR]".toRegex().containsMatchIn(message)

        return when {
            hasMiniMessageTags -> MessageFormat.MINI_MESSAGE
            hasSectionColors -> MessageFormat.LEGACY_SECTION
            hasLegacyColors -> MessageFormat.LEGACY_AMPERSAND
            else -> MessageFormat.PLAIN
        }
    }

    private fun serializeComponent(parsedMessage: ParsedMessage, targetFormat: MessageFormat = parsedMessage.sourceFormat): String {
        return when (targetFormat) {
            MessageFormat.MINI_MESSAGE -> mM.serialize(parsedMessage.component)
            MessageFormat.LEGACY_SECTION -> LegacyComponentSerializer
                .legacySection()
                .toBuilder()
                .hexColors()
                .build()
                .serialize(parsedMessage.component)
            MessageFormat.LEGACY_AMPERSAND -> LegacyComponentSerializer
                .legacyAmpersand()
                .toBuilder()
                .hexColors()
                .build()
                .serialize(parsedMessage.component)
            MessageFormat.PLAIN -> getPlainText(parsedMessage.component)
        }
    }

    private data class ParsedMessage(
        val component: Component,
        val sourceFormat: MessageFormat
    )

    enum class MessageFormat {
        MINI_MESSAGE,
        LEGACY_SECTION,
        LEGACY_AMPERSAND,
        PLAIN
    }
}
