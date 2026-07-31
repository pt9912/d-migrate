# Tracker: opt-in-Nischen-Modul `keychain-native` (JNA/DPAPI)

> **Status:** Vorschlag (Draft) / Trigger Watch (2026-07-19, **Nische** — nach native-freier
> Windows-Lieferung stark reduziert)
> **Trigger:** Der `keychain:`-Provider deckt **alle drei OS native-frei** ab (macOS `security`, Linux
> `secret-tool`, **Windows `powershell.exe` + Win32 `CredReadW`**; [ADR 0040](../../adr/0040-keychain-credential-provider-backend-port.md)-Nachtrag, Plan
> [`credential-provider-keychain.md`](../done/credential-provider-keychain.md)). Windows ist damit
> **kein** Grund mehr für ein natives Modul. Übrig bleibt nur eine echte, aber schmale Nische, in der
> der PowerShell-Shell-out nicht greift: **Constrained Language Mode** (Enterprise-Lockdown blockiert
> `Add-Type` → der Backend scheitert sauber `Unavailable`), oder wenn ein echt-natives Backend (ohne
> PowerShell-Prozess je Lookup) bevorzugt wird.
> **Aktivierungsbedingung** (Move nach `../next/`): ein konkreter Bedarf aus **genau dieser Nische**
> (belegter CLM-Lockdown-Fall o. echt-native Anforderung) **plus** die Mechanismus-Entscheidung (eigene
> JNA vs. geprüfte Keyring-Lib) **plus** ein eingeplanter eigener Security-Review. Ohne diesen Bedarf
> bleibt das Modul bewusst ungebaut.

## Scope (falls je gebaut)

- Neues opt-in-Modul `keychain-native`, das denselben `KeychainBackend`-Port implementiert und beim
  Wiring **bevorzugt** wird, falls vorhanden (Präsenz des Moduls = Aktivierung; Default bleibt der
  native-freie `ShelloutKeychainBackend`).
- **Windows**: Credential Manager / DPAPI direkt via JNA (`advapi32` `CredReadW`) statt über den
  PowerShell-Prozess — nützlich, wo `Add-Type`/PowerShell gesperrt ist.
- **Optionale Parität macOS/Linux**: Security.framework bzw. libsecret-D-Bus statt des Shell-outs —
  reiner Perf-/Präferenz-Gewinn, **kein** funktionaler Bedarf (Shell-out deckt beide voll ab).

## Warum (noch) nicht gebaut

- Der native-freie Shell-out deckt **macOS, Linux und den Windows-Normalfall** ab. Der verbleibende
  CLM-/echt-native Fall ist bislang **nicht belegt** nachgefragt.
- Jede Keyring-Lib bzw. JNA würde **JNA aktivieren** (heute inert) — das läuft gegen das
  GraalVM-Native-Image-Ziel und braucht eigene Native-Image-Konfiguration. Genau deshalb hält
  ADR 0040 den Default native-frei und macht Native zum opt-in.
- Kein Lastenheft-Eintrag erzwingt es; wo der PowerShell-Weg nicht greift, sollen Operatoren laut der
  Konsequenz aus [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md) ohnehin die
  richtige Schicht wählen (`env:` / `file:`).

## Vorbedingungen vor `../next/`

- **Belegter Nischen-Bedarf**: konkreter CLM-Lockdown-Fall oder echt-native Anforderung (nicht
  spekulativ) — sonst bleibt das Modul ungebaut.
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
Graduierung nach `done/` am 2026-07-19; nach der native-freien Windows-Lieferung ([ADR 0040](../../adr/0040-keychain-credential-provider-backend-port.md)-Nachtrag)
von „Windows-Support" auf „CLM-/echt-native Nische" reduziert.
