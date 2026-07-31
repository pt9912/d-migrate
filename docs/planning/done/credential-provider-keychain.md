# `keychain:`-Credential-Provider (Folge-Slice der O4-Naht)

**Status**: **ABGESCHLOSSEN 2026-07-19** — Kern-Slice (macOS/Linux) geliefert (`20509388`,
[ADR 0040](../../adr/0040-keychain-credential-provider-backend-port.md)); **Windows native-frei
nachgeliefert** (dritter Shell-out-Zweig via PowerShell/`CredReadW`, Nachtrag in
[ADR 0040](../../adr/0040-keychain-credential-provider-backend-port.md)). Plan nach
`done/` graduiert. Nur noch eine **opt-in-Nische** (JNA/DPAPI für Constrained-Language-Mode-Windows
o. echt-natives Backend) ist als eigener Tracker
[`../open/keychain-native-provider-module.md`](../open/keychain-native-provider-module.md) ausgegliedert
(nicht mehr offene Scope dieses Plans).
Baute auf der **fertigen** `CredentialProviderRegistry` auf
([ADR 0035](../../adr/0035-credential-provider-scheme-registry.md), Slice 2 der
[`LN-025`](../../../spec/lastenheft-d-migrate.md#ln-025)-O4-Staffel); kein Architekturwechsel.

> **Geliefert ([ADR 0040](../../adr/0040-keychain-credential-provider-backend-port.md) + Nachtrag):**
> `KeychainBackend`-Port + `ShelloutKeychainBackend` (**native-frei für alle drei OS**: macOS `security`,
> Linux `secret-tool`, **Windows `powershell.exe` + Win32 `CredReadW`** — Ziel-Name über Env-Var
> `DM_KEYCHAIN_TARGET`, konstantes `-EncodedCommand`, Timeout, Secret über stdout) +
> `KeychainCredentialProvider` (`keychain://<service>[/<account>]`, fail-closed:
> `KEYCHAIN_UNAVAILABLE`/`KEYCHAIN_ENTRY_NOT_FOUND`/`EMPTY_VALUE`) + Registrierung in
> `defaultCredentialProviderRegistry` + Tests (injizierte Kommando-Ausführung, kein echtes OS-Keychain
> im CI). Die Mechanismus-Frage ist per ADR 0040 hinter den `KeychainBackend`-Port gezogen: Default
> native-frei, **Native nur als opt-in-Nische**.
>
> **Verbleibend = opt-in-Nischen-Modul `keychain-native`** (JNA/DPAPI): nur noch für Umgebungen, in
> denen der PowerShell-Weg nicht greift (v. a. Constrained Language Mode → `Add-Type` blockiert) oder
> wenn ein echt-natives Backend bevorzugt wird — **kein** Windows-Blocker mehr. Als eigener Tracker
> ausgegliedert → [`../open/keychain-native-provider-module.md`](../open/keychain-native-provider-module.md).

**Trigger**: [ADR 0035](../../adr/0035-credential-provider-scheme-registry.md) D4 hat `keychain:`
bewusst aus dem RC-Slice ausgeschnitten (plattformspezifische Native-/CLI-Integration + zwingender
Headless-Fallback, s. auch [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md) D4).
Der `file:`-Provider bewies die Naht; `keychain:` ist der nächste Provider auf derselben Registry.

## Scope

- Neuer `KeychainCredentialProvider` (Scheme `keychain:`) in `adapters/driven/connection-config`,
  registriert in `defaultCredentialProviderRegistry()`. Vertrag wie `file:`/`env:`: der
  Keychain-Eintrag liefert eine **vollständige** Connect-URL (World-B-Parität).
- `credentialRef: keychain://<service>[/<account>]` — Dispatch am Scheme wie gehabt (ADR 0035 D2).

## Plattform-Mechanismen (geliefert)

| OS | Mechanismus | Geliefert (native-frei) |
|---|---|---|
| macOS | Keychain | `security find-generic-password -s <service> [-a <account>] -w` |
| Linux | Secret Service (libsecret) | `secret-tool lookup service <service> [account <account>]` |
| Windows | Credential Manager | `powershell.exe -EncodedCommand <konstant>` → Win32 `CredReadW`, Ziel-Name über Env-Var |

**Kern-Entscheidung (getroffen, [ADR 0040](../../adr/0040-keychain-credential-provider-backend-port.md))**:
nicht Entweder-oder, sondern `KeychainBackend`-Port — **Default = Shell-out** (native-frei, hält das
GraalVM-Native-Image-Ziel + Supply-Chain sauber), **Native = opt-in-Nische**. Alle drei OS sind über den
native-freien Shell-out geliefert; Windows braucht PowerShell, weil kein CLI ein Secret ausgibt
(`cmdkey /list` zeigt Passwörter bewusst nicht). Die JNA-vs-Keyring-Lib-Frage bleibt ein isoliertes
Detail **innerhalb** des opt-in-`keychain-native`-Moduls (Tracker).

## Headless-Fallback (zwingend)

CI/Docker/Server haben **keinen** Keychain. `keychain:` muss dort **fail-closed** sauber scheitern
(secret-freie Meldung „kein Keychain verfügbar" → Registry-`Failure`), damit ein Lauf nicht
undiagnostizierbar hängt. Kein stiller Degrade. Doku muss die Schicht-Wahl aussprechen (headless →
`env:`/`file:`, nicht `keychain:`), analog [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md)-Konsequenz „richtige Schicht wählen".

## Vorbedingungen (für den Kern-Slice — alle erfüllt)

- ✅ ADR-Inkrement: Mechanismus hinter `KeychainBackend`-Port, Fallback-Semantik, OS-Matrix
  ([ADR 0040](../../adr/0040-keychain-credential-provider-backend-port.md)).
- ✅ Security-Review: Secret über stdout (nicht in `ps`-Argumenten), kein Secret in Logs,
  ProcessBuilder-Timeout gegen interaktiven Unlock-Hänger im nicht-interaktiven Lauf.
- ✅ Test-Strategie: Provider + Backend gegen injizierte Kommando-Ausführung (kein echtes
  OS-Keychain im CI).

Die native-modul-spezifischen Vorbedingungen (Windows-Packaging, eigene JNA/Keyring-Lib-Prüfung,
GraalVM-Native-Image-Config, eigener Security-Review) leben im Tracker
[`../open/keychain-native-provider-module.md`](../open/keychain-native-provider-module.md).

## Manuelle Windows-Verifikation (nicht CI-abbildbar)

Der echte Windows-Round-Trip (Blob-Kodierung, `CredReadW`-Verhalten, Exit-Codes) läuft **nicht** in
der Linux-Docker-CI — der Unit-Test deckt nur Kommando-Konstruktion, Env-Übergabe, Injection-Sicherheit
und Ergebnis-Mapping ab. Manuelles Protokoll auf einem Windows-Host:

1. Eintrag anlegen (Wert = **vollständige** Connect-URL):
   `cmdkey /generic:pg-prod /user:app /pass:"postgresql://app:secret@db:5432/prod"`
   (bzw. für Account-Form Target `pg-prod/app`).
2. `keychain://pg-prod` referenzieren (z. B. `data profile --source "credentialRef=keychain://pg-prod"`
   in der Map-Form) und prüfen: Auflösung liefert die URL, Operation verbindet.
3. Nicht vorhandener Eintrag → **fail-closed** (`KEYCHAIN_ENTRY_NOT_FOUND`), kein stiller Rückfall.
4. Constrained Language Mode (Enterprise-Lockdown, `Add-Type` blockiert) → sauberes `Unavailable`
   (kein Hänger) — dort ist `env:`/`file:` die richtige Schicht (oder das opt-in-Nischen-Modul).
5. Gegenprobe: in keiner Ausgabe/keinem Log taucht der Secret-Wert auf; die PowerShell-Args (`ps`)
   enthalten nur das konstante `-EncodedCommand`, nicht den Service-Namen oder das Secret.
