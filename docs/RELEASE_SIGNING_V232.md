# Family Location v2.3.2 release signing

Pinned signer SHA-256: `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`

Existing release JKS/alias are unchanged:
- C:\Users\Admin\Documents\FamilyLocation-Signing\Family-Location-Release-2026.jks
- family-location-release-2026
- Parent com.family.parent / Child com.family.child; versionName 2.3.2; code36.

STATUS: STOPPED BEFORE SIGNING AND PRODUCTION DEPLOYMENT FOR REVIEW. No v232 Installable APK or signed Release ZIP has been created. Device enrollment keys are independent of this APK signer; no new release key is created.

CI/review APKs are UNPROVISIONED. The v232 signing gate now additionally requires a hash-only BOOTSTRAP-PROVISIONING.json, fixed role scopes with no initial time expiry and actual DEX matches for each private capability. Neither APK may contain the other role's capability. Missing/incorrect provisioning blocks the operation BEFORE any password prompt or JKS inspection. CI outputs cannot be promoted by supplying a label alone.

See [BOOTSTRAP_RELEASE_V232.md](BOOTSTRAP_RELEASE_V232.md) for the complete local generate/inject/unsigned preflight procedure and the explicit stop point. No tokens/passwords/private keys may enter Git, docs, CI logs or release reports. A future separately authorized signing run will prompt locally with SecureString and use this same pin, verifying BOTH package/version/certificate/apksigner/16KiB zipalign gates before either Installable APK or Release ZIP is published. Existing v230 release artifacts are preserved.

V232 STOP: source review, production CAS migration, stable server pepper and production verification must precede local signing. Initial capabilities have no time expiry. Never send a password in chat.
