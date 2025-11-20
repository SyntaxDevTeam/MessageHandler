package pl.syntaxdevteam.message

import java.io.File
import java.io.InputStream

/**
 * Abstraction responsible for accessing configuration values and bundled resources
 * required by [MessageHandler].
 */
interface ResourceProvider {
    val dataFolder: File
    fun <T> getConfigValue(path: String, default: T): T
    fun saveResource(resourcePath: String, replace: Boolean)
    fun getResourceStream(resourcePath: String): InputStream?
}
