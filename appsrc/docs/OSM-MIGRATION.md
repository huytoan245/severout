# Google Maps SDK -> OpenStreetMap migration

## What changed

Parent app:
- Removed `com.google.maps.android:maps-compose`.
- Removed Google Maps API-key manifest placeholder and metadata.
- Added MapLibre Native Android.
- Added an OpenStreetMap raster style in `parent-app/src/main/assets/osm_raster_style.json`.
- Added visible OSM attribution.
- Added `XEM TRÊN GOOGLE MAPS` for the latest point.
- Added `DẪN ĐƯỜNG` for the latest point.
- Added `XEM ĐIỂM NÀY TRÊN GOOGLE MAPS` on history visits when coordinates are available.

Child app:
- No map dependency was added.
- Location acquisition and Firebase synchronization are unchanged by this map-provider migration.

## Google Maps handoff

The Parent app opens Google Maps using standard Maps URLs with the exact stored latitude/longitude. If the Google Maps Android app is unavailable, the same URL is opened by another capable app/browser.

This handoff is user-initiated only. It does not require embedding Google Maps SDK or creating a Maps API key.
