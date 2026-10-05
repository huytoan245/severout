# Family Location v2.3.1 / code35 — role bootstrap review

Status: NO SIGNING, NO PRODUCTION DEPLOYMENT. Private personal sideload: exactly one Parent and one Child. Firebase Spark + Cloudflare Workers Free + FCM HTTP v1. No manual UID, QR or pairing code in either app.

## Cause and change

The previous empty-slot policy accepted any valid Firebase identity plus a device-key proof while enrollment was enabled. That proved control of a key, not ownership of this family. An outsider could register first without possessing either private APK.

An empty role now additionally requires its own 256-bit bootstrap capability. Parent and Child have distinct values; both are scoped to family-01 and their role; the logical Child is child-01. Missing provisioning, a wrong/other-role token, expiry or consumed token fails closed. ENROLLMENT_ENABLED remains an optional operator kill switch; it never authorizes a claim by itself. Automatic locking does not require toggling it after pairing.

## Protocol and persistent authority

The operator provisions HASHES ONLY in families/family-01 before distributing APKs. Initial bootstrap-plan.mjs emits an offline create-only REST commit proposal (exists=false); it cannot replace an existing family. No UID is supplied. Fields include epoch=1, role BootstrapHash (SHA-256 of the exact canonical base64url capability string, encoded as base64url), BootstrapExpiresAt and BootstrapConsumed=false. Separate role fields enforce scope; shared hashes are rejected. Capability generator uses .NET cryptographic RandomNumberGenerator.GetBytes(32), no padding base64url, and seven-day expiry.

1. App signs in anonymously and creates/reuses its non-exportable Android Keystore P-256 device key. This is independent of the APK release JKS.
2. Worker verifies Firebase JWT against the fixed project and derives UID; clients cannot choose UID in the request.
3. Authenticated challenge fixes family/device/role/purpose/epoch, creates a random 120-second nonce and reports needsBootstrap. A stranger cannot obtain an occupied role challenge.
4. For an empty slot, the app signs the registration payload INCLUDING its capability. Signature binds UID, role, family/device, purpose, nonce and payload digest. Body size and exact field sets are enforced.
5. Registry verifies nonce, epoch, signature, public key, empty slot, hash, unconsumed state and expiry. The entire conditional Firestore REST commit writes owner UID/key/registeredAt AND consumed=true/consumedAt/claimProofHash in ONE document write using currentDocument.updateTime. Concurrent attempts cannot both win. A failed precondition or write cannot consume without claiming.
6. Nonce/proof fingerprint and response are journaled in SQLite. Firestore also stores the committed claim proof hash. After a lost response, only the identical still-valid nonce/proof for the committed UID/key is idempotent; a fresh proof bearing that consumed token is rejected, even from the same owner. No plaintext capability is persisted by the server.
7. After nonce expiry or a cold app restart, the registered UID/key can resume with a fresh signed registration payload that OMITS bootstrap. This confirms existing membership and never claims a different slot. If only one of UID/key survives uninstall, the mismatch fails closed.

Runtime wake/token uses strict payloads without any bootstrap field. Diagnostics/family state requires mapped UID. GPS, journey and events use Firestore Rules based on the server-owned role mapping. Client token writes remain denied; backend token CAS checks Child UID/key/epoch. UID and epoch changes invalidate prior authority. A third UID, changed device key, replayed signature or recovered old identity cannot take over.

When both roles are present locked=true and both capabilities are consumed. Registered clients cannot edit/delete family mapping, bootstrap hashes, consumed flags or recovery records. Hashes are not Firebase/Cloudflare/FCM/admin credentials. No plaintext capability is in server configuration, source, docs, committed fixtures or CI logs; tests generate random values in memory.

## APK leak and remaining threat model

This removes anonymous pre-enrollment squatting by someone who lacks a valid role capability. It DOES NOT prove which human owns an APK: a leaked private APK before enrollment can reveal its embedded one-time capability. A thief who has it can race the intended phone for THAT empty role until expiry/revocation. Secure private distribution and operator recovery/revocation remain necessary. After consumption, APK extraction does not supply the registered UID plus device private key and cannot reclaim the slot.

This is NOT hardware attestation, Play Integrity or public Play Store onboarding. Android Keystore protects key extraction; we do not attest hardware/software provenance. A compromised already-enrolled phone, privileged operator/service-account compromise, availability attacks and theft of the private build/config directory remain outside this capability guarantee. Initial operator provisioning/deployment still occurs before distribution, never as end-user setup after installation.

Sources: [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Firestore atomic writes](https://firebase.google.com/docs/firestore/manage-data/transactions).

## Update and explicit recovery

Update/reboot/process restart retain existing Firebase UID and device key and do not need a bootstrap token. Full uninstall/clear data can lose them; no automatic takeover path is added. Existing production identities are not cleared or migrated implicitly.

recovery-plan.mjs remains OFFLINE, with no cloud/apply option. It now requires --hashes containing fresh role hashes. It increments epoch, retires old UID hashes and every recovered-role bootstrap hash, clears only selected role ownership and claim fingerprint, preserves the other role, and atomically clears Child FCM authority/revises generation when recovering Child. Old or previously retired capability hashes cannot be reused. The server also refuses hashes listed in retiredBootstrapHashes. Location and events are preserved.

Example proposal only, after obtaining an admin-read normalized snapshot and generating NEW capabilities outside Git:

```powershell
node .\cloudflare-wake\recovery-plan.mjs --snapshot 'C:\Users\Admin\Documents\FamilyLocation-Bootstrap\recovery-snapshot.json' --role child --hashes 'C:\Users\Admin\Documents\FamilyLocation-Bootstrap\v231-recovery\BOOTSTRAP-PROVISIONING.json' --output 'C:\Users\Admin\Documents\FamilyLocation-Bootstrap\child-recovery-review.json'
```

Review and cloud application require a separate future operator action; no production write is performed here. Only the recovered role receives new server authorization. Capabilities for the unaffected role need no provisioning or runtime use.

## Validation A–O

| Requirement | Actual coverage |
|---|---|
| A/B correct Parent/Child | Both installation orders, distinct random capabilities, paired lock |
| C/D wrong values | Random wrong values reject; owner/consumed unchanged |
| E/F role swap | Opposite-role capability rejects for both roles |
| G consumed replay | Fresh proof rejected, including original UID/key |
| H third UID/stale extracted token | Third UID and different key rejected after restart |
| I concurrency | Multiple contenders and two registry instances; exactly one owner per role |
| J atomic failure | Injected pre-commit failure leaves owner and consume unchanged; real emulator stale CAS does not alter either |
| K response loss | Commit succeeds then response lost; identical proof retry succeeds and keeps owner |
| L paired | Both consumed, lock=true; bootstrap-bearing fresh proof rejected |
| M runtime | Wake and token succeed without bootstrap; added bootstrap fields rejected |
| N persistence | Registry restart and real workerd/SQLite restart retain consumed ownership |
| O Rules | Actual emulator denies all client hash/consume edits; changing server mapping revokes old Parent and grants replacement Parent |

See VALIDATION_V231.md for measured counts and limitations; BOOTSTRAP_RELEASE_V231.md for local preparation. CI/debug/review APKs contain no capability, cannot enroll a fresh phone, and are blocked by the signing gate. They are not installable release deliverables.
