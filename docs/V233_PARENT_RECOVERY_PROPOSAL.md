# Parent identity recovery — REVIEW ONLY

User confirmed uninstall/Clear Data. UPDATE_IN_PLACE cannot restore erased Anonymous Auth credentials or Android Keystore keys. The server still has a Parent slot, public key and stable device-binding hash. Those server fields cannot reconstruct the old private key. Phone-local credentials and same-device binding match are UNKNOWN.

Read-only backup: 2026-11-06 12:22:37 Asia/Saigon, protected local diagnostics-v233-20261106 directory outside Git/releases; family revision 2026-10-16T15:03:46.713235Z, schema232/epoch3, both slots enrolled. Do not publish raw snapshots. No identity recovery has been applied.

## Proposed route after separate approval

1. Re-read family/device revisions and securely save family, device, relevant Auth metadata and existing rebind audit. Stop if mappings/revisions differ from the reviewed snapshot. Never clear a slot or mint bootstrap capabilities.
2. Confirm this is the original Parent phone, same Android user/profile, package and release signer. Inspect only credential-presence flags locally first. If the old UID and private key actually survive, use normal v233 UPDATE_IN_PLACE proof registration and do not rebind.
3. If credentials are lost, prepare a separately approved Parent-only recovery build using the existing v232 `/v1/rebind/parent` protocol. Create a fresh Anonymous Auth identity and installation proof key only after that approval. Do not change the release signer. Match the derived same-device recovery material against the existing server binding; never copy it into chat/reports/logs. The UPDATE_IN_PLACE APKs in this PR deliberately cannot perform this action.
4. Existing server checks the fixed family/role, proof signature, stable binding, fresh identity/key, epoch and Parent rebind generation. Its atomic commit CAS-checks the family revision and create-only audit, then changes only Parent identity/key/generation, increments epoch and retires old Parent hashes. Child identity/key, token, coordinates, history and events remain intact. Concurrent/stale/mismatched binding must fail closed.
5. If the binding does not match, STOP. Do not use generic bootstrap recovery or overwrite mapping. A separately designed and approved operator recovery would be needed; this PR does not implement it.
6. Verify old Parent access denied, new Parent role correct, Child still authorized and a new command uses the new epoch. Do not send FCM before phone-test authorization.

## Abort / rollback

Before commit or on CAS/binding failure: no mapping changes; retain protected snapshot and stop. If response is lost, resolve the exact nonce/proof/audit idempotently using the existing rebind journal; never blindly retry with another identity. After a successful commit, do not restore old mapping or decrement epoch: that would revive retired credentials. Stop Parent commands, preserve current state and audit, and require a reviewed forward recovery with fresh CAS/epoch. Any unused newly created Auth user is reviewed separately; no automatic Auth deletion.

Approval must explicitly cover Parent-only new Auth UID/installation key, same-device rebind and epoch increment. It does not authorize Child rebind, history deletion, backend deployment, signer change or installation. No executable production apply command is included.
