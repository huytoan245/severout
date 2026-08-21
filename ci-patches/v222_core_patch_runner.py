from pathlib import Path

# Run the v2.2.2 source patch after correcting one validation-only literal.
# MainActivity keeps the count dynamic (${WisdomStore.count()}); therefore the
# source contains "lời khuyên", not a hard-coded "220 lời khuyên" string.
source_path = Path('ci-patches/v222_core_patch.py')
source = source_path.read_text(encoding='utf-8')
source = source.replace("'220 lời khuyên'", "'lời khuyên'")
exec(compile(source, str(source_path), 'exec'))

# If Location changes from OFF back to ON, immediately return to the normal
# silent protection notification. This is a state transition, so it intentionally
# overrides the ordinary 8-hour re-show delay that follows a manual dismissal.
location_service = Path('appsrc/child-app/src/main/java/com/family/child/LocationService.kt')
text = location_service.read_text(encoding='utf-8')
old = '''        if (locationOn) getSystemService(NotificationManager::class.java).cancel(LOCATION_REMINDER_NOTIFICATION_ID)\n\n        if (signature != lastHealthSignature) {\n            lastHealthSignature = signature\n            when {'''
new = '''        if (locationOn) getSystemService(NotificationManager::class.java).cancel(LOCATION_REMINDER_NOTIFICATION_ID)\n\n        val locationWasOff = lastHealthSignature.endsWith(":false")\n        if (signature != lastHealthSignature) {\n            if (fine && locationOn && locationWasOff) {\n                getSystemService(NotificationManager::class.java).notify(TRACKING_NOTIFICATION_ID, trackingNotification())\n                getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n                    .remove(KEY_TRACKING_NOTIFICATION_DISMISSED_AT)\n                    .apply()\n                publishLocalStatus("tracking_notification_restored_location_on")\n            }\n            lastHealthSignature = signature\n            when {'''
if old not in text:
    raise SystemExit('v2.2.2 Location-on notification restore anchor not found')
text = text.replace(old, new, 1)
location_service.write_text(text, encoding='utf-8')

required = [
    'tracking_notification_restored_location_on',
    'TRACKING_NOTIFICATION_RESHOW_MS = 8 * 60 * 60_000L',
    'setContentIntent(locationSettingsPi)',
]
final_text = location_service.read_text(encoding='utf-8')
for needle in required:
    if needle not in final_text:
        raise SystemExit(f'v2.2.2 runner validation missing {needle!r}')
