---
status: accepted
date: 2026-07-19
decision-makers: pt9912
consulted: docs/planning/next/credential-provider-keychain.md, docs/adr/0035-credential-provider-scheme-registry.md, docs/adr/0034-master-key-architektur-credential-store.md
informed: adapters/driven/connection-config, hexagon/ports-common
---

# Keychain-Credential-Provider: Backend-Port mit Default-Shell-out und opt-in Native-Modul

> **Status: accepted (2026-07-19).** Das von
> [`credential-provider-keychain.md`](../planning/next/credential-provider-keychain.md)
> geforderte ADR-Inkrement **vor** dem Bau: legt den Zugriffs-**Mechanismus** nicht als
> Entweder-oder fest, sondern zieht ihn hinter einen Adapter-Port — Default native-frei,
> Native als opt-in.

## Kontext und Problemstellung

[ADR 0035](0035-credential-provider-scheme-registry.md) hat die principal-freie
`CredentialProviderRegistry` gebaut (`env:` + `file:`, Dispatch am Scheme). `keychain:`
war bewusst ausgeschnitten (D4): plattformspezifische Native-/CLI-Integration + zwingender
Headless-Fallback. Der Provider selbst ist trivial (ein weiterer `CredentialProvider` auf
der fertigen Registry) — die harte Frage ist der **Zugriffsmechanismus** auf den
OS-Schlüsselbund, mit einem echten Trade-off:

- **CLI-Shell-out** (`security`/`secret-tool`/PowerShell): native-frei, aber pro OS ein
  Kommando + Parsing, und Windows-Read ist umständlich.
- **Native/JNA** (Security.framework/DPAPI/libsecret): cross-platform, aber JNA wird
  **aktiviert** — heute ist JNA nur transitiv (clikt/mordant) und **inert** (kein
  `Native.load()` im Produktivcode). Das gefährdet das GraalVM-Native-Image-Ziel (1.0.0-Stable).
- **Keyring-Bibliothek**: am wenigsten eigener Code, aber die gängigen Libs sind selbst
  JNA-basiert (= dieselbe Aktivierung) **und** eine Fremd-Dependency **im Secret-Pfad**
  (liest alle Connect-Credentials → maximale Supply-Chain-/Wartungs-Relevanz).

## Entscheidungstreiber

- **Native-frei bleiben by default:** GraalVM Native Image ist ein 1.0.0-Stable-Ziel; JNA
  inert zu halten (kein `Native.load()` im Produktiv-Default) ist ein im internen Audit
  vermerktes Positiv.
- **Keine Fremd-Lib im Secret-Pfad by default** (Supply-Chain).
- **Cross-Platform/Windows als Möglichkeit**, ohne alle zu einer nativen Runtime zu zwingen.
- **Testbarkeit:** ein injizierbares Backend statt echtem OS-Keychain im CI (Plan-Vorgabe).
- **Fail-closed Headless:** in CI/Docker/Server ohne Keychain sauber scheitern, nicht hängen.
- **Hexagon-Konsistenz:** austauschbare Adapter sind das etablierte Muster
  (`storage-file` vs `storage-s3`, `persistence-memory` vs `-jdbc`).

## Betrachtete Optionen

1. **Nur Shell-out** — native-frei, aber Windows fix ausgeschlossen/umständlich.
2. **Nur Native/JNA** — cross-platform, aber JNA-Aktivierung + GraalVM-Reibung für alle.
3. **Nur Keyring-Lib** — wenig Code, aber Fremd-Lib im Secret-Pfad + meist JNA-basiert.
4. **`KeychainBackend`-Port + Default-Shell-out + opt-in Native-Modul** (gewählt).

## Entscheidung

Gewählt: **Option 4.** Der `keychain:`-Provider bleibt **eine** Registrierung; darunter
liegt eine zweite Port-Ebene, sodass der Mechanismus ein austauschbares Adapter-Detail ist.

- **`KeychainBackend`-Port** (in `adapters/driven/connection-config`): `isAvailable(): Boolean`
  + `lookup(service, account?): KeychainLookup` (`Found(value)` / `NotFound` / `Unavailable(detail)`).
  Wirft nicht; Fehler/Timeout → `Unavailable`.
