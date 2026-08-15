# Tracker: Multi-Arch für das native OCI-Image (`:X.Y.Z-native`)

> **Status:** Vorschlag (Draft) / Trigger Watch (2026-07-31)
> **Trigger:** Wie beim JVM-Image ist auch `…:X.Y.Z-native` **Single-Platform `linux/amd64`**
> (verifiziert am `1.0.0-RC2`-Tag). Auf ARM64 läuft es emuliert — was den Sinn eines nativen Images
> (Start in Millisekunden) gerade aufhebt.
> **Aktivierungsbedingung** (Move nach `../next/`): ein **`linux-arm64`-Native-Binary** muss zuerst
> existieren; dessen Vertagung ist eine bewusste Entscheidung (siehe unten). Danach
> die Manifest-Frage aus [`oci-image-multiarch-jvm.md`](oci-image-multiarch-jvm.md), die für beide
> Images dieselbe ist.

## Warum das der teure Schritt ist

Beim JVM-Image ist Bytecode architekturneutral — nur das Base-Image wechselt. Ein **natives Binary
ist architekturgebunden**: je Zielarchitektur muss GraalVM einen **eigenen** `nativeCompile` fahren.
Cross-Compilation ist bei GraalVM Native Image nicht praktikabel; es braucht einen echten
ARM64-Runner (GitHub-hosted `ubuntu-24.04-arm`) oder einen selbstgehosteten.

Kostenrahmen: der Linux-Native-Build lag zum RC2-Tag bei ~10 Minuten. Ein zweites Leg verdoppelt das
und läuft zusätzlich zum bereits vorhandenen 3-OS-Binary-Matrixbau in `native-image.yml`.

## Bereits entschieden — und diese Entscheidung ist der Vorläufer

Slice-Frage 4 des GraalVM-Slices
([`graalvm-native-image-distribution.md`](../done/graalvm-native-image-distribution.md), Zeile 542):

> ✅ **Entschieden 2026-07-21 (Eigner): linux-arm64 NICHT für 1.0.0** (auf später vertagt). Der
> Plattform-Scope (seit [ADR 0044](../../adr/0044-kein-macos-native-binary.md) nur noch `linux-x64`
> und `windows-x64`) bleibt schlank; ARM64-Linux-Nutzer
> haben JVM/OCI bzw. x64-Emulation als Brücke. Nicht slice-blockierend. Nachrüstbar: eine
> `ubuntu-24.04-arm`-Matrix-Zeile fürs Binary (klein) + multi-arch für das native OCI-Image
> (aufwändiger) — als eigener Post-1.0.0-Schritt.

Dieses Ticket ist genau der dort benannte „eigene Post-1.0.0-Schritt". Es ist also **keine**
Abweichung von der Entscheidung, sondern ihre Fortschreibung.

## Skizze (falls gebaut)

1. `native-image.yml`: Matrix um `ubuntu-24.04-arm` erweitern → Asset
   `d-migrate-<version>-linux-arm64` (+ `.sha256`). Dieses Binary ist **für sich schon nützlich**,
   unabhängig vom Image, und ist der kleine Teil.
2. `build.yml` `native-image`-Job: je Architektur ein Image bauen und pushen, dann per
   `docker buildx imagetools create` zu einem Index zusammenfassen.
3. Dieselbe Digest-/Spiegel-Frage klären wie im JVM-Ticket — beide Images sollten denselben Weg
   gehen, nicht zwei verschiedene.

## Akzeptanz (falls gebaut)

- `docker pull …:X.Y.Z-native` auf ARM64 liefert ein nativ laufendes Binary; Startzeit in derselben
  Größenordnung wie auf x64 (~15 ms), nicht Emulations-langsam.
- Das `linux-arm64`-Binary hängt am GitHub-Release, mit `.sha256`.
- Das `linux-x64`-Release-Gate aus Slice-Frage 6 bleibt unberührt; ARM64 ist **best-effort** wie
  macOS/Windows, sonst wächst die Gate-Fläche unbeabsichtigt.
