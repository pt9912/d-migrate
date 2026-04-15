package dev.dmigrate.cli.output

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * Resolves localized CLI messages from ResourceBundles.
 *
 * Not a singleton — instantiated with a specific [Locale] and injected
 * into formatters and renderers. Falls back to the root (English) bundle
 * for missing keys or unsupported locales.
 */
class MessageResolver(private val locale: Locale = Locale.ENGLISH) {

    private val bundle: ResourceBundle =
        ResourceBundle.getBundle("messages.messages", locale)

    /**
     * Looks up a message by key and formats it with the given arguments.
     * Returns the key itself if the message is not found (safe degradation).
     */
    fun text(key: String, vararg args: Any): String {
        val pattern = try {
            bundle.getString(key)
        } catch (_: java.util.MissingResourceException) {
            return key
        }
        return if (args.isEmpty()) pattern
        else MessageFormat(pattern, locale).format(args)
    }
}
