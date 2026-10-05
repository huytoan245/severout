# Test report

## Executed in this environment
- Core compile with Kotlin/JVM: PASS
- GPS jitter <= same-area radius does not create a new visit: PASS
- Single large GPS jump followed by return does not close visit: PASS
- Sustained departure closes visit and starts trip: PASS
- Stable stop after trip creates a new visit: PASS
- Unfamiliar + long-stop + far-from-known flags: PASS
- Full synthetic day scenario office -> trip -> new visit: PASS
- Reboot reseed model and explicit no-data gap preservation: PASS

## Not executable here
This runtime does not contain Android SDK/Gradle tooling and has no connected Android device/emulator. Therefore APK assembly, emulator instrumentation, MapLibre/OpenStreetMap rendering, Google Maps URL handoff, FCM delivery, Firestore security behavior, reboot on a real Android device, OEM battery management, and two-device internet synchronization have NOT been truthfully verified here.

These must be run after adding real Firebase configuration, preferably on the user's spare Android phone before family use.
