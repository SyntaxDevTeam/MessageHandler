package com.github.messagehandler

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

@OptIn(ExperimentalPathApi::class)
class MessageHandlerTest {
    private lateinit var tempDir: Path
    private lateinit var handler: MessageHandler
    private lateinit var config: MessageHandlerConfig

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("message-handler-test")
        copyResource("/lang/messages_pl.yml", tempDir.resolve("messages_pl.yml"))
        copyResource("/lang/messages_en.yml", tempDir.resolve("messages_en.yml"))
        config = MessageHandlerConfig(
            baseDirectory = tempDir,
            defaultLocale = Locale("pl"),
            fallbackLocale = Locale.ENGLISH,
            cacheDuration = Duration.ofMinutes(10)
        )
        handler = MessageHandler(config)
    }

    @AfterTest
    fun tearDown() {
        handler.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `mini message format is deserialized with placeholders`() {
        val component = handler.getMessage(
            "general",
            "welcome",
            mapOf("player" to Component.text("Steve"))
        )
        val mini = handler.getMiniMessage(
            "general",
            "welcome",
            mapOf("player" to Component.text("Steve"))
        )
        assertEquals(config.miniMessage.serialize(component), mini)
        val plain = handler.getPlain(
            "general",
            "welcome",
            mapOf("player" to Component.text("Steve"))
        )
        assertEquals("Witaj Steve!", plain)
        assertEquals(
            config.miniMessage.deserialize("<green>Witaj Steve!</green>"),
            component
        )
    }

    @Test
    fun `legacy section formatting is supported`() {
        val legacy = handler.getLegacySection(
            "general",
            "notify",
            mapOf("player" to Component.text("Alex"))
        )
        assertEquals("§eWitaj §lAlex", legacy)
    }

    @Test
    fun `legacy ampersand formatting is supported`() {
        val legacy = handler.getLegacyAmpersand(
            "general",
            "info",
            mapOf("player" to Component.text("Alex"))
        )
        assertEquals("&bInformacja dla Alex", legacy)
    }

    @Test
    fun `plain text entries are returned`() {
        val plain = handler.getPlain("general", "plain")
        assertEquals("Wiadomość tekstowa", plain)
    }

    @Test
    fun `prefix can be retrieved`() {
        val prefixPl = handler.prefix(locale = Locale("pl"))
        val prefixEn = handler.prefix(locale = Locale.ENGLISH)
        assertEquals("<gray>[Serwer]</gray> ", config.miniMessage.serialize(prefixPl))
        assertEquals("<gray>[Server]</gray> ", config.miniMessage.serialize(prefixEn))
    }

    @Test
    fun `fallback locale is used when entry missing`() {
        val plain = handler.getPlain(
            "general",
            "fallback",
            mapOf("player" to "Alex"),
            locale = Locale("pl")
        )
        assertEquals("Fallback Alex", plain)
    }

    @Test
    fun `component from raw legacy string`() {
        val component = handler.componentFromRaw(
            "§aHello <name>",
            MessageFormat.LEGACY_SECTION,
            mapOf("name" to Component.text("Alex"))
        )
        val serialized = LegacyComponentSerializer.legacySection().serialize(component)
        assertEquals("§aHello Alex", serialized)
    }

    @Test
    fun `reloading locale refreshes cache`() {
        val file = config.resolveFile(Locale("pl"))
        Files.writeString(
            file,
            """
            prefix:
              value: "<gray>[Serwer]</gray> "
            general:
              welcome:
                format: MINI_MESSAGE
                value: "<blue>Siema <player>!</blue>"
            """.trimIndent()
        )
        handler.reload(Locale("pl")).join()
        val plain = handler.getPlain(
            "general",
            "welcome",
            mapOf("player" to "Kowalski")
        )
        assertEquals("Siema Kowalski!", plain)
    }

    private fun copyResource(resource: String, target: Path) {
        javaClass.getResourceAsStream(resource)?.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        } ?: error("Missing resource $resource")
    }
}
