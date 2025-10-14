package com.github.messagehandler

import java.io.Reader
import java.nio.file.Files
import java.util.Locale
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

internal class YamlMessageLoader(
    private val config: MessageHandlerConfig
) {
    private val yaml = Yaml(LoaderOptions().apply { isProcessComments = false })

    fun load(locale: Locale): MessageBundle {
        val path = config.resolveFile(locale)
        val data = if (Files.exists(path)) {
            Files.newBufferedReader(path).use { reader -> parse(reader) }
        } else {
            emptyMap<String, MessageEntry>()
        }
        return MessageBundle(locale, data)
    }

    private fun parse(reader: Reader): Map<String, MessageEntry> {
        val root = yaml.load<Any?>(reader)
        if (root !is Map<*, *>) {
            return emptyMap()
        }
        val entries = mutableMapOf<String, MessageEntry>()
        collectEntries(root, emptyList(), entries)
        return entries
    }

    private fun collectEntries(
        node: Map<*, *>,
        path: List<String>,
        output: MutableMap<String, MessageEntry>
    ) {
        val normalized = node.entries.associate { (key, value) -> key.toString() to value }
        if (normalized.containsKey("value")) {
            val rawValue = normalized["value"]?.toString() ?: return
            val formatValue = normalized["format"]?.toString()
            val format = MessageFormat.from(formatValue, config.defaultFormat)
            output[path.joinToString(".")] = MessageEntry(rawValue, format)
        }

        normalized.forEach { (key, value) ->
            if (key == "value" || key == "format") return@forEach
            when (value) {
                is Map<*, *> -> collectEntries(value, path + key, output)
                is Iterable<*> -> {
                    val raw = value.joinToString("\n") { it?.toString() ?: "" }
                    output[(path + key).joinToString(".")] = MessageEntry(raw, config.defaultFormat)
                }
                null -> {}
                else -> output[(path + key).joinToString(".")] = MessageEntry(value.toString(), config.defaultFormat)
            }
        }
    }
}
