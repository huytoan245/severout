Empty-slot enrollment previously trusted any verified Firebase UID with a device-key proof. An outsider without either private APK could claim first. v2.3.1/code35 now requires distinct 256-bit family/role bootstrap capabilities for the initial claim, keeping the install/open/permissions UX.

The backend stores hashes only and claims UID/key plus permanently consumes the role capability in one conditional Firestore REST commit. Identical committed proof retries survive response loss; fresh consumed-token proofs, other UIDs/keys and role swaps are rejected. Existing membership resumes without bootstrap; runtime wake/token/history/diagnostics never uses it. Recovery requires new hashes, retires old capabilities/UIDs and advances epoch.

Local generators/build helpers keep private config and capability-bearing builds outside Git under protected Windows ACLs. CI/debug APKs remain unprovisioned. The signing gate checks hash-only provisioning, expiry and actual per-role DEX capability hashes before any password prompt; public review APKs cannot be signed as zero-setup releases.

Validation: 58 Worker tests; real workerd/SQLite with intercepted Google traffic; 59 actual Firestore emulator assertions including dynamic role revocation and atomic CAS; 37 Android unit tests plus core/scenario checks, both unsigned release builds and full lint; 73 password-free signing regressions and 12 Windows/10 portable bootstrap boundary tests, plus actual private unsigned injection and password-free preflight using ephemeral non-production capabilities. Detailed evidence/limits in docs/VALIDATION_V231.md.

A leaked private APK BEFORE enrollment can expose its one-time capability and race the intended phone. This is private personal sideload, not hardware attestation; after consumption the token cannot take over. Full uninstall/clear data still requires operator recovery.

Keep Draft; no signing, release key change, production provisioning/deploy, phone installation/uninstall or master merge. Existing v230 signer and signed release are preserved. Review local preparation in docs/BOOTSTRAP_RELEASE_V231.md.
