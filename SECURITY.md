# Security Policy

## Supported versions

Only the latest release receives security fixes.

| Version | Supported |
|---|---|
| Latest release | ✅ |
| Older releases | ❌ |

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Report them privately instead:

1. Use GitHub's **[private vulnerability reporting](../../security/advisories/new)** (Security tab → *Report a vulnerability*), or
2. contact the maintainer through <https://abedallask.netlify.app/>.

Please include what you found, the affected version, steps to reproduce, and the impact you expect. You will get an acknowledgement within 7 days. Once a fix is released, the issue is disclosed, with credit to you if you wish.

## Scope and design notes

Areas where reports are especially welcome:

- **Wi‑Fi remotes:** TLS handling for TVs with self-signed certificates (trusted only on the Wi‑Fi client and only for private IPv4 addresses), Android TV pairing, and the Samsung/LG/Roku/Gree/Daikin protocols.
- **Stored secrets:** device tokens and account credentials (Electra Smart, Sensibo) are encrypted at rest with an Android Keystore key (`SecretVault`).
- **Importers:** parsing of Flipper `.ir`, IRDB CSV, SmartIR JSON and code packs from user-supplied files or URLs.
- **Update check:** reads GitHub releases over HTTPS and only offers `https://` download links.

The app has no account, no analytics and no backend of its own.
