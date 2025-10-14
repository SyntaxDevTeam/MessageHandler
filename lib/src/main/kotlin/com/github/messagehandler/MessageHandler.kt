package com.github.messagehandler

import com.github.benmanes.caffeine.cache.Caffeine
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import kotlin.jvm.functions.Function0
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * High level facade used by plugins to obtain localized messages.
 */
public class MessageHandler(
    private val config: MessageHandlerConfig,
    executor: ExecutorService? = null
) : Closeable {

    private val loader = YamlMessageLoader(config)
    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(config.cacheDuration)
        .maximumSize(config.maximumCachedLocales)
        .build<Locale, MessageBundle>()
    private val loading = ConcurrentHashMap<Locale, CompletableFuture<MessageBundle>>()

    private val executor: ExecutorService
    private val ownsExecutor: Boolean

    private val miniMessage = config.miniMessage
    private val plainSerializer = PlainTextComponentSerializer.plainText()
    private val legacyAmpersand = LegacyComponentSerializer.legacyAmpersand()
    private val legacySection = LegacyComponentSerializer.legacySection()

    init {
        val createdExecutor = executor ?: Executors.newCachedThreadPool(newThreadFactory())
        this.executor = createdExecutor
        this.ownsExecutor = executor == null
        preload(config.defaultLocale)
        config.fallbackLocale?.let { fallback ->
            if (fallback != config.defaultLocale) {
                preload(fallback)
            }
        }
    }

    /**
     * Schedules the language file for the supplied [locale] to be loaded in the background.
     */
    public fun preload(locale: Locale = config.defaultLocale): CompletableFuture<Unit> =
        scheduleLoad(locale).thenApply { Unit }

    /**
     * Forces a reload of the language file for the supplied [locale].
     */
    public fun reload(locale: Locale = config.defaultLocale): CompletableFuture<Unit> {
        cache.invalidate(locale)
        val future = submitLoad(locale)
        loading[locale] = future
        return future.thenApply { Unit }
    }

    /**
     * Returns the prefix component defined by [MessageHandlerConfig.prefixPath].
     */
    public fun prefix(
        locale: Locale = config.defaultLocale,
        placeholders: Map<String, Any?> = emptyMap(),
        resolver: TagResolver = TagResolver.empty()
    ): Component = component(locale, config.prefixPath, placeholders, resolver)

    /**
     * Retrieves the message identified by [category] and [key] and returns it as an Adventure [Component].
     */
    public fun getMessage(
        category: String,
        key: String,
        placeholders: Map<String, Any?> = emptyMap(),
        locale: Locale = config.defaultLocale,
        resolver: TagResolver = TagResolver.empty()
    ): Component = component(locale, listOf(category, key), placeholders, resolver)

    /**
     * Returns the message as MiniMessage formatted text after placeholder resolution.
     */
    public fun getMiniMessage(
        category: String,
        key: String,
        placeholders: Map<String, Any?> = emptyMap(),
        locale: Locale = config.defaultLocale,
        resolver: TagResolver = TagResolver.empty()
    ): String = miniMessage.serialize(getMessage(category, key, placeholders, locale, resolver))

    /**
     * Returns the message as plain text after placeholder resolution.
     */
    public fun getPlain(
        category: String,
        key: String,
        placeholders: Map<String, Any?> = emptyMap(),
        locale: Locale = config.defaultLocale,
        resolver: TagResolver = TagResolver.empty()
    ): String = plainSerializer.serialize(getMessage(category, key, placeholders, locale, resolver))

    /**
     * Returns the message encoded using the legacy ampersand format.
     */
    public fun getLegacyAmpersand(
        category: String,
        key: String,
        placeholders: Map<String, Any?> = emptyMap(),
        locale: Locale = config.defaultLocale,
        resolver: TagResolver = TagResolver.empty()
    ): String = legacyAmpersand.serialize(getMessage(category, key, placeholders, locale, resolver))

    /**
     * Returns the message encoded using the legacy section format.
     */
    public fun getLegacySection(
        category: String,
        key: String,
        placeholders: Map<String, Any?> = emptyMap(),
        locale: Locale = config.defaultLocale,
        resolver: TagResolver = TagResolver.empty()
    ): String = legacySection.serialize(getMessage(category, key, placeholders, locale, resolver))

    /**
     * Returns the raw, unresolved text stored in the YAML file or `null` when the path is missing.
     */
    public fun getRaw(
        category: String,
        key: String,
        locale: Locale = config.defaultLocale
    ): String? = raw(locale, listOf(category, key))

    /**
     * Converts an ad-hoc [raw] message into an Adventure [Component].
     */
    public fun componentFromRaw(
        raw: String,
        format: MessageFormat = config.defaultFormat,
        placeholders: Map<String, Any?> = emptyMap(),
        resolver: TagResolver = TagResolver.empty()
    ): Component {
        val entry = MessageEntry(raw, format)
        val placeholderBundle = buildPlaceholders(placeholders, resolver)
        return renderEntry(entry, placeholderBundle)
    }

    /**
     * Returns a component for the message located at the provided [path].
     */
    public fun component(
        locale: Locale = config.defaultLocale,
        path: List<String>,
        placeholders: Map<String, Any?> = emptyMap(),
        resolver: TagResolver = TagResolver.empty()
    ): Component {
        if (path.isEmpty()) {
            return Component.empty()
        }
        val (bundle, entry) = findEntry(locale, path)
            ?: return config.missingMessageProvider(locale, path)
        val placeholderBundle = buildPlaceholders(placeholders, resolver)
        return renderEntry(entry, placeholderBundle)
    }

    /**
     * Convenience overload that accepts a variable length message path.
     */
    public fun component(
        vararg path: String,
        locale: Locale = config.defaultLocale,
        placeholders: Map<String, Any?> = emptyMap(),
        resolver: TagResolver = TagResolver.empty()
    ): Component = component(locale, path.toList(), placeholders, resolver)

    override fun close() {
        if (ownsExecutor) {
            executor.shutdown()
        }
    }

    private fun renderEntry(entry: MessageEntry, placeholders: PlaceholderBundle): Component {
        return when (entry.format) {
            MessageFormat.MINI_MESSAGE -> miniMessage.deserialize(entry.raw, placeholders.resolver)
            MessageFormat.LEGACY_SECTION -> {
                val processed = applyLegacyPlaceholders(entry.raw, placeholders.componentValues, legacySection)
                legacySection.deserialize(processed)
            }
            MessageFormat.LEGACY_AMPERSAND -> {
                val processed = applyLegacyPlaceholders(entry.raw, placeholders.componentValues, legacyAmpersand)
                legacyAmpersand.deserialize(processed)
            }
            MessageFormat.PLAIN -> {
                val processed = applyPlainPlaceholders(entry.raw, placeholders.componentValues)
                Component.text(processed)
            }
        }
    }

    private fun applyPlainPlaceholders(raw: String, values: Map<String, Component>): String {
        if (values.isEmpty()) return raw
        var result = raw
        values.forEach { (name, component) ->
            val replacement = plainSerializer.serialize(component)
            result = result.replace("<$name>", replacement)
        }
        return result
    }

    private fun applyLegacyPlaceholders(
        raw: String,
        values: Map<String, Component>,
        serializer: LegacyComponentSerializer
    ): String {
        if (values.isEmpty()) return raw
        var result = raw
        values.forEach { (name, component) ->
            val replacement = serializer.serialize(component)
            result = result.replace("<$name>", replacement)
        }
        return result
    }

    private fun buildPlaceholders(
        placeholders: Map<String, Any?>,
        additionalResolver: TagResolver
    ): PlaceholderBundle {
        if (placeholders.isEmpty() && additionalResolver == TagResolver.empty()) {
            return PlaceholderBundle(TagResolver.empty(), emptyMap())
        }
        val components = mutableMapOf<String, Component>()
        val builder = TagResolver.builder()
        if (additionalResolver != TagResolver.empty()) {
            builder.resolver(additionalResolver)
        }
        placeholders.forEach { (name, value) ->
            when (value) {
                null -> {}
                is ComponentLike -> {
                    val component = value.asComponent()
                    components[name] = component
                    builder.resolver(Placeholder.component(name, component))
                }
                is TagResolver -> builder.resolver(value)
                is java.util.function.Supplier<*> -> {
                    val supplied = value.get()
                    if (supplied is ComponentLike) {
                        val component = supplied.asComponent()
                        components[name] = component
                        builder.resolver(Placeholder.component(name, component))
                    } else if (supplied != null) {
                        val component = Component.text(supplied.toString())
                        components[name] = component
                        builder.resolver(Placeholder.component(name, component))
                    }
                }
                is Function0<*> -> {
                    val supplied = value.invoke()
                    if (supplied is ComponentLike) {
                        val component = supplied.asComponent()
                        components[name] = component
                        builder.resolver(Placeholder.component(name, component))
                    } else if (supplied != null) {
                        val component = Component.text(supplied.toString())
                        components[name] = component
                        builder.resolver(Placeholder.component(name, component))
                    }
                }
                is String -> {
                    val component = miniMessage.deserialize(value)
                    components[name] = component
                    builder.resolver(Placeholder.component(name, component))
                }
                is Number, is Boolean -> {
                    val component = Component.text(value.toString())
                    components[name] = component
                    builder.resolver(Placeholder.component(name, component))
                }
                else -> {
                    val component = Component.text(value.toString())
                    components[name] = component
                    builder.resolver(Placeholder.component(name, component))
                }
            }
        }
        return PlaceholderBundle(builder.build(), components)
    }

    private fun findEntry(locale: Locale, path: List<String>): Pair<MessageBundle, MessageEntry>? {
        val primaryBundle = awaitBundle(locale)
        if (primaryBundle != null) {
            val entry = primaryBundle.entry(path)
            if (entry != null) {
                return primaryBundle to entry
            }
        }
        val fallbackLocale = config.fallbackLocale
        if (fallbackLocale != null && fallbackLocale != locale) {
            val fallbackBundle = awaitBundle(fallbackLocale)
            if (fallbackBundle != null) {
                val fallbackEntry = fallbackBundle.entry(path)
                if (fallbackEntry != null) {
                    return fallbackBundle to fallbackEntry
                }
            }
        }
        return null
    }

    private fun raw(locale: Locale, path: List<String>): String? {
        val entry = findEntry(locale, path)?.second ?: return null
        return entry.raw
    }

    private fun awaitBundle(locale: Locale): MessageBundle? {
        cache.getIfPresent(locale)?.let { return it }
        val future = scheduleLoad(locale)
        return try {
            val bundle = future.getNow(null) ?: future.join()
            if (bundle != null) {
                cache.put(locale, bundle)
            }
            bundle
        } catch (ex: CompletionException) {
            null
        } catch (ex: CancellationException) {
            null
        }
    }

    private fun scheduleLoad(locale: Locale): CompletableFuture<MessageBundle> {
        cache.getIfPresent(locale)?.let { return CompletableFuture.completedFuture(it) }
        return loading.computeIfAbsent(locale) { submitLoad(locale) }
    }

    private fun submitLoad(locale: Locale): CompletableFuture<MessageBundle> {
        val future = CompletableFuture.supplyAsync({ loader.load(locale) }, executor)
        future.whenComplete { bundle, throwable ->
            try {
                if (throwable == null && bundle != null) {
                    cache.put(locale, bundle)
                }
            } finally {
                loading.compute(locale) { _, existing ->
                    if (existing === future) null else existing
                }
            }
        }
        return future
    }

    private companion object {
        private val COUNTER = AtomicInteger()

        private fun newThreadFactory(): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "message-handler-${COUNTER.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }

    private data class PlaceholderBundle(
        val resolver: TagResolver,
        val componentValues: Map<String, Component>
    )
}
