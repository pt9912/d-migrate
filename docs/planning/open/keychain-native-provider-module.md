# Tracker: opt-in-Modul `keychain-native` (Windows + volle Parität)

> **Status:** Vorschlag (Draft) / Trigger Watch (2026-07-19)
> **Trigger:** Der `keychain:`-Provider-Kern ist geliefert (`20509388`,
> [ADR 0040](../../adr/0040-keychain-credential-provider-backend-port.md), Plan
> [`credential-provider-keychain.md`](../done/credential-provider-keychain.md)) — **native-frei** über
> `ShelloutKeychainBackend` (macOS `security`, Linux `secret-tool`). Der `KeychainBackend`-Port wurde
> per ADR 0040 bewusst so geschnitten, dass **Native = opt-in** bleibt. Windows hat in diesem
> Default-Backend **kein** Pendant → `keychain:` ist dort heute nicht verfügbar (fail-closed). Dieses
> Modul ist die zurückgestellte Windows-Unterstützung + optionale native Parität auf macOS/Linux.
> **Aktivierungsbedingung** (Move nach `../next/`): konkreter Bedarf für Windows-Keychain oder native
> Parität **plus** die Mechanismus-Entscheidung (eigene JNA vs. geprüfte Keyring-Lib) **plus** ein
> eingeplanter eigener Security-Review.

## Scope

- Neues opt-in-Modul `keychain-native`, das denselben `KeychainBackend`-Port implementiert und beim
  Wiring **bevorzugt** wird, falls vorhanden (Präsenz des Moduls = Aktivierung; Default bleibt
  `ShelloutKeychainBackend`).
- **Windows**: Credential Manager / DPAPI — native (JNA) oder Shell-out (`cmdkey` / PowerShell).
- **Optionale Parität macOS/Linux**: Security.framework bzw. libsecret-D-Bus statt des Shell-outs,
  falls ein native Weg gegenüber dem CLI-Aufruf gewünscht ist (kein Muss — der Shell-out deckt beide
  bereits ab).

## Warum zurückgestellt (nicht im Kern-Slice)

- Der Kern deckt **macOS + Linux** ab — die für Entwicklung, CI und die meisten Operator-Hosts
  relevanten Plattformen. Windows-native ist eine eigene **Packaging- und Supply-Chain-Fläche**.
- Jede Keyring-Lib bzw. JNA würde **JNA aktivieren** (heute inert) — das läuft gegen das
  GraalVM-Native-Image-Ziel und braucht eigene Native-Image-Konfiguration. Genau deshalb hält
  ADR 0040 den Default native-frei und macht Native zum opt-in.
- Kein Lastenheft-Eintrag erzwingt Windows-Keychain heute; headless/Windows sollen laut der
  Konsequenz aus [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md) ohnehin die
  richtige Schicht wählen (`env:` / `file:`).

## Vorbedingungen vor `../next/`

- **Mechanismus-Entscheidung**: eigene JNA-Bindung vs. geprüfte Keyring-Lib — inkl. Bewertung der
  Fremd-Lib im Secret-Pfad (Supply-Chain, Wartung).
- **GraalVM-Native-Image-Konfiguration**: Reflection/JNI-Registrierung für den nativen Zugriff, damit
  das Native-Image-Ziel nicht bricht.
- **Eigener Security-Review**: interaktives Unlock-Prompt-Verhalten, kein Secret in Logs/Args,
  Native-Packaging, Vertrauen in die gewählte Lib.
- **Test-Strategie Windows**: injizierbares Backend (kein echter Credential Manager im CI) plus, falls
  verfügbar, ein Windows-CI-Smoke.

## Herkunft

Abgespalten aus [`credential-provider-keychain.md`](../done/credential-provider-keychain.md) bei dessen
Graduierung nach `done/` am 2026-07-19 — dort war das Native-Modul nur als „Verbleibend"-Abschnitt
vermerkt, ohne eigenen Tracker.
