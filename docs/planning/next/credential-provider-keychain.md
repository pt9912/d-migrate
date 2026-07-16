# `keychain:`-Credential-Provider (Folge-Slice der O4-Naht)

**Status**: Skeleton / Pending — aktivierbar, sobald die O4-Provider-Registry steht
([ADR 0035](../../adr/0035-credential-provider-scheme-registry.md), Slice 2 der
[`LN-025`](../../../spec/lastenheft-d-migrate.md#ln-025)-O4-Staffel).
Baut auf der **fertigen** `CredentialProviderRegistry` auf; kein Architekturwechsel — nur ein
weiterer `CredentialProvider`.

**Trigger**: [ADR 0035](../../adr/0035-credential-provider-scheme-registry.md) D4 hat `keychain:`
bewusst aus dem RC-Slice ausgeschnitten (plattformspezifische Native-/CLI-Integration + zwingender
Headless-Fallback, s. auch [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md) D4).
Der `file:`-Provider bewies die Naht; `keychain:` ist der nächste Provider auf derselben Registry.

## Scope

- Neuer `KeychainCredentialProvider` (Scheme `keychain:`) in `adapters/driven/connection-config`,
  registriert in `defaultCredentialProviderRegistry()`. Vertrag wie `file:`/`env:`: der
  Keychain-Eintrag liefert eine **vollständige** Connect-URL (World-B-Parität).
- `credentialRef: keychain://<service>[/<account>]` — Dispatch am Scheme wie gehabt (ADR 0035 D2).

## Plattform-Mechanismen (Entscheidung offen)

| OS | Mechanismus | Zugriff |
|---|---|---|
| macOS | Keychain | `security find-generic-password` (CLI-Shell-out) oder Security.framework (JNA/native) |
| Windows | Credential Manager / DPAPI | native (JNA) oder `cmdkey`/PowerShell |
| Linux | Secret Service (libsecret) | `secret-tool` (CLI) oder D-Bus |

**Kern-Entscheidung**: CLI-Shell-out (keine neue Runtime-Abhängigkeit, aber Prozess-Start +
Parsing pro OS) **vs.** native/JNA (Abhängigkeit + Packaging). ADR-Inkrement vor Bau.

## Headless-Fallback (zwingend)

CI/Docker/Server haben **keinen** Keychain. `keychain:` muss dort **fail-closed** sauber scheitern
(secret-freie Meldung „kein Keychain verfügbar" → Registry-`Failure`), damit ein Lauf nicht
undiagnostizierbar hängt. Kein stiller Degrade. Doku muss die Schicht-Wahl aussprechen (headless →
`env:`/`file:`, nicht `keychain:`), analog [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md)-Konsequenz „richtige Schicht wählen".

## Vorbedingungen

- ADR-Inkrement: Shell-out vs. native, Fallback-Semantik, unterstützte OS-Matrix.
- Security-Review: Prozess-Argument-Leaks (Keychain-CLI-Args in `ps`), kein Secret in Logs,
  Keychain-Prompt-Verhalten (interaktiver Unlock) im nicht-interaktiven Lauf.
- Test-Strategie: Provider gegen ein injizierbares Keychain-Backend (kein echtes OS-Keychain im CI).
