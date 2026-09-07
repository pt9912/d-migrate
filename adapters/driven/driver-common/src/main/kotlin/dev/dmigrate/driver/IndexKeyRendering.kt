package dev.dmigrate.driver

import dev.dmigrate.core.model.IndexColumn

/**
 * Ein Indexschluessel als DDL — eine Spalte (gequotet) oder ein Ausdruck
 * (wortgleich, **in Klammern**).
 *
 * Die Klammern sind nicht Zierde, sondern gemessen noetig:
 *
 * - **PostgreSQL** verlangt sie fuer jeden Ausdruck, der kein blosser
 *   Funktionsaufruf ist: `(nm || 'x')` ist ein Syntaxfehler, `((nm || 'x'))`
 *   laeuft.
 * - **MySQL 8** verlangt sie immer; einfach geklammert liest es den Ausdruck
 *   als Spaltenliste und lehnt ab.
 * - **Oracle** nimmt beide Formen an und gibt die Klammern beim Zuruecklesen
 *   ohnehin nicht zurueck.
 *
 * Eine gemeinsame Quelle, weil jeder Dialekt **zwei** Index-Renderer hat —
 * einen fuer `schema generate`, einen fuer den Diff-Pfad. Getrennt gepflegt
 * hat der zweite den Ausdruck als Bezeichner gequotet, was einen Index auf
 * eine Spalte gelegt haette, die es nicht gibt.
 */
fun IndexColumn.renderKey(quoteIdentifier: (String) -> String): String =
    expression?.let { "($it)" } ?: quoteIdentifier(name)
