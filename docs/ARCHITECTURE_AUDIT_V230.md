# Family Location v2.3.0 – Clean Reliable Baseline

Audit trước sửa code, 03/10/2026. Baseline thực: `8f648142d67e275bfb571f6111f05f240ef97408` trên `v2211-samsung-survival-and-wake`; branch mới `v230-clean-reliable-baseline`. Không sửa/merge master. Chạy lại reconstruct v2211 và static validation đều PASS trước audit. Không có AGENTS.md áp dụng trong workspace.

## Source và build

Source Android thực hiện được sinh từ base64 archive + workflow v229 + patch v2210/v2211; appsrc bị gitignore. Đây là rủi ro baseline: source review khác source build nếu quên reconstruction hoặc sửa trực tiếp rồi bị ghi đè. v230 sẽ lưu source cuối trực tiếp trong Git, giữ nguyên chain cũ để truy xuất lịch sử, không chạy patch lịch sử lên baseline mới. Lưu hash source audit để chứng minh nguồn gốc.

Gradle9.5/JDK17, AGP9.3.0 (built-in Kotlin Android), Kotlin JVM/Compose2.3.21, compile/target36 min26, Firebase BOM34.17.0, Messaging25.1.1 Child, WorkManager2.10.5, Play location21.4.0, Robolectric4.17. SQLite location.db v2 có journal_keys/checkpoint/transaction synchronous FULL, giữ migration v1. Dependencies giữ phiên bản đã build v2211; không nâng tùy tiện.

Đã đọc lifecycle LocationService, RecoveryStarter/BootReceiver/watchdog, geofence manager/receiver, network manager, Child UI/permission flow, FCM/token/local DB/checkpoint/GPS filter, core engine, Parent refresh/outbox/REST/Health, manifests/Gradle, Worker verifier/OAuth/coordinator và Rules renderer/tests.

## Hai luồng chính hiện tại

Parent user action → WorkManager outbox commit → Firestore command transaction (request timestamp, UID, TTL15m) → authenticated HTTPS Worker → RS256 Firebase ID token exact Parent UID → fixed child-01 command/token owner read → SQLite Durable Object acceptance/rate/alarm → FCM HTTPv1 HIGH → Child FCM preferences persist → legal FGS → service durable pending → fresh GPS → local sample journal → SDK/REST result → Parent matching ACK/terminal +45s timeout.

Continuous GPS callback → LocationPolicy validation → VisitEngine + raw sample → atomic SQLite events/checkpoint → fixed-ID SDK event write → bounded HTTPS fallback → delete pending only on server success → Parent Journey sorts original event time. Engine keeps one trip anchor, not all points. Process death after DB commit replays; death before durable commit cannot guarantee capturing an in-flight callback. No architecture can reconstruct GPS never delivered by Android.

## Root causes còn thực tế trong v2211

1. **Clean install không xin quyền runtime.** Child MainActivity.evaluateSilentSetup chỉ kiểm tra quyền, còn nút prompt chỉ mở battery exemption. App mới thiếu fine/background/notifications sẽ không tracking. v230 cần một prompt app duy nhất, rồi system permission flow riêng foreground → background, không tự cấp, không nhắc lại UI setup.
2. **FCM payload thiếu device/expiry binding và inbox commit không kiểm tra.** Chỉ kiểm timestamp request; equal duplicate chưa hoàn tất có thể lặp ACK/notification/recovery. Cần parser strict + durable inbox/dedupe dùng SQLite trước side effect, tests malformed/expired/duplicate/reopen.
3. **Sample/checkpoint DB chạy trên main looper.** SQLite FULL/count có thể làm trễ service/heartbeat/UI; flush I/O dùng cùng executor với sample sẽ khiến sample chờ upload. Cần dedicated serial journal executor, initialization/checkpoint restore và ordered callbacks, không chặn GPS bởi upload.
4. **SDK+REST set/PATCH có thể regress latest location/ACK.** updateTime CAS chỉ có ở token/Parent command/backend; Child latest sample và progress/result vẫn unconditional, late older write có thể overwrite newer request/GPS. Cần transaction/CAS guard cùng policy ở hai transport, chỉ confirm terminal của matching request; Parent không cho stale snapshot regress vị trí.
5. **FCM token generation cloud vượt local sau clean install/đổi UID.** Worker chỉ từ chối remote generation lớn; cần nâng local revision từ giá trị remote bằng compare-and-set của token hiện tại, rồi retry không overwrite token đã rotate. Exact new Child UID chỉ được cấp bằng rules review sau cài mới; không suy đoán/auto xóa cloud.
6. **Network callback/probe stale.** onUnavailable chưa kiểm callback identity; probe có một gate dùng chung có thể bỏ probe cellular khi probe Wi-Fi đang chạy; HTTP exception thiếu finally disconnect. Cần request/probe generations, queue bounded/callback identity, VPN cancellation kể cả lúc requesting, cleanup finally.
7. **Parent hiện 7 stage**, chưa phân biệt outbox/local sample/upload. Cần thêm stages có evidence tương ứng; FCM sent luôn chỉ Google acceptance. Request generation bằng wall clock chưa có local monotonic guard; clock lùi có thể tạo command cũ.
8. **Signer bị khóa fingerprint cũ và source rebuild overwrite.** v230 clean signer workflow phải độc lập key cũ, pin fingerprint mới sau tạo cục bộ; chưa có signing input thì dừng gate, không tạo password hay Installable giả.

## Các lớp giữ nguyên và giới hạn

FGS location promote sớm/sticky/stopWithTask=false; one service/callback/listener, auth/network listener generations; watchdog persisted15m (secondary, battery exemption, bounded retries); Google-owned same-ID250m EXIT NEVER_EXPIRE geofence; BOOT_COMPLETED/MY_PACKAGE_REPLACED receiver bounded8s; Cloudflare/FCM independent owner. LOCKED_BOOT_COMPLETED **không bật**: Auth/WorkManager/journal credential-encrypted, không direct-boot safe. Không đổi reminder semantics/notification IDs42/22942/77, 220 tips/clock/dark/shield/animation.

Android12–16 background start/while-in-use restrictions vẫn áp dụng. RecoveryStarter prerequisite+try/catch không chứng minh actual restart; tests phải phân biệt requested/started. Force Stop, permissions revoked, Location OFF, OEM/Doze suppression, quota/disk full/unsaved callback là giới hạn còn lại. Unknown Samsung Sleeping membership giữ unknown; unused-app API báo feature enabled, không chứng minh đang hibernated.

Worker RS256/audience/issuer/UID/expiry, bounded8 attempts/TTL/rate12/min/SQLite alarm, token rotation/invalid-token, conditional diagnostic writes được giữ. Bổ sung hợp lý exp/iat/auth_time ordering và explicit FCM payload binding. Production secrets/rules chưa có; URL public người dùng cung cấp: `https://family-location-wake.huytoan0979928450.workers.dev`. Không fake UID/secrets, không deploy.

## Clean auth identity và dữ liệu cũ

Không đọc/xóa production events/child-01/Auth users. UID sau reinstall chưa biết. Existing rules gắn UID cũ có thể chặn identity mới: báo và review rules với UID thực trước publish. Đề xuất migration không xóa: giữ child-01/events, new token owner UID + monotonic revision lớn hơn remote; revoke old UID quyền client qua exact-UID rules. Không tự thực hiện migration production.

Nguồn Android chính thức: [FGS exemptions/while-in-use](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [foreground/background xin riêng](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime), [background Android11+](https://developer.android.com/develop/sensors-and-location/location/permissions/background). UI app phải tuân thủ system dialogs; không bypass indicator/permissions.
