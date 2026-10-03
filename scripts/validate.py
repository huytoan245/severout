from pathlib import Path
import xml.etree.ElementTree as ET
import json
import sys

root = Path(__file__).resolve().parent.parent
child = root/'appsrc/child-app/src/main/java/com/family/child'
parent = root/'appsrc/parent-app/src/main/java/com/family/parent'
def contains(path, values):
    s = path.read_text(encoding='utf-8-sig')
    for value in values: assert value in s, (str(path), value)
    return s
version, code = ('2.2.10', 32) if '--v2210' in sys.argv else ('2.2.11', 33)
for module, package in [('parent-app','com.family.parent'),('child-app','com.family.child')]:
    contains(root/'appsrc'/module/'build.gradle.kts', [f'applicationId = "{package}"',f'versionCode = {code}',f'versionName = "{version}"'])
    ET.parse(root/'appsrc'/module/'src/main/AndroidManifest.xml')
contains(child/'LocationService.kt', ['return START_STICKY','local.commitSample(journal, JournalCheckpoint.encode(engine, sample))','saveRefreshResult(refreshFor, payload)','lastCloudHeartbeatSuccessAt','processExitImportance','pendingJourneyCount','LocationPolicy.problem','running = false'])
s = (child/'LocationService.kt').read_text(encoding='utf-8')
assert s.index('local.commitSample(journal') < s.index('val firebaseStart = SystemClock.elapsedRealtime()')
contains(child/'LocalDb.kt', ['"location.db", null, 2','beginTransaction()','insertOrThrow','CREATE TABLE IF NOT EXISTS journal_keys'])
contains(child/'RecoveryGeofenceManager.kt', ['FLAG_MUTABLE','RADIUS_M = 250f','NEVER_EXPIRE','INITIAL_TRIGGER_EXIT','client.addGeofences(request, pi)'])
assert 'removeGeofences(' not in (child/'RecoveryGeofenceManager.kt').read_text(encoding='utf-8')
contains(child/'RecoveryGeofenceReceiver.kt', ['LocationService.running','goAsync()','pending.finish()'])
contains(child/'ChildWakeMessagingService.kt', ['message.priority == RemoteMessage.PRIORITY_HIGH','message.originalPriority','maxOf(requestId','refreshReceivedFor'])
contains(child/'ProtectionNotifier.kt', ['22942','setSilent(true)'])
contains(child/'LocationService.kt', ['TRACKING_NOTIFICATION_ID = 42','LOCATION_REMINDER_NOTIFICATION_ID = 77','"protection"','"family_location_reminder"'])
contains(child/'NetworkFailoverManager.kt', ['bindProcessToNetwork(network)','HEALTHY_PROBE_INTERVAL_MS = 60_000L','DEGRADED_PROBE_INTERVAL_MS = 15_000L','releaseCellular("vpn_active")','cellularNetwork != network','probing.compareAndSet'])
contains(child/'WakeTokenSyncWorker.kt', ['Source.SERVER','ExistingWorkPolicy.KEEP','Tasks.await(FirebaseMessaging.getInstance().token'])
ui = contains(child/'MainActivity.kt', ['Điện thoại của bạn đang được bảo vệ an toàn.','Không phát hiện mối đe dọa, lừa đảo.','Cho phép ứng dụng hoạt động liên tục','continuous_run_prompt_shown_v228'])
for forbidden in ['GPS','MỞ CÀI ĐẶT QUYỀN','BẬT VỊ TRÍ','MỞ DANH SÁCH KHÔNG BAO GIỜ TỰ NGHỈ']:
    assert forbidden not in ui, forbidden
assert 'pin yếu' not in s.lower()
contains(parent/'MainActivity.kt', ['refreshExpiresAt','Máy Con chưa phản hồi sau 45 giây','refreshLocatingFor','LOCATION_REMINDER_DELAY_MS = 6 * 60 * 60_000L'])
contains(root/'appsrc/parent-app/src/main/AndroidManifest.xml', ['android:label="Kết Nối"'])
tips = (child/'WisdomStore.kt').read_text(encoding='utf-8')
import re
assert len(re.findall(r'^\s*"', tips.split('private val QUOTES', 1)[1], re.M)) == 220
contains(root/'firebase-wake/functions/index.js', ['devices/child-01','asia-southeast1','retry: true'])
contains(root/'firebase-wake/functions/wake-handler.js', ['runTransaction','wakeLeaseOwner','tokenOf(current) === claim.token','ttl: remaining','WAKE_TTL_MS = 15 * 60 * 1000'])
json.loads((root/'firebase-wake/functions/package-lock.json').read_text(encoding='utf-8'))
print('Static wiring/product preservation checks passed (not a device/production test)')
if code == 33:
    contains(child/'LocationService.kt', ['generation != listenerGeneration','generation != routeGeneration','lastFreshFixAttemptElapsed','"fcmOriginalPriority"','"unusedAppRestricted"'])
    assert s.index('try { startAsForeground(); running = true }') < s.index('state.restore(engine)')
    contains(child/'TokenState.kt', ['fcm_token_generation_v2211','expected != null','current(context) != snapshot'])
    contains(child/'WakeTokenSyncWorker.kt', ['runTransaction','currentDocument.updateTime','fcmTokenOwnerUid','https_readback_verified'])
    contains(parent/'ParentWakeBridge.kt', ['operation.result.get()','WAKE_WORKER_URL','identity_changed','currentDocument.updateTime','refreshRequestedBy'])
    contains(parent/'MainActivity.kt', ['SurvivalHealth.fields','Firebase UID Máy Cha','Wake backend đã nhận yêu cầu','ParentWakeBridge.prepareCommand'])
    contains(root/'cloudflare-wake/src/worker.js', ['verifier.verify','body_too_large','child-01'])
    contains(root/'cloudflare-wake/src/coordinator.js', ['storage.transaction','tx.setAlarm','attempts < 8','rate_limited','sentFingerprint'])
    conf=json.loads((root/'cloudflare-wake/wrangler.jsonc').read_text(encoding='utf-8'))
    assert conf['migrations'][0]['new_sqlite_classes']==['WakeCoordinator']
    for path in list((root/'appsrc').rglob('*.kt'))+[root/'cloudflare-wake/wrangler.jsonc']:
        content=path.read_text(encoding='utf-8')
        assert '-----BEGIN PRIVATE KEY-----' not in content
    print('v2211 survival/secret boundary static checks passed; production NOT VERIFIED')
