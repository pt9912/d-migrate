package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DialectCapabilityProvider

/**
 * ServiceLoader-Einstieg fuer [SqliteCapabilities].
 *
 * Der Umweg ist keine Geschmacksfrage: `ServiceLoader` verlangt einen
 * **oeffentlichen parameterlosen Konstruktor**, und ein Kotlin-`object` hat
 * einen privaten. Ohne diese Klasse scheitert die Auflaesung zur Laufzeit mit
 * `ServiceConfigurationError: Unable to get public no-arg constructor` —
 * gemessen, nicht vermutet.
 *
 * Die Antworten stehen weiterhin in [SqliteCapabilities]; diese Klasse reicht sie
 * per Delegation durch und traegt selbst keine.
 */
class SqliteCapabilityProvider : DialectCapabilityProvider by SqliteCapabilities
