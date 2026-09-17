# d-check-Pin: erst `v0.76.1` bricht bei nicht lesbaren Objekten ab

> **Status:** Befund / Vorabklärung (Gate-Infrastruktur), 2026-09-17.
> **Trigger:** Beim Schließen von
> [`doc-immutable-lokal-still-gruen.md`](../done/doc-immutable-lokal-still-gruen.md)
> gemessen: der gepinnte d-check `v0.74.1` meldet eine Kernänderung still mit
> 0 Befunden, wenn ein Objekt nur in einem `loose-*`-Pack liegt. d-check hat das
> selbst als Carve-out CO-001 geführt und mit `v0.76.1` geschlossen.
> **Aktivierungsbedingung:** Eigner-Entscheidung, den Pin zu heben. Danach ist es
> ein kleiner, eigener Commit (wie `a35261f04`).

## Befund

- Der Pin steht im [`Makefile`](../../../Makefile) (`DCHECK_DIGEST`, `v0.74.1`),
  und `make/d-check.mk` ist mit `--print-mk` aus derselben Version erzeugt.
- `make doc-immutable` ist davon nicht mehr abhängig: es prüft gegen einen
  frischen Klon ([`scripts/doc-immutable-in-clone.sh`](../../../scripts/doc-immutable-in-clone.sh)),
  in dem es keine `loose-*`-Packs gibt.
- Jeder andere git-lesende Aufruf gegen das Arbeits-Repo trägt die Lücke weiter,
  etwa `make doc-commits` (Modul `commits`). Kein Gate dieses Repos nutzt ihn
  heute; gemessen ist er nicht.
- Gemessen an einem Probe-Repo mit `loose-*`-Pack: `v0.74.1` endet mit
  0 Befunden und Exit 0, `v0.76.1`
  (`sha256:1470ecdcaa686a5ef4513dee9b0ae522586f54b87d568b06fc6b5b2741b633b3`)
  bricht mit Exit 2 ab. Lesen kann auch `v0.76.1` solche Packs nicht; das ist in
  d-check eine dokumentierte Grenze (slice-218).

## Was eine Hebung mitbringt

Laut Versionshistorie im Benutzerhandbuch von d-check ändert `v0.76.1` am Modul
`vcs` zwei Fehlermeldungen, ohne neuen Grund-Code und ohne Konfigurations-Bruch.
`v0.75.0` und `v0.76.0` bringen Opt-in-Erweiterungen (Modul `mentions`, eine
weitere `structure`-Bedingung). Zur Hebung gehören `make/d-check.mk`, neu erzeugt
mit `--print-mk`, der neue Digest sowie `make docs-check` und
`make doc-immutable RANGE=origin/main..HEAD` auf dem neuen Pin.
