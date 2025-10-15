package com.github.messagehandler

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class SimpleMessageHandlerTest {
    private val miniMessage = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()
    private val legacyAmpersand = LegacyComponentSerializer.legacyAmpersand()
    private val legacySection = LegacyComponentSerializer.legacySection()

    private val defaultYaml = """
        prefix: "<gray>[Test]</gray>"
        general:
          welcome: "<green>Hello <name></green>"
        log:
          info: "&aNotice §b<name>"
        smart:
          single: "<yellow>Solo <name></yellow>"
          multi:
            - "&eLine <name>"
            - "§fLine <name>"
        reasons:
          ban:
            - "Cheating"
            - "Abuse"
    """.trimIndent()

    private lateinit var tempDir: Path
    private lateinit var provider: FakeResourceProvider
    private lateinit var logger: RecordingLogger
    private lateinit var handler: MessageHandler

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("message-handler-test")
        provider = FakeResourceProvider(tempDir.toFile(), defaultYaml)
        logger = RecordingLogger()
        handler = MessageHandler(provider, TestMetaProvider, logger)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `message is parsed with prefix and placeholders`() {
        val component = handler.getMessage("general", "welcome", mapOf("name" to "Alex"))
        assertEquals("&7[Test]&r &aHello Alex", legacyAmpersand.serialize(component))

        val simple = handler.getSimpleMessage("general", "welcome", mapOf("name" to "Alex"))
        assertEquals(component, miniMessage.deserialize(simple))

        val clean = handler.getCleanMessage("general", "welcome", mapOf("name" to "Alex"))
        assertEquals("Hello Alex", plain.serialize(miniMessage.deserialize(clean)))
    }

    @Test
    fun `smart message handles strings and lists`() {
        val single = handler.getSmartMessage("smart", "single", mapOf("name" to "Solo"))
        assertEquals(1, single.size)
        assertEquals("[Test] Solo Solo", plain.serialize(single.first()))

        val multi = handler.getSmartMessage("smart", "multi", mapOf("name" to "Alex"))
        val serialized = multi.map { legacyAmpersand.serialize(it) }
        assertEquals(listOf("&eLine Alex", "&fLine Alex"), serialized)
    }

    @Test
    fun `log messages support mixed legacy formatting`() {
        val logComponent = handler.getLogMessage("log", "info", mapOf("name" to "Player"))
        assertEquals("§aNotice §bPlayer", legacySection.serialize(logComponent))
    }

    @Test
    fun `reasons list is returned as raw strings`() {
        val reasons = handler.getReasons("reasons", "ban")
        assertEquals(listOf("Cheating", "Abuse"), reasons)
    }

    @Test
    fun `reload messages picks up file changes`() {
        val file = tempDir.resolve("lang/messages_en.yml").toFile()
        file.writeText(
            """
            prefix: "<gray>[Reloaded]</gray>"
            general:
              welcome: "<blue>Welcome <name></blue>"
            """.trimIndent()
        )
        handler.reloadMessages()
        val component = handler.getMessage("general", "welcome", mapOf("name" to "Alex"))
        assertEquals("&7[Reloaded]&r &9Welcome Alex", legacyAmpersand.serialize(component))
    }

    @Test
    fun `missing entry logs error and returns fallback`() {
        val result = handler.getCleanMessage("missing", "value")
        assertEquals("Message not found!", result)
        assertTrue(logger.errors.any { "missing" in it })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `legacy helpers behave as expected`() {
        assertEquals("§aHello", handler.formatLegacyTextBukkit("&aHello"))

        val component = handler.formatHexAndLegacyText("&#a1b2c3Hello")
        assertEquals("§bHello", legacySection.serialize(component))

        val mini = handler.miniMessageFormat("<red>Hi</red>")
        assertEquals("Hi", plain.serialize(mini))
    }

    private class FakeResourceProvider(
        override val dataFolder: File,
        private val defaults: String,
        private val configValues: Map<String, String> = mapOf("language" to "en")
    ) : ResourceProvider {
        override fun getConfigValue(path: String, default: String): String =
            configValues[path] ?: default

        override fun saveResource(path: String, replace: Boolean) {
            val target = dataFolder.toPath().resolve(path)
            Files.createDirectories(target.parent)
            if (replace || !Files.exists(target)) {
                Files.writeString(target, defaults, StandardCharsets.UTF_8)
            }
        }

        override fun getResourceStream(path: String): InputStream? =
            ByteArrayInputStream(defaults.toByteArray(StandardCharsets.UTF_8))
    }

    private class RecordingLogger : MessageLogger {
        val successes = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun success(message: String) {
            successes += message
        }

        override fun err(message: String) {
            errors += message
        }
    }

    private object TestMetaProvider : PluginMetaProvider {
        override val name: String = "TestPlugin"
    }
}
