from pathlib import Path

source_path = Path("ci-patches/v225_cellular_failover_patch.py")
source = source_path.read_text(encoding="utf-8")

# v2.2.2 already expanded diagnosticFields with Location-off persistence.
# Adapt the v2.2.5 composition anchor to that actual generated source instead
# of weakening/removing validation.
old_anchor = "'''    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {\\n        val network = networkState()\\n        return mutableMapOf('''"
new_anchor = "'''    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {\\n        val network = networkState()\\n        val locationEnabled = isLocationEnabled()\\n        val locationOffSince = updateLocationOffSince(now, locationEnabled)\\n        return mutableMapOf('''"
old_replacement = "'''    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {\\n        val network = networkState()\\n        val failover = failoverManager.snapshot()\\n        return mutableMapOf('''"
new_replacement = "'''    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {\\n        val network = networkState()\\n        val locationEnabled = isLocationEnabled()\\n        val locationOffSince = updateLocationOffSince(now, locationEnabled)\\n        val failover = failoverManager.snapshot()\\n        return mutableMapOf('''"

if old_anchor not in source or old_replacement not in source:
    raise SystemExit("v2.2.5 diagnostic composition literals not found")
source = source.replace(old_anchor, new_anchor, 1)
source = source.replace(old_replacement, new_replacement, 1)
exec(compile(source, str(source_path), "exec"))

# When the requested Cellular network disappears while the Child app is bound
# to it, explicitly classify the transition as cellular_lost. LocationService
# then re-opens Firestore/HTTPS on the surviving default route immediately.
manager_path = Path("appsrc/child-app/src/main/java/com/family/child/NetworkFailoverManager.kt")
manager = manager_path.read_text(encoding="utf-8")
old = '''                        failoverSince = 0L\n                        wifiRecoverySuccesses = 0\n                        updateMode(defaultRouteMode(), forceNotify = true)'''
new = '''                        failoverSince = 0L\n                        wifiRecoverySuccesses = 0\n                        updateMode(defaultRouteMode(), forceNotify = true, reason = "cellular_lost")'''
if old not in manager:
    raise SystemExit("v2.2.5 cellular-loss recovery anchor not found")
manager = manager.replace(old, new, 1)
manager_path.write_text(manager, encoding="utf-8")

# Guard the executor submission as service shutdown can race a late Network
# callback. This should never crash the foreground location service.
manager = manager_path.read_text(encoding="utf-8")
old_probe = '''    private fun probe(network: Network, callback: (Boolean) -> Unit) {\n        io.execute {\n            val ok = try {'''
new_probe = '''    private fun probe(network: Network, callback: (Boolean) -> Unit) {\n        if (stopped.get()) return\n        try {\n            io.execute {\n                val ok = try {'''
if old_probe not in manager:
    raise SystemExit("v2.2.5 probe executor anchor not found")
manager = manager.replace(old_probe, new_probe, 1)
old_probe_end = '''            }\n            if (!stopped.get()) main.post { callback(ok) }\n        }\n    }\n\n    private fun findNetwork'''
new_probe_end = '''                }\n                if (!stopped.get()) main.post { callback(ok) }\n            }\n        } catch (_: java.util.concurrent.RejectedExecutionException) {\n            // Service is shutting down; ignore a late network callback.\n        }\n    }\n\n    private fun findNetwork'''
if old_probe_end not in manager:
    raise SystemExit("v2.2.5 probe executor closing anchor not found")
manager = manager.replace(old_probe_end, new_probe_end, 1)
manager_path.write_text(manager, encoding="utf-8")

final_manager = manager_path.read_text(encoding="utf-8")
for needle in [
    'reason = "cellular_lost"',
    'RejectedExecutionException',
    'bindProcessToNetwork(network)',
    'WIFI_FAILURES_TO_FAILOVER = 3',
    'WIFI_RECOVERY_SUCCESSES = 3',
]:
    if needle not in final_manager:
        raise SystemExit(f"v2.2.5 runner validation missing {needle!r}")

print("v2.2.5 patch runner applied composition and shutdown recovery fixes")
