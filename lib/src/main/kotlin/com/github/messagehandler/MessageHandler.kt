package com.github.messagehandler

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.io.File
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

/**
 * Kotlin implementation of a YAML backed message handler inspired by the
 * simplified proposal in the repository discussion. It keeps the original API
 * surface but removes asynchronous loading to favour straightforward and
 * predictable behaviour.
 */
public class MessageHandler(
    private val resources: ResourceProvider,
    private val meta: PluginMetaProvider,
    private val logger: MessageLogger = ConsoleMessageLogger
) {
    private val language: String = resources.getConfigValue("language", "EN").lowercase()
    private val messagesFile: File = File(resources.dataFolder, "lang/messages_${language}.yml")

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

    private val miniMessage: MiniMessage = MiniMessage.miniMessage()

    @Volatile
    private var yamlConfig: MutableMap<String, Any?> = linkedMapOf()

    @Volatile
    private var prefix: String = "[${'$'}{meta.name}]"

    init {
        copyDefaultAndSync()
        reloadMessages()
    }

    private fun loadYaml(): MutableMap<String, Any?> = loadYamlFromFile(messagesFile)

    /** Displays information about the loaded language file author in console. */
    public fun initial() {
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
            targetFile.parentFile?.mkdirs()
            resources.saveResource(resourcePath, false)
        }

        val defaultStream = resources.getResourceStream(resourcePath)
            ?: run {
                logger.err("Default language file for $language not found!")
                return
            }

        val defaultCfg = defaultStream.reader(StandardCharsets.UTF_8).use { loadYamlFromReader(it) }
        val currentCfg = loadYamlFromFile(targetFile)
        val updated = syncMaps(defaultCfg, currentCfg)

        if (updated) {
            logger.success("Updating messages_${language.lowercase()}.yml with missing entries.")
            saveYamlToFile(targetFile, currentCfg)
        }
    }

    /** Reloads YAML messages and resets cached values. */
    public fun reloadMessages() {
        yamlConfig = loadYaml()
        prefix = getString("prefix") ?: "[${meta.name}]"
        componentCache.invalidateAll()
        simpleCache.invalidateAll()
        cleanCache.invalidateAll()
        complexCache.invalidateAll()
    }

    private fun errorLogAndDefault(category: String, key: String): String {
        logger.err("Nie można załadować wiadomości $key z kategorii $category")
        return "Message not found!"
    }

    public fun getPrefix(): String = prefix

    private fun createResolver(placeholders: Map<String, String>): TagResolver {
        if (placeholders.isEmpty()) return TagResolver.empty()
        val resolvers = placeholders.map { (k, v) -> Placeholder.parsed(k, v) }
        return TagResolver.resolver(resolvers)
    }

    private fun composeKey(category: String, key: String, placeholders: Map<String, String>): String =
        buildString {
            append(category).append('.').append(key)
            if (placeholders.isNotEmpty()) {
                append('?')
                placeholders.entries.sortedBy { it.key }
                    .joinTo(this, "&") { "${it.key}=${it.value}" }
            }
        }

    public fun getMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return componentCache.get(cacheKey) {
            val raw = getString("$category.$key") ?: errorLogAndDefault(category, key)
            val full = "$prefix $raw"
            miniMessage.deserialize(full, resolver)
        }
    }

    public fun getSimpleMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return simpleCache.get(cacheKey) {
            val raw = getString("$category.$key") ?: errorLogAndDefault(category, key)
            val parsed = miniMessage.deserialize(raw, resolver)
            val serialized = miniMessage.serialize(parsed)
            "$prefix $serialized"
        }
    }

    public fun getCleanMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): String {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return cleanCache.get(cacheKey) {
            val raw = getString("$category.$key") ?: errorLogAndDefault(category, key)
            val parsed = miniMessage.deserialize(raw, resolver)
            miniMessage.serialize(parsed)
        }
    }

    public fun getLogMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return componentCache.get("log.$cacheKey") {
            val raw = getString("$category.$key") ?: errorLogAndDefault(category, key)
            formatMixedTextToMiniMessage(raw, resolver)
        }
    }

    @Deprecated(
        message = "Use getSmartMessage instead",
        replaceWith = ReplaceWith("getSmartMessage(category, key, placeholders)")
    )
    public fun getComplexMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): List<Component> {
        val cacheKey = composeKey(category, key, placeholders)
        val resolver = createResolver(placeholders)
        return complexCache.get(cacheKey) {
            val list = getStringList("$category.$key")
            if (list.isEmpty()) {
                listOf(Component.text("Message list not found. Check console..."))
            } else {
                list.map { raw -> formatMixedTextToMiniMessage(raw, resolver) }
            }
        }
    }

    public fun getSmartMessage(
        category: String,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): List<Component> {
        val cacheKey = composeKey("smart.$category", key, placeholders)
        val resolver = createResolver(placeholders)
        return complexCache.get(cacheKey) {
            val path = "$category.$key"
            when (val rawValue = getValue(path)) {
                is String -> listOf(
                    formatMixedTextToMiniMessage("$prefix $rawValue", resolver)
                )
                is List<*> -> rawValue.filterIsInstance<String>().map { line ->
                    formatMixedTextToMiniMessage(line, resolver)
                }
                else -> {
                    logger.err("There was an error loading the smart message $key from category $category")
                    listOf(Component.text("Message not found. Check console..."))
                }
            }
        }
    }

    public fun getReasons(category: String, key: String): List<String> =
        getStringList("$category.$key")

    public fun formatLegacyText(message: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(message)

    @Deprecated(
        message = "Use formatLegacyText instead",
        replaceWith = ReplaceWith("formatLegacyText(message)")
    )
    public fun formatLegacyTextBukkit(message: String): String {
        val component = formatLegacyText(message)
        return LegacyComponentSerializer.legacySection().serialize(component)
    }

    public fun formatHexAndLegacyText(message: String): Component {
        val hexFormatted = message.replace("&#([a-fA-F0-9]{6})".toRegex()) {
            val hex = it.groupValues[1]
            "§x§${hex[0]}§${hex[1]}§${hex[2]}§${hex[3]}§${hex[4]}§${hex[5]}"
        }
        return LegacyComponentSerializer.legacySection().deserialize(hexFormatted)
    }

    public fun miniMessageFormat(message: String): Component =
        miniMessage.deserialize(message)

    public fun getANSIText(component: Component): String =
        ANSIComponentSerializer.ansi().serialize(component)

    public fun getPlainText(component: Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)

    private fun formatMixedTextToMiniMessage(
        message: String,
        resolver: TagResolver = TagResolver.empty()
    ): Component {
        val unicodeResolved = convertUnicodeEscapeSequences(message)
        val sectionConverted = convertSectionSignToMiniMessage(unicodeResolved)
        val ampersandConverted = convertLegacyToMiniMessage(sectionConverted)
        return miniMessage.deserialize(ampersandConverted, resolver)
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
                result.append(miniMessage.serialize(component))
            }
            result.append(match.value)
            lastIndex = match.range.last + 1
        }
        if (lastIndex < message.length) {
            val component = serializer.deserialize(message.substring(lastIndex))
            result.append(miniMessage.serialize(component))
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

    private fun convertUnicodeEscapeSequences(input: String): String =
        input.replace(Regex("""\\\\u([0-9A-Fa-f]{4})""")) { matchResult ->
            val codePoint = matchResult.groupValues[1].toInt(16)
            String(Character.toChars(codePoint))
        }

    private fun loadYamlFromFile(file: File): MutableMap<String, Any?> {
        if (!file.exists()) return linkedMapOf()
        return file.reader(StandardCharsets.UTF_8).use { reader ->
            loadYamlFromReader(reader)
        }
    }

    private fun loadYamlFromReader(reader: Reader): MutableMap<String, Any?> {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val yaml = Yaml(options)
        val loaded = yaml.load<Any?>(reader)
        return when (loaded) {
            is Map<*, *> -> convertMap(loaded)
            else -> linkedMapOf()
        }
    }

    private fun saveYamlToFile(file: File, data: Map<String, Any?>) {
        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
        }
        val yaml = Yaml(dumperOptions)
        file.parentFile?.mkdirs()
        file.writer(StandardCharsets.UTF_8).use { writer ->
            yaml.dump(prepareForDump(data), writer)
        }
    }

    private fun syncMaps(
        defaultMap: Map<String, Any?>,
        currentMap: MutableMap<String, Any?>
    ): Boolean {
        var updated = false
        for ((key, defaultValue) in defaultMap) {
            val existing = currentMap[key]
            if (existing == null) {
                currentMap[key] = deepCopy(defaultValue)
                updated = true
            } else if (defaultValue is Map<*, *>) {
                val defaultChild = convertMap(defaultValue)
                val ensured = ensureMutableMap(existing)
                if (ensured == null) {
                    currentMap[key] = deepCopy(defaultValue)
                    updated = true
                    continue
                }
                val (currentChild, replaced) = ensured
                if (replaced) {
                    currentMap[key] = currentChild
                }
                if (syncMaps(defaultChild, currentChild)) {
                    updated = true
                }
            }
        }
        return updated
    }

    private fun ensureMutableMap(value: Any?): Pair<MutableMap<String, Any?>, Boolean>? = when (value) {
        is MutableMap<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            Pair(value as MutableMap<String, Any?>, false)
        }
        is Map<*, *> -> Pair(convertMap(value), true)
        else -> null
    }

    private fun convertMap(source: Map<*, *>): MutableMap<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for ((key, value) in source) {
            val stringKey = key?.toString() ?: continue
            result[stringKey] = deepCopy(value)
        }
        return result
    }

    private fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> convertMap(value)
        is List<*> -> value.map { deepCopy(it) }
        else -> value
    }

    private fun prepareForDump(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val result = linkedMapOf<String, Any?>()
            for ((key, v) in value) {
                val stringKey = key?.toString() ?: continue
                result[stringKey] = prepareForDump(v)
            }
            result
        }
        is List<*> -> value.map { prepareForDump(it) }
        else -> value
    }

    private fun getValue(path: String): Any? {
        var current: Any? = yamlConfig
        for (segment in path.split('.')) {
            if (segment.isEmpty()) continue
            current = (current as? Map<String, Any?>)?.get(segment) ?: return null
        }
        return current
    }

    private fun getString(path: String): String? =
        (getValue(path) as? String)?.takeIf { it.isNotEmpty() }

    private fun getStringList(path: String): List<String> = when (val value = getValue(path)) {
        is List<*> -> value.filterIsInstance<String>()
        is String -> listOf(value)
        else -> emptyList()
    }
}
