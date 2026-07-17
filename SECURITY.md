# Security Policy

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub
issues, discussions, or pull requests.**

Report vulnerabilities through GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/pt9912/d-migrate/security)
2. Click **Report a vulnerability**

This creates a private advisory visible only to the maintainers.

Please include:

- The affected version (`d-migrate --version`) and dialect
  (PostgreSQL / MySQL / SQLite), if relevant
- A description of the issue and its impact
- Steps to reproduce — a minimal schema YAML or CLI invocation is ideal
- Any suggested mitigation you are aware of

You can expect an initial response within **7 days**. We will keep you
informed about the progress toward a fix and may ask for additional
detail. Once a fix is released, we will credit you in the advisory
unless you prefer to remain anonymous.

## Supported Versions

Security fixes are applied to the latest release line only. d-migrate
has not yet reached 1.0.0; older minor versions do not receive
backports.

| Version   | Supported          |
| --------- | ------------------ |
| 1.0.0-RC  | ✅                 |
| 0.9.x     | ❌ (upgrade to 1.0.0-RC) |

## Threat Model

Knowing what d-migrate does and does not defend against will help you
judge whether a given behaviour is a vulnerability.

d-migrate is an operator-run tool. It is a CLI (and an MCP server)
that an operator runs against databases they are authorised to access.
The operator is **not** the adversary — the operator can already read
their own connection credentials and issue arbitrary SQL against their
own databases, so "the operator can see their own password" is not a
vulnerability.

**Untrusted inputs** — d-migrate is expected to defend against these:

- **The source database.** Schema and data read from a database are
  untrusted. A database whose contents an attacker controls must not be
  able to compromise the machine running d-migrate, nor inject SQL into
  the target database. Identifiers such as table and column names are
  the sharpest edge here.
- **Input files.** Schema YAML, data files (CSV, JSON, Parquet), and
  configuration files may come from an untrusted source.
- **MCP requests.** When running `mcp serve`, requests and tool
  parameters are untrusted. See
  [ADR 0009](docs/adr/0009-mcp-resource-server-no-auth-server.md) for
  the authentication model: d-migrate acts as a resource server and
  validates externally issued tokens; it is deliberately not an
  authorisation server.
- **Credential storage.** The credential store
  ([ADR 0034](docs/adr/0034-master-key-architektur-credential-store.md),
  [ADR 0035](docs/adr/0035-credential-provider-scheme-registry.md))
  protects credentials at rest against an attacker with read access to
  the file, not against an attacker who already controls the operator's
  session.

**Out of scope:**

- Attacks requiring the operator's own privileges on their own machine
- Denial of service against the local CLI process by its own operator
- Vulnerabilities in the target databases themselves, or in the JDBC
  drivers (report those upstream; tell us too if we can mitigate)
- Deliberate design decisions recorded in `docs/adr/`. If you believe an
  ADR's security reasoning is wrong, that is worth reporting — say which
  ADR and why.

## Security Measures

The build enforces several security gates, all runnable locally:

- `make semgrep` — static analysis with a pinned, SHA256-verified
  ruleset, run hermetically (`--network none`)
- `make gates` — the full gate suite

Accepted findings are annotated inline with `# nosemgrep: <rule-id>`
and a rationale.
