package pl.syntaxdevteam.message

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MessageHandlerMixedFormattingTest {
    @Test
    fun `Vulcan reason can mix ampersand section and MiniMessage formatting`() = withHandler { handler ->
        for (message in listOf(
            "&cPowód &8» &7§c[Vulcan] Unfair Advantage",
            "<red>Powód <dark_gray>» <gray>§c[Vulcan] Unfair Advantage",
            "&cPowód &8» &7\\u00a7c[Vulcan] Unfair Advantage"
        )) {
            val result = handler.formatMixedTextToMiniMessage(message)
            assertEquals("Powód » [Vulcan] Unfair Advantage", handler.getPlainText(result))
            assertEquals(NamedTextColor.RED, styleOf(result, "[Vulcan]").color())
            assertEquals(NamedTextColor.DARK_GRAY, styleOf(result, "»").color())
        }
    }

    @Test
    fun `smart messages and cached APIs normalize legacy reasons from plugins`() = withHandler { handler ->
        val values = mapOf("reason" to "§c[Vulcan] Unfair Advantage")
        val expected = "Powód » [Vulcan] Unfair Advantage"
        for (key in listOf("kick", "mini")) {
            repeat(2) {
                val result = handler.stringMessageToComponentNoPrefix("messages", key, values)
                assertEquals(expected, handler.getPlainText(result))
                assertEquals(NamedTextColor.RED, styleOf(result, "[Vulcan]").color())
                assertEquals("[MH] $expected", handler.getPlainText(handler.getSmartMessage("messages", key, values).single()))
                assertEquals("[MH] $expected", handler.getPlainText(handler.getSmartMessageForLocale("pl_PL", "messages", key, values).single()))
                val serialized = handler.stringMessageToStringNoPrefix("messages", key, values)
                assertEquals(expected, handler.getPlainText(handler.formatMixedTextToMiniMessage(serialized)))
            }
        }
        val line = handler.getSmartMessage("messages", "kick-list", values).single()
        assertEquals(expected, handler.getPlainText(line))
        assertEquals(NamedTextColor.RED, styleOf(line, "[Vulcan]").color())
        val changed = handler.getSmartMessage("messages", "kick-list", mapOf("reason" to "§aAllowed")).single()
        assertEquals("Powód » Allowed", handler.getPlainText(changed))
        assertEquals(NamedTextColor.GREEN, styleOf(changed, "Allowed").color())
    }

    @Test
    fun `external and nested parsed resolvers normalize their values before parsing`() = withHandler { handler ->
        val resolver = TagResolver.resolver(
            Placeholder.parsed("reason", "&7<detail>"),
            Placeholder.parsed("detail", "\\u00a7c[Vulcan] <bold>Unfair</bold> Advantage")
        )
        val result = handler.formatMixedTextToMiniMessage("<gray>Powód » <reason>", resolver)
        assertEquals("Powód » [Vulcan] Unfair Advantage", handler.getPlainText(result))
        assertEquals(NamedTextColor.RED, styleOf(result, "[Vulcan]").color())
        assertEquals(TextDecoration.State.TRUE, styleOf(result, "Unfair").decoration(TextDecoration.BOLD))
    }

    @Test
    fun `unparsed and component placeholders retain literal legacy codes`() = withHandler { handler ->
        val value = "§c<red>&a literal"
        for (resolver in listOf(
            Placeholder.unparsed("reason", value),
            Placeholder.component("reason", Component.text(value))
        )) {
            val result = handler.formatMixedTextToMiniMessage("&7<reason>", resolver)
            assertEquals(value, handler.getPlainText(result))
            assertEquals(NamedTextColor.GRAY, styleOf(result, "literal").color())
        }
    }

    @Test
    fun `legacy styles span tags and color codes reset decorations including RGB`() = withHandler { handler ->
        val result = handler.formatMixedTextToMiniMessage("&lBold <gold>gold</gold> §cRed")
        assertEquals("Bold gold Red", handler.getPlainText(result))
        assertEquals(TextDecoration.State.TRUE, styleOf(result, "gold").decoration(TextDecoration.BOLD))
        assertEquals(TextDecoration.State.FALSE, styleOf(result, "Red").decoration(TextDecoration.BOLD))
        for (code in listOf("&#123456", "§#123456", "&x&1&2&3&4&5&6", "§x§1§2§3§4§5§6")) {
            val standalone = handler.formatMixedTextToMiniMessage(code + "Hex")
            assertEquals("Hex", handler.getPlainText(standalone))
            assertEquals(TextColor.color(0x123456), styleOf(standalone, "Hex").color())
            val hex = handler.formatMixedTextToMiniMessage("&lBold ${code}Hex")
            assertEquals("Bold Hex", handler.getPlainText(hex))
            assertEquals(TextColor.color(0x123456), styleOf(hex, "Hex").color())
            assertEquals(TextDecoration.State.FALSE, styleOf(hex, "Hex").decoration(TextDecoration.BOLD))
        }
        val reset = handler.formatMixedTextToMiniMessage("§c§lBefore &rAfter")
        assertEquals("Before After", handler.getPlainText(reset))
        assertEquals(null, styleOf(reset, "After").color())
        assertFalse(styleOf(reset, "After").hasDecoration(TextDecoration.BOLD))
    }

    @Test
    fun `explicit rich formats accept mixed codes and plain format stays literal`() = withHandler { handler ->
        val message = "&cRed §aGreen <gold>Gold</gold>"
        for (format in MessageHandler.MessageFormat.entries.filter { it != MessageHandler.MessageFormat.PLAIN }) {
            val result = handler.formatTextToComponent(message, format)
            assertEquals("Red Green Gold", handler.getPlainText(result))
            assertEquals(NamedTextColor.GREEN, styleOf(result, "Green").color())
        }
        assertEquals(message, handler.getPlainText(handler.formatTextToComponent(message, MessageHandler.MessageFormat.PLAIN)))
        assertEquals("Red Green Gold", handler.getPlainText(handler.miniMessageFormat(message)))
        val legacy = handler.formatMixedTextToLegacy(message)
        assertEquals("Red Green Gold", handler.getPlainText(handler.formatMixedTextToMiniMessage(legacy)))
    }

    @Test
    fun `MiniMessage events and ordinary section signs survive normalization`() = withHandler { handler ->
        val result = handler.formatMixedTextToMiniMessage(
            "<click:run_command:'/help'><hover:show_text:'<yellow>Help'>§aClick</hover></click> § test & text"
        )
        assertEquals("Click § test & text", handler.getPlainText(result))
        assertEquals(ClickEvent.runCommand("/help"), styleOf(result, "Click").clickEvent())
        val hover = styleOf(result, "Click").hoverEvent()?.value() as Component
        assertEquals("Help", handler.getPlainText(hover))
        assertEquals(NamedTextColor.YELLOW, styleOf(hover, "Help").color())
    }

    @Test
    fun `tag arguments keep ampersands while hover text supports legacy colors`() = withHandler { handler ->
        val result = handler.formatMixedTextToMiniMessage(
            "<click:run_command:'/say &cText'><hover:show_text:'&aHelp'>§aClick</hover></click>"
        )
        assertEquals(ClickEvent.runCommand("/say &cText"), styleOf(result, "Click").clickEvent())
        val hover = styleOf(result, "Click").hoverEvent()?.value() as Component
        assertEquals("Help", handler.getPlainText(hover))
        assertEquals(NamedTextColor.GREEN, styleOf(hover, "Help").color())
        val url = "https://example.invalid/?a=1&color=red"
        val linked = handler.formatMixedTextToMiniMessage("<click:open_url:'$url'>Link</click>")
        assertEquals(ClickEvent.openUrl(url), styleOf(linked, "Link").clickEvent())
    }

    private fun styleOf(component: Component, text: String, parent: Style = Style.empty()): Style {
        fun find(node: Component, inherited: Style): Style? {
            val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            if (node is TextComponent && node.content().contains(text)) return style
            return node.children().firstNotNullOfOrNull { find(it, style) }
        }
        return requireNotNull(find(component, parent)) { "Missing text: $text" }
    }

    private fun withHandler(block: (MessageHandler) -> Unit) {
        val folder = Files.createTempDirectory("mh-mixed").toFile()
        try {
            val language = """
                prefix: "<gold>[MH]</gold>"
                messages:
                  kick: "&cPowód &8» &7<reason>"
                  mini: "<gray>Powód » <reason></gray>"
                  kick-list:
                    - "&cPowód &8» &7<reason>"
            """.trimIndent() + "\n"
            val resources = object : ResourceProvider {
                override val dataFolder: File = folder
                @Suppress("UNCHECKED_CAST")
                override fun <T> getConfigValue(path: String, default: T): T =
                    if (path == "language" || path == "fallback-language") "en" as T else default

                override fun saveResource(resourcePath: String, replace: Boolean) {
                    val target = File(folder, resourcePath)
                    if (target.exists() && !replace) return
                    target.parentFile?.mkdirs()
                    target.writeText(language, Charsets.UTF_8)
                }

                override fun getResourceStream(resourcePath: String): InputStream? =
                    if (resourcePath == "lang/messages_en.yml") ByteArrayInputStream(language.toByteArray(Charsets.UTF_8)) else null
            }
            block(MessageHandler(resources, object : PluginMetaProvider { override val name = "TestPlugin" }, MessageLogger.NO_OP))
        } finally {
            folder.deleteRecursively()
        }
    }
}
