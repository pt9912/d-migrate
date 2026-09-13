package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DialectCapabilityProvider

/**
 * ServiceLoader-Einstieg fuer [MysqlCapabilities].
 *
 * Der Umweg ist keine Geschmacksfrage: `ServiceLoader` verlangt einen
 * **oeffentlichen parameterlosen Konstruktor**, und ein Kotlin-`object` hat
 * einen privaten. Ohne diese Klasse scheitert die Auflaesung zur Laufzeit mit
 * `ServiceConfigurationError: Unable to get public no-arg constructor` —
 * gemessen, nicht vermutet.
 *
 * Die Antworten stehen weiterhin in [MysqlCapabilities]; diese Klasse reicht sie
 * per Delegation durch und traegt selbst keine.
 */
class MysqlCapabilityProvider : DialectCapabilityProvider by MysqlCapabilities
