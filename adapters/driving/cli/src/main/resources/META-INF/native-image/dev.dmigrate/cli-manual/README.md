# Handgepflegte Native-Image-Metadaten

Bewusst **getrennt** vom Nachbarverzeichnis `dev.dmigrate/cli/`: dort liegt die Ausgabe des
Tracing-Agenten (`make native-agent`), die bei jedem Lauf neu erzeugt und ueberschrieben wird.
Handarbeit dort waere beim naechsten Agent-Lauf verloren. `native-image` fuehrt alle Verzeichnisse
unter `META-INF/native-image/` zusammen, die Trennung kostet also nichts.

## `reflect-config.json` — `com.zaxxer.hikari.HikariConfig`

**Warum der Agent das nicht liefert.** `HikariConfig.logConfiguration()` ruft ueber
`PropertyElf.getProperty` reflektiv alle Getter auf, laeuft aber **nur bei aktivem
DEBUG-Logging**. Die `logback.xml` des CLI setzt `root level="WARN"` — auf der JVM wird der Pfad
also nie betreten, und was nicht laeuft, kann der Agent nicht aufzeichnen.

**Warum es trotzdem gebraucht wird.** Im Native-Image trat der Pfad frueher genau dann auf, wenn
`logback.xml` nicht als Ressource registriert war: logback fiel auf seinen eingebauten Default
(DEBUG) zurueck, `logConfiguration()` lief los und starb an
`MissingReflectionRegistrationError: … HikariConfig.getCredentials()`. Die Ressourcen-Registrierung
behebt diesen Ausloeser — aber nicht die Ursache: **schaltet ein Nutzer DEBUG-Logging ein, kehrt
der Fehler zurueck.** Das Binary duerfte dann ausgerechnet bei der Fehlerdiagnose brechen.

**Empirisch belegt (2026-07-20, GraalVM 25):** Der Bedarf ist nicht mehr nur eine Hypothese. Mit
erzwungenem DEBUG-Logging (`-Dlogback.configurationFile=` auf eine Konfiguration mit
`root level="DEBUG"`) laeuft `logConfiguration()` tatsaechlich los. **Ohne** dieses Verzeichnis
bricht das Binary dabei mit
`MissingReflectionRegistrationError: Cannot reflectively invoke method
HikariConfig.isAllowPoolSuspension()`; **mit** ihm laeuft der Aufruf durch (Exit 0, die Konfiguration
wird ausgegeben). Notwendig und hinreichend, beides gemessen.

Das GraalVM Reachability Metadata Repository ersetzt diese Registrierung **nicht** — auch nicht unter
GraalVM 25, wo es fehlerfrei laeuft. Derselbe Test ohne dieses Verzeichnis schlaegt fehl.

Deshalb hier explizit registriert, statt sich auf das Log-Level zu verlassen.
Gemessen in Phase F.0/F.2 des GraalVM-Slices
(`docs/planning/in-progress/graalvm-native-image-distribution.md`).