- **`ShelloutKeychainBackend` = Default** (native-frei, in `connection-config`): macOS
  `security find-generic-password -s <service> [-a <account>] -w`, Linux
  `secret-tool lookup service <service> account <account>`. Ausführung via `ProcessBuilder`
  mit **Timeout** (kein Hängen an einem interaktiven Unlock-Prompt im nicht-interaktiven Lauf).
  Das **Secret kommt über stdout** — nicht in Prozess-Args (kein `ps`-Leak; Service/Account
  in den Args sind nicht geheim). `isAvailable()` prüft OS **und** Tool-Präsenz.
- **`KeychainCredentialProvider`** (`connection-config`): Scheme `keychain:`, ref-Form
  `keychain://<service>[/<account>]`. Kein Backend verfügbar → `Failure(KEYCHAIN_UNAVAILABLE)`;
  Eintrag fehlt → `Failure(KEYCHAIN_ENTRY_NOT_FOUND)`; leerer Wert → `Failure(EMPTY_VALUE)`;
  sonst `Success(url)` (der Eintrag trägt die **vollständige** Connect-URL, World-B-Parität).
  Weder Secret noch URL werden geloggt/in `detail` echot.
- **Neue Reason-Codes** `KEYCHAIN_UNAVAILABLE` / `KEYCHAIN_ENTRY_NOT_FOUND` in
  `ResolvedConnection` (Single Source), gespiegelt in `CredentialResolution`.
- **Backend-Auswahl beim Wiring:** `defaultCredentialProviderRegistry()` registriert
  `KeychainCredentialProvider(ShelloutKeychainBackend())`. Auf Headless/Windows meldet
  `isAvailable()=false` → sauberes fail-closed. Ein **opt-in-Modul `keychain-native`**
  (Windows-DPAPI + volle Parität via JNA **oder** geprüfte Keyring-Lib) implementiert
  denselben `KeychainBackend`-Port und wird — wenn vorhanden — beim Wiring bevorzugt
  (Regel: Native falls Modul da, sonst Shell-out, sonst fail-closed). **Nicht Teil dieses
  Slices** — dokumentierter Folge-Opt-in; die Lib-vs-eigene-JNA-Frage ist dann ein
  **isoliertes Adapter-Detail innerhalb `-native`**, ohne Rückwirkung auf den Kern.

**Kern-Slice jetzt:** Port + `ShelloutKeychainBackend` (macOS/Linux) + Provider +
Registrierung + Fake-Backend + Tests. Der `-native`-Weg bleibt eine spätere, opt-in-Lieferung.

## Konsequenzen

- **Positiv:** Default-Runtime bleibt **native-frei** (GraalVM-Ziel + Supply-Chain unberührt);
  kein forcierter Mechanismus; Windows/Voll-Parität als opt-in wie S3-vs-File; injizierbares
  Backend → deterministische Tests ohne OS-Keychain; fail-closed überall.
- **Negativ:** Im Default-Runtime ist **Windows** nicht abgedeckt (`keychain:` fail-closed
  dort bis das `-native`-Modul dazukommt); Shell-out setzt das jeweilige OS-CLI-Tool voraus
  (`secret-tool` ist auf Linux nicht immer installiert → sauberes `Unavailable`).
- **Abgrenzung:** `keychain-native` + Windows sind ein separater, ADR-benannter opt-in-Slice
  mit eigenem Security-Review (Prompt-Verhalten, Native-Packaging, GraalVM-Config). Der
  Mechanismus **innerhalb** dieses Moduls (eigene JNA vs Keyring-Lib) wird dort entschieden.

## Weitere Informationen

- [`credential-provider-keychain.md`](../planning/next/credential-provider-keychain.md) —
  der Plan (Scope, Plattform-Mechanismen, Headless-Fallback, Vorbedingungen).
- [ADR 0035](0035-credential-provider-scheme-registry.md) — die `CredentialProviderRegistry`,
  auf der dieser Provider aufsitzt (Slice `ImpPlan-1.0.0-RC-ln025-slice2-credential-provider-seam.md`).
- [ADR 0034](0034-master-key-architektur-credential-store.md) — Credential-Store-Architektur,
  Konsequenz „richtige Schicht wählen" (headless → `env:`/`file:`, nicht `keychain:`).
