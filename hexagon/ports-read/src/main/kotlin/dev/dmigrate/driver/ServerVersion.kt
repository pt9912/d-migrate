package dev.dmigrate.driver

/**
 * Die Version des Servers, gegen den gelesen wurde.
 *
 * Der Lesepfad liefert sie als eine Angabe, nicht als ein Feld je Dialekt:
 * jede Auspraegung traegt die Fassung, in der ihr Hersteller die Version
 * ausweist, und wer sie auswertet, faengt sie mit `as?` auf die von ihm
 * erwartete Auspraegung ab. Ein Dialekt, dessen Renderer keine Version
 * braucht, laesst sie ungelesen; file-zu-Datei bleibt sie `null`.
 */
sealed interface ServerVersion
