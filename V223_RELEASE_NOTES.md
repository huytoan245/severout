# Family Location v2.2.3

PATCH release based on v2.2.2.

Fixed:
- Child now checks the app-level notification switch in addition to POST_NOTIFICATIONS.
- Child now checks the Android reminder notification channel before acknowledging a Parent reminder as shown.
- Parent receives a distinct `notification_channel_disabled` result when the reminder channel is disabled.
- Silent 8-hour protection-notification re-post attempts stop when the protection channel is disabled.
- Location-ON recovery respects a user-disabled protection channel.

Preserved:
- One Parent + one Child (`child-01`).
- Six-hour continuous Location-off threshold before the Parent reminder button appears.
- Parent must explicitly press the reminder button; no automatic remote reminder is sent.
- Tapping the full Child reminder opens Android Location settings.
- 220 online-scam prevention tips and the existing permanent protection message.
- Existing location, journey, connection health, Firebase/HTTPS fallback behavior.

Release remains on a separate branch until physical two-device regression testing is complete.
