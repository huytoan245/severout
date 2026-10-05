# Family Location v2.3.0 – Clean Reliable Baseline

Canonical Android source is tracked here; build directly with Gradle9.5/JDK17/SDK36.
See ../docs/ARCHITECTURE_AUDIT_V230.md, ../docs/V230_CANONICAL_SOURCE.md and
../docs/RELEASE_SIGNING_V230.md for validation, clean identity and the new signer gate.
Production remains paused until the exact new UIDs and real Secrets are provisioned.

The mapping design below is retained from the original OSM/MapLibre edition.

Two Android apps:
- `child-app`: transparent background location sharing with an Android foreground-service notification, analog clock UI, battery-oriented Visit/Trip engine.
- `parent-app`: current location and daily visit history on OpenStreetMap, plus one-tap handoff to Google Maps for place inspection or turn-by-turn directions.

## Mapping architecture (v1.1)

The Parent app no longer embeds Google Maps SDK.

- Map renderer: MapLibre Native Android.
- Map data shown in the app: OpenStreetMap Standard raster tiles.
- Google Maps is opened only when the user taps a button:
  - `XEM TRÊN GOOGLE MAPS` opens the exact latitude/longitude in Google Maps.
  - `DẪN ĐƯỜNG` opens directions to the exact latitude/longitude.
- Google Maps URLs do not require a Google Maps API key.
- No Google Maps Cloud Billing is required for the embedded Parent map.

## OpenStreetMap usage rules implemented by design

- Visible `© OpenStreetMap contributors` attribution.
- Interactive on-screen viewing only.
- No bulk tile download, no map-area prefetch, and no offline map-download feature.
- Tile URL is isolated in `parent-app/src/main/assets/osm_raster_style.json` so it can be replaced with another OSM-derived provider later without rewriting map UI logic.

## Firebase still required

1. Use one Firebase project.
2. Register Android apps `com.family.child` and `com.family.parent`.
3. Put each app's `google-services.json` in its module directory.
4. Enable Anonymous Authentication and Firestore.
5. Deploy secure production rules from `backend/firestore.rules` only after hardening family/device provisioning.

## Important Android behavior

- Background location permission is user-controlled; Android 11+ requires the user to enable "Allow all the time" in Settings.
- Foreground location service keeps an ongoing, low-importance notification.
- Reboot recovery is best-effort and subject to OEM/Android restrictions.
- Force-stop cannot be bypassed.

## Test strategy

Core visit/trip logic remains pure Kotlin and can be tested independently of Android/Firebase. See `docs/TEST-REPORT.md`.
