package com.github.messagehandler

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

/**
 * Immutable configuration for the [MessageHandler].
 */
public data class MessageHandlerConfig(
    val baseDirectory: Path,
    val defaultLocale: Locale = Locale.getDefault(),
    val fallbackLocale: Locale? = null,
    val filePattern: String = "messages_%s.yml",
    val defaultFormat: MessageFormat = MessageFormat.MINI_MESSAGE,
    val cacheDuration: Duration = Duration.ofMinutes(5),
    val maximumCachedLocales: Long = 16,
    val prefixPath: List<String> = listOf("prefix"),
    val miniMessage: MiniMessage = MiniMessage.miniMessage(),
    val missingMessageProvider: (locale: Locale, path: List<String>) -> Component = { locale, path ->
        Component.text("Missing message '${path.joinToString(".")}' for locale ${locale.toLanguageTag()}")
    }
) {
    init {
        require(filePattern.contains("%s")) { "filePattern must contain %s placeholder" }
        require(maximumCachedLocales > 0) { "maximumCachedLocales must be greater than zero" }
    }

    /**
     * Resolves the language file path for the supplied [locale].
     */
    public fun resolveFile(locale: Locale): Path {
        val formatted = locale.toLanguageTag().lowercase().replace('-', '_')
        val fileName = String.format(filePattern, formatted)
        val path = baseDirectory.resolve(fileName)
        if (!Files.exists(path.parent)) {
            Files.createDirectories(path.parent)
        }
        return path
    }
}
