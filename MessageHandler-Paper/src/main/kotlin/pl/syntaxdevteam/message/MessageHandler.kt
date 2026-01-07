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
/**
 * Centralny serwis do wczytywania, buforowania i formatowania wiadomości z plików językowych.
 *
 * Klasa odpowiada za:
 * - synchronizację domyślnych plików językowych z katalogiem danych pluginu,
 * - wykrywanie przestarzałych tłumaczeń oraz ich automatyczny backup,
 * - parsowanie tekstu złożonego z MiniMessage, starych kodów kolorów (§ oraz &)
 *   oraz czystego tekstu,
 * - buforowanie wyników serializacji w kilku postaciach (Component, String, listy),
 * - udostępnianie wygodnych metod do pobierania wiadomości z prefiksem i bez.
 */
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

    /**
        * Ładuje bieżący plik YAML z wiadomościami z dysku.
        *
        * Funkcja jest izolowana, aby można ją było łatwo podmienić w testach lub przy
        * przyszłych zmianach sposobu wczytywania konfiguracji.
        */
    private fun loadYaml(): FileConfiguration =
        YamlConfiguration.loadConfiguration(messagesFile)

    /**
     * Loguje informację o autorze pliku językowego po pierwszym wczytaniu handlera.
     *
     * Funkcja jest przewidziana do jednorazowego wywołania po konstrukcji – pozwala
     * zweryfikować, że plik został wykryty, a język został prawidłowo dobrany do
     * konfiguracji.
     */
    fun initial() {
        val author = getAuthorFromYamlComment() ?: "SyntaxDevTeam"
        logger.success("<gray>Loaded \"$language\" language file by: <white><b>$author</b></white>")
    }

    /**
     * Odczytuje autora pliku językowego z komentarza w pierwszych liniach pliku.
     *
     * @return wartość po `# Author:` lub `null`, gdy plik nie istnieje albo nie ma komentarza.
     */
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

    /**
     * Ekstrahuje oznaczenie wersji z nagłówka pliku językowego, jeśli jest obecne.
     *
     * @param langFile plik z katalogu lang, z którego ma być czytana wersja.
     * @return wersja w formacie semantycznym albo `null`, gdy nie znaleziono nagłówka.
     */
    private fun getVersionFromYamlHeader(langFile: File): String? {
        if (!langFile.exists()) return null
        val versionRegex = Regex("""#\s*(?:ver(?:sion)?[:.]?\s*)?(\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE)

        langFile.useLines { lines ->
            for ((index, line) in lines.withIndex()) {
                if (index >= 2) break
                val trimmed = line.trim()
                val match = versionRegex.find(trimmed)
                if (match != null) return match.groupValues[1]
            }
        }
        return null
    }

    /**
     * Sprawdza, czy numer wersji jest starszy od wersji referencyjnej.
     *
     * Porównanie uwzględnia różne długości wersji (np. 1.2 kontra 1.2.0) przez
     * uzupełnienie brakujących segmentów zerami.
     *
     * @param version wersja z pliku.
     * @param reference wersja odniesienia, do której porównujemy.
     * @return `true`, jeśli `version` jest starsza, `false` w pozostałych przypadkach.
     */
    private fun isVersionLowerThan(version: String, reference: String): Boolean {
        fun parseVersion(value: String): List<Int>? {
            val parts = value.split(".")
            if (parts.isEmpty()) return null
            return parts.map {
                it.toIntOrNull() ?: return null
            }
        }

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

    /**
     * Określa, czy plik językowy powinien zostać zastąpiony domyślną wersją
     * ze względu na zbyt niską wersję.
     *
     * @param langFile istniejący plik w katalogu danych.
     * @return `true`, jeśli należy wykonać backup i podmianę, `false` w przeciwnym razie.
     */
    private fun shouldReplaceOutdatedLanguage(langFile: File): Boolean {
        val version = getVersionFromYamlHeader(langFile) ?: return false
        if (!isVersionLowerThan(version, "2.0.0")) {
            logger.success("Detected language file version $version. No replacement required.")
            return false
        }
        return true
    }

    /**
     * Synchronizuje domyślne pliki językowe z katalogiem danych pluginu.
     *
     * Operacja:
     * 1. Tworzy kopię zapasową starych plików, jeśli wykryje wersję poniżej 2.0.0.
     * 2. Kopiuje domyślny plik, jeśli nie istnieje.
     * 3. Uzupełnia brakujące wpisy w aktualnym pliku na podstawie domyślnego.
     * 4. Informuje loggerem o każdym z kroków.
     */
    private fun copyDefaultAndSync() {
        val langDirectory = File(resources.dataFolder, "lang")
        val resourcePath = "lang/messages_${language.lowercase()}.yml"
        val targetFile = File(resources.dataFolder, resourcePath)

        if (langDirectory.exists() && targetFile.exists() && shouldReplaceOutdatedLanguage(targetFile)) {
            val backupDirectory = File(resources.dataFolder, "lang_old_ver")
            if (backupDirectory.exists()) {
                backupDirectory.deleteRecursively()
            }

            if (langDirectory.renameTo(backupDirectory)) {
                logger.success("Detected outdated language files (version below 2.0.0). Backed up current lang directory to lang_old_ver.")
            } else {
                logger.err("Failed to backup outdated language directory to lang_old_ver.")
            }
        }

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

    /**
     * Ponownie wczytuje plik językowy z dysku i czyści wszystkie cache.
     *
     * Wywołanie wymagane po zmianie konfiguracji, aby kolejne zapytania korzystały
     * z najnowszych wartości.
     */
    fun reloadMessages() {
        yamlConfig = loadYaml()
        prefix = yamlConfig.getString("prefix") ?: "[${meta.name}]"
        componentCache.invalidateAll()
        simpleCache.invalidateAll()
        cleanCache.invalidateAll()
        complexCache.invalidateAll()
    }

    /**
     * Loguje błąd, gdy wpis nie został znaleziony, i zwraca domyślny tekst.
     *
     * @param category sekcja YAML, w której szukano.
     * @param key nazwa wiadomości.
     */
    private fun errorLogAndDefault(category: String, key: String): String {
        logger.err("Nie można załadować wiadomości $key z kategorii $category")
        return "Message not found!"
    }

    /**
     * Zwraca aktualny prefiks wiadomości wczytany z konfiguracji.
     *
     * Prefiks jest aktualizowany podczas [reloadMessages] i doklejany do większości metod
     * `stringMessage*`, dzięki czemu pojedyncze wiadomości pozostają spójne stylistycznie.
     */
    fun getPrefix(): String = prefix

    /**
     * Buduje [TagResolver] z mapy placeholderów w formie tekstowej.
     *
     * Używany w każdej metodzie konwertującej wiadomości, aby parsowanie MiniMessage
     * mogło wstawić dynamiczne wartości.
     *
     * @param placeholders para klucz-wartość przekazywana do MiniMessage.
     * @return resolver gotowy do użycia w [MiniMessage.deserialize].
     */
    private fun createResolver(placeholders: Map<String, String>): TagResolver {
        if (placeholders.isEmpty()) return TagResolver.empty()
        val resolvers = placeholders.map { (k, v) -> Placeholder.parsed(k, v) }
        return TagResolver.resolver(resolvers)
    }

    /**
     * Skleja kategorię, klucz oraz placeholdery w deterministyczny identyfikator
     * używany w kluczach cache.
     *
     * @param category sekcja YAML.
     * @param key nazwa wiadomości.
     * @param placeholders placeholdery użyte przy formatowaniu.
     */
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

    /**
     * Wspólna ścieżka obsługi cache dla wszystkich wariantów zwracających wiadomości.
     *
     * @param category sekcja YAML.
     * @param key nazwa wiadomości.
     * @param placeholders placeholdery do wypełnienia w treści.
     * @param cache instancja cache dla typu wyjściowego.
     * @param cacheKeyPrefix opcjonalny prefiks rozróżniający przestrzenie cache (np. logi).
     * @param formatHint sugerowany format źródłowy, gdy nie chcemy autodetekcji.
     * @param transform funkcja przekształcająca surowy tekst na rezultat.
     */
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

    /**
     * Zwraca wiadomość jako [Component] z automatycznie dodanym prefiksem.
     *
     * Korzysta z cache, więc kolejne odczyty są szybkie, a placeholdery
     * zostają wstawione za pomocą MiniMessage.
     */
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

    /**
     * Jak [stringMessageToComponent], ale z narzuconym formatem źródłowym
     * (MiniMessage, legacy lub plain), co pozwala ominąć autodetekcję.
     */
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

    /**
     * Buduje [Component] bez doklejania prefiksu, co przydaje się w logach
     * lub wiadomościach wewnętrznych.
     */
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

    /**
     * Wariant [stringMessageToComponentNoPrefix] z wymuszeniem formatu źródłowego.
     */
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

    /**
     * Zwraca wiadomość jako sformatowany String z prefiksem, zachowując
     * oryginalny format (MiniMessage, legacy lub plain).
     */
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

    /**
     * Wariant [stringMessageToString] z jawnym formatem źródłowym, który pomija autodetekcję.
     */
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

    /**
     * Zwraca wiadomość jako String bez prefiksu, zachowując format źródłowy.
     */
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

    /**
     * Wariant [stringMessageToStringNoPrefix] z wymuszonym formatem źródłowym.
     */
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

    /**
     * Zwraca listę surowych wpisów tekstowych z konfiguracji (bez parsowania).
     */
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

    /**
     * Konwertuje tekst w składni legacy (`&`) na [Component].
     */
    fun formatLegacyText(message: String): Component {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message)
    }

    /**
     * Parsuje tekst zawierający mieszankę kodów `§` oraz notacji hex (`&#RRGGBB`)
     * do komponentu Adventure.
     */
    fun formatHexAndLegacyText(message: String): Component {
        val hexFormatted = message.replace("&#([a-fA-F0-9]{6})".toRegex()) {
            val hex = it.groupValues[1]
            "§x§${hex[0]}§${hex[1]}§${hex[2]}§${hex[3]}§${hex[4]}§${hex[5]}"
        }

        return LegacyComponentSerializer.legacySection().deserialize(hexFormatted)
    }

    /**
     * Bezpośrednio deserializuje podany tekst MiniMessage na [Component].
     */
    fun miniMessageFormat(message: String): Component {
        return mM.deserialize(message)
    }

    /**
     * Serializuje komponent do formatu ANSI, przydatnego w konsoli.
     */
    fun getANSIText(component: Component): String {
        return ANSIComponentSerializer.ansi().serialize(component)
    }

    /**
     * Zwraca czysty tekst z komponentu, ignorując formatowanie.
     */
    fun getPlainText(component: Component): String {
        return PlainTextComponentSerializer.plainText().serialize(component)
    }

    /**
     * Konwertuje mieszane formaty legacy na MiniMessage, zachowując oryginalne tagi MiniMessage.
     *
     * @param message tekst zawierający potencjalne fragmenty MiniMessage i legacy.
     * @param serializer serializer odpowiedzialny za interpretację kodów kolorów.
     */
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

    /**
     * Zamienia kody `&` na składnię MiniMessage.
     */
    private fun convertLegacyToMiniMessage(message: String): String {
        val serializer = LegacyComponentSerializer.legacyAmpersand()
            .toBuilder()
            .hexColors()
            .build()
        return convertWithLegacySerializer(message, serializer)
    }

    /**
     * Zamienia kody `§` na składnię MiniMessage.
     */
    private fun convertSectionSignToMiniMessage(message: String): String {
        val serializer = LegacyComponentSerializer.legacySection()
            .toBuilder()
            .hexColors()
            .build()
        return convertWithLegacySerializer(message, serializer)
    }

    /**
     * Rozkodowuje sekwencje `\uXXXX` w tekście przed dalszym parsowaniem.
     */
    private fun convertUnicodeEscapeSequences(input: String): String {
        return input.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { matchResult ->
            val codePoint = matchResult.groupValues[1].toInt(16)
            String(Character.toChars(codePoint))
        }
    }

    /**
     * Główne wejście do parsowania tekstu mieszanego na komponent MiniMessage.
     *
     * @param message treść wiadomości.
     * @param resolver zestaw placeholderów MiniMessage.
     */
    fun formatMixedTextToMiniMessage(message: String, resolver: TagResolver? = TagResolver.empty()): Component {
        return parseMixedMessage(message, resolver).component
    }

    /**
     * Wymusza parsowanie podanego tekstu w określonym [MessageFormat].
     */
    fun formatTextToComponent(
        message: String,
        format: MessageFormat,
        resolver: TagResolver? = TagResolver.empty()
    ): Component {
        return parseMixedMessage(message, resolver, format).component
    }

    /**
     * Zwraca wynik parsowania w postaci tekstu legacy (`&`), niezależnie od wejścia.
     *
     * @param message treść wiadomości.
     * @param resolver placeholdery MiniMessage przekazywane do parsera.
     */
    fun formatMixedTextToLegacy(message: String, resolver: TagResolver? = TagResolver.empty()): String {
        val parsed = parseMixedMessage(message, resolver)
        return serializeComponent(parsed, MessageFormat.LEGACY_AMPERSAND)
    }

    /**
     * Parsuje wiadomość, wykrywa jej format (lub korzysta z [formatHint]) i zwraca
     * zarówno komponent, jak i źródłowy format.
     */
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

    /**
     * Deserializuje MiniMessage, opcjonalnie z resolverem placeholderów.
     */
    private fun deserializeMiniMessage(message: String, resolver: TagResolver?): Component {
        return if (resolver != null) {
            mM.deserialize(message, resolver)
        } else {
            mM.deserialize(message)
        }
    }

    /**
     * Na podstawie zawartości tekstu zgaduje format źródłowy (MiniMessage, legacy lub plain).
     */
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

    /**
     * Serializuje [ParsedMessage] do formatu docelowego, domyślnie zachowując format źródłowy.
     */
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

    /**
     * Struktura pomocnicza łącząca komponent z wykrytym formatem źródłowym.
     */
    private data class ParsedMessage(
        val component: Component,
        val sourceFormat: MessageFormat
    )

    /**
     * Typy formatów wiadomości obsługiwane przez handler.
     */
    enum class MessageFormat {
        MINI_MESSAGE,
        LEGACY_SECTION,
        LEGACY_AMPERSAND,
        PLAIN
    }
}
