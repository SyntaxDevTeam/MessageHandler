package pl.syntaxdevteam.message

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageHandlerLanguageSafetyTest {
    private val defaultLanguage = """
        prefix: "[Default]"
        messages:
          hello: "Hello from bundled"
          literal: "<gray>Reason: <reason></gray>"
          display: "<operator_display>"
          list:
            - "One"
            - "Two"
    """.trimIndent() + "\n"

    @Test
    fun `invalid yaml is preserved and bundled fallback is used`() {
        val dataFolder = Files.createTempDirectory("mh-spigot-invalid").toFile()
        try {
            val languageFile = File(dataFolder, "lang/messages_en.yml")
            languageFile.parentFile.mkdirs()
            val invalid = "prefix: \"[Custom]\"\nmessages:\n  hello: \"unterminated\n"
            languageFile.writeText(invalid, StandardCharsets.UTF_8)
            val before = languageFile.readBytes()

            val handler = createHandler(dataFolder)

            assertContentEquals(before, languageFile.readBytes())
            assertEquals("Hello from bundled", handler.stringMessageToStringNoPrefix("messages", "hello"))
            assertTrue(File(languageFile.parentFile, "backups").listFiles().orEmpty().any { it.name.endsWith(".invalid.yml") })
        } finally {
            dataFolder.deleteRecursively()
        }
    }

    @Test
    fun `missing key falls back without rewriting valid user file`() {
        val dataFolder = Files.createTempDirectory("mh-spigot-fallback").toFile()
        try {
            val languageFile = File(dataFolder, "lang/messages_en.yml")
            languageFile.parentFile.mkdirs()
            languageFile.writeText("prefix: \"[Custom]\"\nmessages:\n  custom: \"Only custom\"\n", StandardCharsets.UTF_8)
            val before = languageFile.readBytes()

            val handler = createHandler(dataFolder)

            assertEquals("Hello from bundled", handler.stringMessageToStringNoPrefix("messages", "hello"))
            assertContentEquals(before, languageFile.readBytes())
        } finally {
            dataFolder.deleteRecursively()
        }
    }

    @Test
    fun `failed reload keeps last valid configuration active`() {
        val dataFolder = Files.createTempDirectory("mh-spigot-reload").toFile()
        try {
            val languageFile = File(dataFolder, "lang/messages_en.yml")
            languageFile.parentFile.mkdirs()
            languageFile.writeText("prefix: \"[Custom]\"\nmessages:\n  hello: \"First value\"\n", StandardCharsets.UTF_8)
            val handler = createHandler(dataFolder)
            assertEquals("First value", handler.stringMessageToStringNoPrefix("messages", "hello"))

            val invalid = "prefix: \"[Broken]\"\nmessages:\n  hello: \"unterminated\n"
            languageFile.writeText(invalid, StandardCharsets.UTF_8)
            val brokenBytes = languageFile.readBytes()

            handler.reloadMessages()

            assertEquals("First value", handler.stringMessageToStringNoPrefix("messages", "hello"))
            assertContentEquals(brokenBytes, languageFile.readBytes())
        } finally {
            dataFolder.deleteRecursively()
        }
    }

    @Test
    fun `component serialization preserves text in all output formats`() {
        val folder = Files.createTempDirectory("mh-codecs").toFile()
        try {
            val handler = createHandler(folder)
            val component = handler.formatRichTextToComponent("&cRed <gold>gold</gold> &#123456hex")
            for (format in MessageHandler.MessageFormat.entries) {
                val rendered = handler.componentToString(component, format)
                assertEquals("Red gold hex", handler.getPlainText(handler.formatTextToComponent(rendered, format)))
            }
            assertEquals(MessageHandler.MessageFormat.MINI_MESSAGE, handler.getMessageFormat("messages", "display"))
            assertEquals(MessageHandler.MessageFormat.PLAIN, handler.getMessageFormat("messages", "hello"))
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun `literal placeholders never create formatting or commands`() {
        val folder = Files.createTempDirectory("mh-literals").toFile()
        try {
            val handler = createHandler(folder)
            val value = "<red>&c <click:run_command:'/op User'>click</click>"
            val component = handler.stringMessageToComponentNoPrefixLiteral("messages", "literal", mapOf("reason" to value))
            assertEquals("Reason: $value", handler.getPlainText(component))
            fun hasClick(node: net.kyori.adventure.text.Component): Boolean = node.clickEvent() != null || node.children().any(::hasClick)
            assertTrue(!hasClick(component))
            val rich = handler.formatRichTextToComponent("<gold>[Admin]</gold> &a<name>",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", "User <red>"))
            assertEquals("[Admin] User <red>", handler.getPlainText(rich))
        } finally { folder.deleteRecursively() }
    }

    private fun createHandler(dataFolder: File): MessageHandler = MessageHandler(
        resources = TestResourceProvider(dataFolder, defaultLanguage),
        meta = object : PluginMetaProvider {
            override val name: String = "TestPlugin"
        },
        logger = MessageLogger.NO_OP
    )

    private class TestResourceProvider(
        override val dataFolder: File,
        private val defaultLanguage: String
    ) : ResourceProvider {
        @Suppress("UNCHECKED_CAST")
        override fun <T> getConfigValue(path: String, default: T): T = when (path) {
            "language" -> "en" as T
            "fallback-language" -> "en" as T
            else -> default
        }

        override fun saveResource(resourcePath: String, replace: Boolean) {
            val target = File(dataFolder, resourcePath)
            if (target.exists() && !replace) return
            target.parentFile?.mkdirs()
            target.writeText(defaultLanguage, StandardCharsets.UTF_8)
        }

        override fun getResourceStream(resourcePath: String): InputStream? =
            if (resourcePath == "lang/messages_en.yml") {
                ByteArrayInputStream(defaultLanguage.toByteArray(StandardCharsets.UTF_8))
            } else {
                null
            }
    }
}
