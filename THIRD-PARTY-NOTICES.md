# Drittanbieter-Lizenzhinweise

Dieses Repository ist MIT-lizenziert ([`LICENSE`](LICENSE)). Die meisten
Laufzeitabhängigkeiten stehen unter kompatiblen Lizenzen (Apache-2.0, MIT,
BSD u. ä.) und benötigen keinen gesonderten Hinweis. Diese Datei führt nur
Abhängigkeiten, deren Lizenz eine explizite Hinweispflicht bei
Weiterverbreitung auferlegt.

## Oracle JDBC-Treiber (`com.oracle.database.jdbc:ojdbc11`)

Ab Oracle als fünftem Dialekt ([ADR 0052](docs/adr/0052-oracle-fuenfter-dialekt-scoping.md))
bindet `adapters/driven/driver-oracle` den Oracle-JDBC-Treiber `ojdbc11`.
Dieser Treiber steht unter den **Oracle Free Use Terms and Conditions
(FUTC)**, nicht MIT:
<https://www.oracle.com/downloads/licenses/oracle-free-license.html>

FUTC erlaubt die Weiterverbreitung des **unmodifizierten** Treibers, verlangt
dabei aber:

- diese Hinweisdatei bzw. eine gleichwertige Lizenzkopie bei jeder
  Weiterverbreitung mitzuführen,
- Oracle-Eigentumsvermerke im Treiber-Artefakt nicht zu entfernen,
- kein Reverse Engineering des Treibers.

d-migrate bindet `ojdbc11` ausschließlich unmodifiziert über Maven Central
(`com.oracle.database.jdbc:ojdbc11`, siehe `gradle.properties`) ein; es
werden keine Oracle-Programmdateien verändert oder eigenständig verteilt.

**Offen (Tracking):** die konkrete Bündelung dieser Datei in den
Release-Artefakten (Docker-Image, Fat-JAR/ZIP) ist noch nicht mechanisiert —
siehe [`oracle-dialect-scoping.md`](docs/planning/in-progress/oracle-dialect-scoping.md),
Abschnitt „Offene Punkte".
