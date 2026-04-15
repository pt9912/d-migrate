package dev.dmigrate.cli.i18n

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

data class ResolvedI18nSettings(
    val locale: Locale,
    val timezone: ZoneId,
    val normalization: UnicodeNormalizationMode,
) {
    companion object {
        val DEFAULT = ResolvedI18nSettings(
            locale = Locale.ENGLISH,
            timezone = ZoneOffset.UTC,
            normalization = UnicodeNormalizationMode.NFC,
        )
    }
}
