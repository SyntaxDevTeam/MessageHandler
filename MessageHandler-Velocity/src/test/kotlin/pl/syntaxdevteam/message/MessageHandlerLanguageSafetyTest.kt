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
    """.trimIndent() + "\n"

    @Test
    fun `invalid yaml is preserved and bundled fallback is used`() {
        val dataFolder = Files.createTempDirectory("mh-velocity-invalid").toFile()
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
        val dataFolder = Files.createTempDirectory("mh-velocity-fallback").toFile()
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
        val dataFolder = Files.createTempDirectory("mh-velocity-reload").toFile()
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
