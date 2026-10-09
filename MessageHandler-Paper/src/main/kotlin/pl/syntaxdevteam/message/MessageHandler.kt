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
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

@Suppress("unused")
class MessageHandler(
    private val resources: ResourceProvider,
    private val meta: PluginMetaProvider,
    private val logger: MessageLogger = MessageLogger.NO_OP
) {
    data class LanguageValidationResult(
        val valid: Boolean,
        val file: File,
        val error: String? = null
    )

    @Volatile
    private var automaticLanguage = resources.getConfigValue("language", "EN").trim().equals("auto", ignoreCase = true)

    @Volatile
    private var language = configuredFallbackLanguage()

    @Volatile
    private var messagesFile = File(resources.dataFolder, "lang/messages_$language.yml")

    private val componentCache: Cache<String, Component> = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build()
    private val simpleCache: Cache<String, String> = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build()
    private val cleanCache: Cache<String, String> = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build()
    private val complexCache: Cache<String, List<Component>> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build()
    private val localeConfigCache: Cache<String, FileConfiguration> = Caffeine.newBuilder()
        .maximumSize(32)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build()

    private val mM = MiniMessage.miniMessage()

    @Volatile
    private var yamlConfig: FileConfiguration = YamlConfiguration()

    @Volatile
    private var defaultYamlConfig: FileConfiguration = YamlConfiguration()

    @Volatile
    private var prefix: String = "[${meta.name}]"

    @Volatile
    private var hasLoadedConfiguration = false

    init {
        reloadMessages()
    }

    private fun configuredFallbackLanguage(): String {
        val configured = resources.getConfigValue("language", "EN").trim().lowercase(Locale.ROOT)
        if (configured != "auto") return configured

        return resources.getConfigValue("fallback-language", "EN")
            .trim()
            .lowercase(Locale.ROOT)
            .takeUnless { it.isBlank() || it == "auto" }
            ?: "en"
    }

    private fun refreshLanguageAndFile() {
        automaticLanguage = resources.getConfigValue("language", "EN").trim().equals("auto", ignoreCase = true)
        language = configuredFallbackLanguage()
        messagesFile = File(resources.dataFolder, "lang/messages_$language.yml")
    }

    private fun resourcePathFor(languageCode: String = language): String =
        "lang/messages_${languageCode.lowercase(Locale.ROOT)}.yml"

    private fun parseYaml(file: File): FileConfiguration {
        val text = file.readText(StandardCharsets.UTF_8)
        return YamlConfiguration().apply { loadFromString(text) }
    }

    private fun parseYaml(text: String): FileConfiguration =
        YamlConfiguration().apply { loadFromString(text) }

    private fun loadBundledLanguage(resourcePath: String, logMissing: Boolean = true): FileConfiguration? {
        val stream = resources.getResourceStream(resourcePath)
        if (stream == null) {
            if (logMissing) logger.err("Bundled language resource '$resourcePath' was not found.")
            return null
        }

        return try {
            val text = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            parseYaml(text)
        } catch (throwable: Throwable) {
            logger.err("Bundled language resource '$resourcePath' is invalid: ${describeError(throwable)}")
            null
        }
    }

    private fun ensureLanguageFileExists(resourcePath: String) {
        if (messagesFile.exists()) return
        try {
            messagesFile.parentFile?.mkdirs()
            resources.saveResource(resourcePath, false)
        } catch (throwable: Throwable) {
            logger.err("Could not create ${messagesFile.path}: ${describeError(throwable)}. Bundled messages will be used in memory.")
        }
    }

    private fun loadUserLanguage(file: File, createBackupOnFailure: Boolean): FileConfiguration? {
        if (!file.exists()) return null
        return try {
            parseYaml(file)
        } catch (throwable: Throwable) {
            logger.err("Invalid YAML in ${file.path}: ${describeError(throwable)}")
            logger.err("The language file has NOT been modified.")
            if (createBackupOnFailure) backupInvalidLanguageFile(file)
            null
        }
    }

    private fun backupInvalidLanguageFile(file: File) {
        try {
            val backupDirectory = File(file.parentFile, "backups")
            backupDirectory.mkdirs()
            val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").format(LocalDateTime.now())
            var backup = File(backupDirectory, "${file.nameWithoutExtension}_$timestamp.invalid.yml")
            var suffix = 1
            while (backup.exists()) {
                backup = File(backupDirectory, "${file.nameWithoutExtension}_${timestamp}_$suffix.invalid.yml")
                suffix++
            }
            file.copyTo(backup, overwrite = false)
            logger.err("A safety copy of the invalid language file was saved to ${backup.path}.")
        } catch (throwable: Throwable) {
            logger.err("Could not create a safety copy of ${file.path}: ${describeError(throwable)}")
        }
    }

    private fun describeError(throwable: Throwable): String =
        throwable.message?.replace('\n', ' ')?.replace('\r', ' ')?.trim().takeUnless { it.isNullOrBlank() }
            ?: throwable.javaClass.simpleName

    private fun invalidateCaches() {
        componentCache.invalidateAll()
        simpleCache.invalidateAll()
        cleanCache.invalidateAll()
        complexCache.invalidateAll()
        localeConfigCache.invalidateAll()
    }

    fun validateConfiguredLanguageFile(): LanguageValidationResult {
        val configuredLanguage = configuredFallbackLanguage()
        val file = File(resources.dataFolder, resourcePathFor(configuredLanguage))
        if (!file.exists()) return LanguageValidationResult(true, file)

        return try {
            parseYaml(file)
            LanguageValidationResult(true, file)
        } catch (throwable: Throwable) {
            LanguageValidationResult(false, file, describeError(throwable))
        }
    }

    fun initial() {
        val author = getAuthorFromYamlComment() ?: "SyntaxDevTeam"
        val loadedLanguage = if (automaticLanguage) {
            "\"auto\" language mode with \"$language\" fallback file"
        } else {
            "\"$language\" language file"
        }
        logger.success("<gray>Loaded $loadedLanguage by: <white><b>$author</b></white>")
    }

    private fun getAuthorFromYamlComment(): String? {
        val langFile = File(resources.dataFolder, resourcePathFor())
        if (!langFile.exists()) return null
        langFile.useLines { lines ->
            lines.forEach { line ->
                if (line.trim().startsWith("# Author:")) return line.substringAfter("# Author:").trim()
            }
        }
        return null
    }

    /*
     * LEGACY LANGUAGE VERSIONING — INTENTIONALLY DISABLED.
     *
     * These methods are kept as a trace of the old migration concept so it can be redesigned
     * later. No runtime path calls them. Language files are never replaced based on this data.
     */
    @Suppress("unused")
    private fun getVersionFromYamlHeader(langFile: File): String? {
        if (!langFile.exists()) return null
        val versionRegex = Regex("""#\s*(?:ver(?:sion)?[:.]?\s*)?(\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE)
        langFile.useLines { lines ->
            for ((index, line) in lines.withIndex()) {
                if (index >= 2) break
                versionRegex.find(line.trim())?.let { return it.groupValues[1] }
            }
        }
        return null
    }

    @Suppress("unused")
    private fun isVersionLowerThan(version: String, reference: String): Boolean {
        fun parseVersion(value: String): List<Int>? = value.split('.').map { it.toIntOrNull() ?: return null }
        val parsedVersion = parseVersion(version) ?: return false
        val parsedReference = parseVersion(reference) ?: return false
        val size = maxOf(parsedVersion.size, parsedReference.size)
        val normalizedVersion = parsedVersion + List(size - parsedVersion.size) { 0 }
        val normalizedReference = parsedReference + List(size - parsedReference.size) { 0 }
        for (index in 0 until size) {
            val diff = normalizedVersion[index].compareTo(normalizedReference[index])
            if (diff < 0) return true
            if (diff > 0) return false
        }
        return false
    }

    @Suppress("unused")
    private fun shouldReplaceOutdatedLanguage(langFile: File): Boolean {
        val version = getVersionFromYamlHeader(langFile) ?: return false
        return isVersionLowerThan(version, "2.0.0")
    }

    fun reloadMessages() {
        refreshLanguageAndFile()
        val resourcePath = resourcePathFor()
        ensureLanguageFileExists(resourcePath)

        val bundled = loadBundledLanguage(resourcePath)
        if (bundled == null) {
            if (hasLoadedConfiguration) {
                logger.err("Language reload aborted because the bundled fallback is unavailable. The previously loaded messages remain active.")
                return
            }
            throw IllegalStateException("Cannot initialize MessageHandler: bundled language '$resourcePath' is unavailable or invalid.")
        }

        val userConfig = loadUserLanguage(messagesFile, createBackupOnFailure = true)
        if (messagesFile.exists() && userConfig == null && hasLoadedConfiguration) {
            logger.err("Language reload aborted. The previously loaded valid messages remain active until the YAML file is fixed.")
            return
        }

        defaultYamlConfig = bundled
        yamlConfig = userConfig ?: bundled
        prefix = resolveString("prefix", listOf(yamlConfig, defaultYamlConfig)) ?: "[${meta.name}]"
        hasLoadedConfiguration = true
        invalidateCaches()

        if (messagesFile.exists() && userConfig == null) {
            logger.err("MessageHandler started with the bundled '$language' language in memory because the user file is invalid.")
        }
    }

    fun getPrefix(): String = prefix

    private fun createResolver(placeholders: Map<String, String>): TagResolver {
        if (placeholders.isEmpty()) return TagResolver.empty()
        return TagResolver.resolver(placeholders.map { (key, value) -> Placeholder.parsed(key, value) })
    }

    private fun composeKey(category: String, key: String, placeholders: Map<String, String>): String = buildString {
        append(category).append('.').append(key)
        if (placeholders.isNotEmpty()) {
            append('?')
            placeholders.entries.sortedBy { it.key }.joinTo(this, "&") { "${it.key}=${it.value}" }
        }
    }

    private fun normalizeLocale(locale: String): String = locale.lowercase(Locale.ROOT).replace('-', '_')

    private fun localeCandidates(locale: String): List<String> {
        val normalized = normalizeLocale(locale)
        val languageOnly = normalized.substringBefore('_')
        return if (languageOnly == normalized) listOf(normalized) else listOf(normalized, languageOnly)
    }

    private fun loadLocaleConfig(locale: String): FileConfiguration {
        for (candidate in localeCandidates(locale)) {
            val file = File(resources.dataFolder, "lang/messages_$candidate.yml")
            if (!file.exists()) continue
            return localeConfigCache.get(candidate) {
                loadUserLanguage(file, createBackupOnFailure = true)
                    ?: loadBundledLanguage("lang/messages_$candidate.yml", logMissing = false)
                    ?: yamlConfig
            }
        }
        return yamlConfig
    }

    private fun getPrefixForConfig(config: FileConfiguration): String =
        resolveString("prefix", listOf(config, yamlConfig, defaultYamlConfig)) ?: prefix

    private fun resolveString(path: String, configs: List<FileConfiguration>): String? {
        for (config in configs.distinct()) {
            val value = config.get(path) ?: continue
            if (value is String) return value
            logger.err("Language entry '$path' has type ${value.javaClass.simpleName}, expected String. Trying fallback value.")
        }
        return null
    }

    private fun resolveStringList(path: String, configs: List<FileConfiguration>): List<String>? {
        for (config in configs.distinct()) {
            val value = config.get(path) ?: continue
            if (value is List<*> && value.all { it is String }) return value.filterIsInstance<String>()
            logger.err("Language entry '$path' has an invalid type or list content. Trying fallback value.")
        }
        return null
    }

    private fun resolveSmartValue(path: String, configs: List<FileConfiguration>): Any? {
        for (config in configs.distinct()) {
            when (val value = config.get(path)) {
                null -> Unit
                is String -> return value
                is List<*> -> if (value.all { it is String }) return value else logger.err("Language entry '$path' contains non-string list elements. Trying fallback value.")
                else -> logger.err("Language entry '$path' has unsupported type ${value.javaClass.simpleName}. Trying fallback value.")
            }
        }
        return null
    }

    private fun errorLogAndDefault(category: String, key: String): String {
        logger.err("Cannot load message '$key' from category '$category'.")
        return "Message not found!"
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
            val raw = resolveString("$category.$key", listOf(yamlConfig, defaultYamlConfig))
                ?: errorLogAndDefault(category, key)
            transform(raw, resolver)
        }
    }

    private fun <T> cacheLocalizedMessage(
        locale: String,
        category: String,
        key: String,
        placeholders: Map<String, String>,
        cache: Cache<String, T>,
        cacheKeyPrefix: String = "",
        formatHint: MessageFormat? = null,
        transform: (raw: String, resolver: TagResolver, selectedConfig: FileConfiguration) -> T
    ): T {
        val normalizedLocale = normalizeLocale(locale)
        val cacheKey = buildString {
            append("locale:").append(normalizedLocale).append('|')
            append(cacheKeyPrefix)
            formatHint?.let { append("format:").append(it.name).append('|') }
            append(composeKey(category, key, placeholders))
        }
        val resolver = createResolver(placeholders)
        return cache.get(cacheKey) {
            val selectedConfig = loadLocaleConfig(normalizedLocale)
            val raw = resolveString("$category.$key", listOf(selectedConfig, yamlConfig, defaultYamlConfig))
                ?: errorLogAndDefault(category, key)
            transform(raw, resolver, selectedConfig)
        }
    }

    fun stringMessageToComponent(category: String, key: String, placeholders: Map<String, String> = emptyMap()): Component =
        cacheMessage(category, key, placeholders, componentCache) { raw, resolver ->
            parseMixedMessage("$prefix $raw", resolver).component
        }

    fun stringMessageToComponentForLocale(locale: String, category: String, key: String, placeholders: Map<String, String> = emptyMap()): Component =
        cacheLocalizedMessage(locale, category, key, placeholders, componentCache) { raw, resolver, selectedConfig ->
            parseMixedMessage("${getPrefixForConfig(selectedConfig)} $raw", resolver).component
        }

    fun stringMessageToComponent(player: Player, category: String, key: String, placeholders: Map<String, String> = emptyMap()): Component =
        if (automaticLanguage) stringMessageToComponentForLocale(player.locale().toLanguageTag(), category, key, placeholders)
        else stringMessageToComponent(category, key, placeholders)

    fun stringMessageToComponent(category: String, key: String, format: MessageFormat, placeholders: Map<String, String> = emptyMap()): Component =
        cacheMessage(category, key, placeholders, componentCache, formatHint = format) { raw, resolver ->
            parseMixedMessage("$prefix $raw", resolver, format).component
        }

    fun stringMessageToComponentNoPrefix(category: String, key: String, placeholders: Map<String, String> = emptyMap()): Component =
        cacheMessage(category, key, placeholders, componentCache, cacheKeyPrefix = "log.") { raw, resolver ->
            parseMixedMessage(raw, resolver).component
        }

    fun stringMessageToComponentNoPrefix(category: String, key: String, format: MessageFormat, placeholders: Map<String, String> = emptyMap()): Component =
        cacheMessage(category, key, placeholders, componentCache, cacheKeyPrefix = "log.", formatHint = format) { raw, resolver ->
            parseMixedMessage(raw, resolver, format).component
        }

    fun stringMessageToString(category: String, key: String, placeholders: Map<String, String> = emptyMap()): String =
        cacheMessage(category, key, placeholders, simpleCache) { raw, resolver ->
            serializeComponent(parseMixedMessage("$prefix $raw", resolver))
        }

    fun stringMessageToStringForLocale(locale: String, category: String, key: String, placeholders: Map<String, String> = emptyMap()): String =
        cacheLocalizedMessage(locale, category, key, placeholders, simpleCache) { raw, resolver, selectedConfig ->
            serializeComponent(parseMixedMessage("${getPrefixForConfig(selectedConfig)} $raw", resolver))
        }

    fun stringMessageToString(player: Player, category: String, key: String, placeholders: Map<String, String> = emptyMap()): String =
        if (automaticLanguage) stringMessageToStringForLocale(player.locale().toLanguageTag(), category, key, placeholders)
        else stringMessageToString(category, key, placeholders)

    fun stringMessageToString(category: String, key: String, format: MessageFormat, placeholders: Map<String, String> = emptyMap()): String =
        cacheMessage(category, key, placeholders, simpleCache, formatHint = format) { raw, resolver ->
            serializeComponent(parseMixedMessage("$prefix $raw", resolver, format))
        }

    fun stringMessageToStringNoPrefix(category: String, key: String, placeholders: Map<String, String> = emptyMap()): String =
        cacheMessage(category, key, placeholders, cleanCache) { raw, resolver -> serializeComponent(parseMixedMessage(raw, resolver)) }

    fun stringMessageToStringNoPrefix(category: String, key: String, format: MessageFormat, placeholders: Map<String, String> = emptyMap()): String =
        cacheMessage(category, key, placeholders, cleanCache, formatHint = format) { raw, resolver ->
            serializeComponent(parseMixedMessage(raw, resolver, format))
        }

    fun getMessageStringList(category: String, key: String): List<String> =
        resolveStringList("$category.$key", listOf(yamlConfig, defaultYamlConfig)) ?: emptyList()

    fun signedChatRenderer(template: String, placeholders: Map<String, String> = emptyMap()): ChatRenderer =
        ChatRenderer.viewerUnaware { _, playerName, message ->
            val resolvers = placeholders.map { (key, value) -> Placeholder.parsed(key, value) } + listOf(
                Placeholder.component("player", playerName),
                Placeholder.component("message", message)
            )
            formatMixedTextToMiniMessage(template, TagResolver.resolver(*resolvers.toTypedArray()))
        }

    fun getSmartMessage(category: String, key: String, placeholders: Map<String, String> = emptyMap()): List<Component> {
        val cacheKey = composeKey("smart.$category", key, placeholders)
        val resolver = createResolver(placeholders)
        return complexCache.get(cacheKey) {
            renderSmartMessage(
                resolveSmartValue("$category.$key", listOf(yamlConfig, defaultYamlConfig)),
                prefix,
                resolver,
                category,
                key
            )
        }
    }

    fun getSmartMessageForLocale(locale: String, category: String, key: String, placeholders: Map<String, String> = emptyMap()): List<Component> {
        val normalizedLocale = normalizeLocale(locale)
        val cacheKey = "locale:$normalizedLocale|" + composeKey("smart.$category", key, placeholders)
        val resolver = createResolver(placeholders)
        return complexCache.get(cacheKey) {
            val selectedConfig = loadLocaleConfig(normalizedLocale)
            renderSmartMessage(
                resolveSmartValue("$category.$key", listOf(selectedConfig, yamlConfig, defaultYamlConfig)),
                getPrefixForConfig(selectedConfig),
                resolver,
                category,
                key
            )
        }
    }

    fun getSmartMessage(player: Player, category: String, key: String, placeholders: Map<String, String> = emptyMap()): List<Component> =
        if (automaticLanguage) getSmartMessageForLocale(player.locale().toLanguageTag(), category, key, placeholders)
        else getSmartMessage(category, key, placeholders)

    private fun renderSmartMessage(value: Any?, selectedPrefix: String, resolver: TagResolver, category: String, key: String): List<Component> =
        when (value) {
            is String -> listOf(formatMixedTextToMiniMessage("$selectedPrefix $value", resolver))
            is List<*> -> value.filterIsInstance<String>().map { formatMixedTextToMiniMessage(it, resolver) }
            else -> {
                logger.err("There was an error loading the smart message $key from category $category")
                listOf(Component.text("Message not found. Check console..."))
            }
        }

    fun formatLegacyText(message: String): Component = LegacyComponentSerializer.legacyAmpersand().deserialize(message)

    fun formatHexAndLegacyText(message: String): Component {
        val hexFormatted = message.replace("&#([a-fA-F0-9]{6})".toRegex()) {
            val hex = it.groupValues[1]
            "§x§${hex[0]}§${hex[1]}§${hex[2]}§${hex[3]}§${hex[4]}§${hex[5]}"
        }
        return LegacyComponentSerializer.legacySection().deserialize(hexFormatted)
    }

    /** Serializes a built component using the same codecs as configured messages. */
    fun componentToString(component: Component, format: MessageFormat): String =
        serializeComponent(ParsedMessage(component, format), format)

    /** Reports the source template format before dynamic component insertion or serialization. */
    fun getMessageFormat(category: String, key: String, includePrefix: Boolean = false): MessageFormat {
        val raw = resolveString("$category.$key", listOf(yamlConfig, defaultYamlConfig))
            ?: errorLogAndDefault(category, key)
        return detectMessageFormat(if (includePrefix) "$prefix $raw" else raw)
    }

    /** Renders server-configured rich fragments containing legacy codes and MiniMessage tags. */
    fun formatRichTextToComponent(message: String, resolver: TagResolver = TagResolver.empty()): Component =
        deserializeMiniMessage(normalizeRichText(message), resolver)

    /** Dynamic values are literal text; formatting remains confined to the language template. */
    fun stringMessageToComponentNoPrefixLiteral(category: String, key: String, placeholders: Map<String, String> = emptyMap()): Component {
        val raw = resolveString("$category.$key", listOf(yamlConfig, defaultYamlConfig))
            ?: errorLogAndDefault(category, key)
        val resolver = TagResolver.resolver(placeholders.map { (name, value) -> Placeholder.unparsed(name, value) })
        return parseMixedMessage(raw, resolver).component
    }

    private fun normalizeRichText(message: String): String {
        val colors = listOf("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white")
        val expanded = Regex("[&§]x((?:[&§][0-9a-f]){6})", RegexOption.IGNORE_CASE).replace(message) {
            "<#" + it.groupValues[1].filter { c -> c != '&' && c != '§' } + ">"
        }
        val hex = Regex("[&§]#([0-9a-f]{6})", RegexOption.IGNORE_CASE).replace(expanded) { "<#${it.groupValues[1]}>" }
        return Regex("[&§]([0-9a-fk-or])", RegexOption.IGNORE_CASE).replace(hex) {
            val code = it.groupValues[1].lowercase()[0]
            // A legacy color code resets decorations, unlike a MiniMessage color tag.
            if (code in '0'..'9' || code in 'a'..'f') {
                "<bold:false><italic:false><underlined:false><strikethrough:false><obfuscated:false><${colors[code.digitToInt(16)]}>"
            } else when (code) {
                'k' -> "<obfuscated>"; 'l' -> "<bold>"; 'm' -> "<strikethrough>"
                'n' -> "<underlined>"; 'o' -> "<italic>"; else -> "<reset>"
            }
        }
    }

    fun miniMessageFormat(message: String): Component = mM.deserialize(message)
    fun getANSIText(component: Component): String = ANSIComponentSerializer.ansi().serialize(component)
    fun getPlainText(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)

    private fun convertWithLegacySerializer(message: String, serializer: LegacyComponentSerializer): String {
        val pattern = Regex("(<[^>]+>|\\{[^}]+})")
        val result = StringBuilder()
        var lastIndex = 0
        for (match in pattern.findAll(message)) {
            val start = match.range.first
            if (start > lastIndex) result.append(mM.serialize(serializer.deserialize(message.substring(lastIndex, start))))
            result.append(match.value)
            lastIndex = match.range.last + 1
        }
        if (lastIndex < message.length) result.append(mM.serialize(serializer.deserialize(message.substring(lastIndex))))
        return result.toString()
    }

    private fun convertLegacyToMiniMessage(message: String): String =
        convertWithLegacySerializer(message, legacySerializerWithHex('&'))

    fun legacySerializer(message: String): String = LegacyComponentSerializer.legacySection().serialize(Component.text(message))
    fun legacyComponentSerializer(message: Component): String = LegacyComponentSerializer.legacySection().serialize(message)

    private fun convertSectionSignToMiniMessage(message: String): String =
        convertWithLegacySerializer(message, legacySerializerWithHex('§'))

    private fun legacySerializerWithHex(character: Char): LegacyComponentSerializer =
        LegacyComponentSerializer.builder().character(character).hexColors().build()

    private fun convertUnicodeEscapeSequences(input: String): String =
        input.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { matchResult ->
            String(Character.toChars(matchResult.groupValues[1].toInt(16)))
        }

    fun formatMixedTextToMiniMessage(message: String, resolver: TagResolver? = TagResolver.empty()): Component =
        parseMixedMessage(message, resolver).component

    fun formatTextToComponent(message: String, format: MessageFormat, resolver: TagResolver? = TagResolver.empty()): Component =
        parseMixedMessage(message, resolver, format).component

    fun formatMixedTextToLegacy(message: String, resolver: TagResolver? = TagResolver.empty()): String =
        serializeComponent(parseMixedMessage(message, resolver), MessageFormat.LEGACY_AMPERSAND)

    private fun parseMixedMessage(message: String, resolver: TagResolver?, formatHint: MessageFormat? = null): ParsedMessage {
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

    private fun deserializeMiniMessage(message: String, resolver: TagResolver?): Component =
        if (resolver != null) mM.deserialize(message, resolver) else mM.deserialize(message)

    private fun detectMessageFormat(message: String): MessageFormat = when {
        "<[^>]+>".toRegex().containsMatchIn(message) -> MessageFormat.MINI_MESSAGE
        "§[0-9a-fk-orA-FK-OR]".toRegex().containsMatchIn(message) -> MessageFormat.LEGACY_SECTION
        "&[0-9a-fk-orA-FK-OR]".toRegex().containsMatchIn(message) -> MessageFormat.LEGACY_AMPERSAND
        else -> MessageFormat.PLAIN
    }

    private fun serializeComponent(parsedMessage: ParsedMessage, targetFormat: MessageFormat = parsedMessage.sourceFormat): String =
        when (targetFormat) {
            MessageFormat.MINI_MESSAGE -> mM.serialize(parsedMessage.component)
            MessageFormat.LEGACY_SECTION -> legacySerializerWithHex('§').serialize(parsedMessage.component)
            MessageFormat.LEGACY_AMPERSAND -> legacySerializerWithHex('&').serialize(parsedMessage.component)
            MessageFormat.PLAIN -> getPlainText(parsedMessage.component)
        }

    private data class ParsedMessage(val component: Component, val sourceFormat: MessageFormat)

    enum class MessageFormat {
        MINI_MESSAGE,
        LEGACY_SECTION,
        LEGACY_AMPERSAND,
        PLAIN
    }
}
