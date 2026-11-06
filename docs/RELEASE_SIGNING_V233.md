# Family Location v2.3.3 / code37 signing

Pinned signer SHA-256: `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`

Existing JKS and alias remain unchanged. Only the tested UPDATE_IN_PLACE pair is supported. No bootstrap, Auth/key reset or automatic rebind is allowed. The gate verifies both packages, version/code, existing signer, apksigner and 16KiB zipalign before publishing either Installable APK or Release ZIP.

Run `scripts/sign-v233-update.ps1` in local PowerShell 7. It prompts using SecureString; never send passwords in chat. Use `-CheckEnvironmentOnly` to run without any password or JKS inspection. Real signing is WAITING_FOR_LOCAL_PASSWORD until both signed outputs pass. Mock tests do not verify real APK signatures.

An update preserves credentials still on the phone. It cannot restore credentials erased by uninstall/Clear Data. See [Parent recovery proposal](V233_PARENT_RECOVERY_PROPOSAL.md) before testing the current Parent. No production deployment or recovery mutation is included.
