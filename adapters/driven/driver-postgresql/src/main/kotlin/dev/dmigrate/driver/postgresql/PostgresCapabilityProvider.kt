package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DialectCapabilityProvider

/**
 * ServiceLoader-Einstieg fuer [PostgresCapabilities].
 *
 * Der Umweg ist keine Geschmacksfrage: `ServiceLoader` verlangt einen
 * **oeffentlichen parameterlosen Konstruktor**, und ein Kotlin-`object` hat
 * einen privaten. Ohne diese Klasse scheitert die Auflaesung zur Laufzeit mit
 * `ServiceConfigurationError: Unable to get public no-arg constructor` —
 * gemessen, nicht vermutet.
 *
 * Die Antworten stehen weiterhin in [PostgresCapabilities]; diese Klasse reicht sie
 * per Delegation durch und traegt selbst keine.
 */
class PostgresCapabilityProvider : DialectCapabilityProvider by PostgresCapabilities
