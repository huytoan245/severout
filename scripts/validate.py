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
version, code = ('2.3.1',35) if '--v2210' not in sys.argv and '--v2211' not in sys.argv else (('2.2.10',32) if '--v2210' in sys.argv else ('2.2.11',33))
for module, package in [('parent-app','com.family.parent'),('child-app','com.family.child')]:
    contains(root/'appsrc'/module/'build.gradle.kts', [f'applicationId = "{package}"',f'versionCode = {code}',f'versionName = "{version}"'])
    ET.parse(root/'appsrc'/module/'src/main/AndroidManifest.xml')
contains(child/'LocationService.kt', ['return START_STICKY','local.commitSample(journal, JournalCheckpoint.encode(engine, sample))','saveRefreshResult(refreshFor, payload)','lastCloudHeartbeatSuccessAt','processExitImportance','pendingJourneyCount','LocationPolicy.problem','running = false'])
s = (child/'LocationService.kt').read_text(encoding='utf-8')
assert s.index('local.commitSample(journal') < s.index('ChildDeviceWriter.write(persisted)')
contains(child/'LocalDb.kt', ['"location.db", null, 2','beginTransaction()','insertOrThrow','CREATE TABLE IF NOT EXISTS journal_keys'])
contains(child/'RecoveryGeofenceManager.kt', ['FLAG_MUTABLE','RADIUS_M = 250f','NEVER_EXPIRE','INITIAL_TRIGGER_EXIT','client.addGeofences(request, pi)'])
assert 'removeGeofences(' not in (child/'RecoveryGeofenceManager.kt').read_text(encoding='utf-8')
contains(child/'RecoveryGeofenceReceiver.kt', ['LocationService.running','goAsync()','pending.finish()'])
contains(child/'ChildWakeMessagingService.kt', ['message.priority == RemoteMessage.PRIORITY_HIGH','message.originalPriority','WakeRequestPolicy.parse','refreshReceivedFor'])
contains(child/'ProtectionNotifier.kt', ['22942','setSilent(true)'])
contains(child/'LocationService.kt', ['TRACKING_NOTIFICATION_ID = 42','LOCATION_REMINDER_NOTIFICATION_ID = 77','"protection"','"family_location_reminder"'])
contains(child/'NetworkFailoverManager.kt', ['bindProcessToNetwork(network)','HEALTHY_PROBE_INTERVAL_MS = 60_000L','DEGRADED_PROBE_INTERVAL_MS = 15_000L','releaseCellular("vpn_active")','cellularNetwork != network','generation == probeGeneration'])
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
if code >= 33:
    contains(child/'LocationService.kt', ['generation != listenerGeneration','generation != routeGeneration','lastFreshFixAttemptElapsed','"fcmOriginalPriority"','"unusedAppRestricted"'])
    assert s.index('try { startAsForeground(); running = true }') < s.index('state.restore(engine)')
    contains(child/'TokenState.kt', ['fcm_token_generation_v2211','expected != null','current(context) != snapshot'])
    contains(child/'WakeTokenSyncWorker.kt', ['EnrollmentClient.ensureRegistered','EnrollmentClient.signed','Source.SERVER','TokenState.rebase','backend_device_key_verified'])
    contains(parent/'ParentWakeBridge.kt', ['operation.result.get()','WAKE_WORKER_URL','identity_changed','currentDocument.updateTime','refreshRequestedBy'])
    contains(parent/'MainActivity.kt', ['SurvivalHealth.fields','Đã đăng ký tự động','Wake backend đã nhận yêu cầu','ParentWakeBridge.prepareCommand'])
    contains(root/'cloudflare-wake/src/worker.js', ['verifier.verify','body_too_large','FAMILY_REGISTRY'])
    contains(root/'cloudflare-wake/src/coordinator.js', ['storage.transaction','tx.setAlarm','attempts < 8','rate_limited','sentFingerprint'])
    conf=json.loads((root/'cloudflare-wake/wrangler.jsonc').read_text(encoding='utf-8'))
    assert conf['migrations'][0]['new_sqlite_classes']==['WakeCoordinator']
    for path in list((root/'appsrc').rglob('*.kt'))+[root/'cloudflare-wake/wrangler.jsonc']:
        content=path.read_text(encoding='utf-8')
        assert '-----BEGIN PRIVATE KEY-----' not in content
    print('v2211 survival/secret boundary static checks passed; production NOT VERIFIED')

if code >= 34:
    contains(child/'MainActivity.kt',['RequestMultiplePermissions','ACCESS_COARSE_LOCATION','ACCESS_BACKGROUND_LOCATION','first_setup_step_v230','onDismissRequest = onDismissContinuousRun'])
    contains(child/'LocationService.kt',['journalThread.looper','local.finishRefresh','ChildDeviceWriter.write(progress)','refreshPersistedFor','refreshUploadedFor'])
    contains(child/'ChildWakeMessagingService.kt',['it.acceptWake(requestId)','inbox_persist_failed'])
    contains(child/'BoundedRest.kt',['finally { c.disconnect() }','instanceFollowRedirects = false'])
    contains(child/'ChildHttpsBridge.kt',['currentDocument.updateTime','DeviceMutationPolicy.select'])
    contains(child/'TokenState.kt',['fun rebase','remoteGeneration + 1'])
    contains(parent/'MainActivity.kt',['snapshotSeen >= seen','ParentWakeBridge.newRequest(context)'])
    contains(root/'appsrc/parent-app/build.gradle.kts',['https://family-location-wake.huytoan0979928450.workers.dev','workerOrigin.rawQuery == null'])
    assert '4675c26756f' not in (root/'scripts/sign-release.ps1').read_text()
    print('v230 canonical source, first-use permission, durable inbox and guarded publication validation passed')

if code == 35:
    contains(root/'cloudflare-wake/src/enrollment.js',['registration_raced','nonce_reused','invalid_signature','slot_occupied','family_changed'])
    contains(parent/'MainActivity.kt',['Đang chờ Máy Con kết nối','Máy Con · Đang kết nối','familyListener.remove()'])
    for path in [root/'cloudflare-wake/src/worker.js',root/'cloudflare-wake/src/coordinator.js',root/'cloudflare-wake/render-rules.mjs']:
        text=path.read_text(encoding='utf-8-sig')
        assert 'env.PARENT_UID' not in text and 'env.CHILD_UID' not in text
    print('v231 automatic enrollment, dynamic roles and device proof static checks passed')

if code == 35:
    contains(root/'cloudflare-wake/src/enrollment.js',['bootstrapAvailable','BootstrapConsumed','ClaimProofHash','bootstrap_consumed'])
    contains(root/'appsrc/enrollment/src/main/java/com/family/enrollment/BootstrapPayload.kt',['needsBootstrap','purpose == "register"','bootstrap_not_provisioned'])
    contains(root/'scripts/sign-release.ps1',['Assert-BootstrapApkPair'])
    for role in ['parent','child']:
        contains(root/f'appsrc/{role}-app/build.gradle.kts',[f'FAMILY_LOCATION_{role.upper()}_BOOTSTRAP','Bootstrap injection is forbidden in CI','BOOTSTRAP_TOKEN'])
    print('v231 role bootstrap, hash-only atomic consumption and unprovisioned signing block checks passed')
