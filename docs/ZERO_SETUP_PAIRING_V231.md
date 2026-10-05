# Family Location v2.3.1: thiết kế để review trước production

Trạng thái: implementation/test candidate; CHƯA deploy. Baseline là commit
`0ced7ae2f096773eef6a019bb63d709c7877b422` của v2.3.0. Hai APK v2.3.0 đã
được kiểm tra lại độc lập: package, code34, certificate, zipalign và ZIP checksum PASS.
Signer giữ nguyên `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`.

## Nguyên nhân kiến trúc cũ cần UID thủ công

Anonymous Auth tạo runtime UID, nhưng v2.3.0 chưa có enrollment authority.
Worker so JWT với `PARENT_UID`; token owner so với `CHILD_UID`; renderer Rules
nhúng hai UID. Vì vậy một bản cài sạch tạo identity khác nhưng không có đường
được backend công nhận tự động. Firebase UID không phải role và client tự
khai role trong dữ liệu cũng không đủ để được tin cậy.

## Thiết kế mới

Một family cố định `family-01`, một Parent và một Child logical id `child-01`.
`families/family-01` do service account server tạo/cập nhật qua Firestore REST:
`parentUid`, `parentKey`, `parentRegisteredAt`, `childUid`, `childKey`,
`childRegisteredAt`, `childDeviceId`, `epoch`, `locked`. Không client nào
được ghi/xóa mapping. Không có UID cố định trong APK, Rules hoặc Worker secrets.

Mỗi app tự anonymous sign-in, tự tạo một khóa EC P-256 trong Android Keystore,
lấy server nonce rồi ký payload đăng ký. Private key thiết bị không được export.
Key này độc lập với release JKS; không tạo hay đổi APK signer. Không cần Play
Store, QR, pairing code, backend password, copy UID hay token.

FamilyRegistry dùng SQLite Durable Object cho nonce/rate/dedupe; Firestore
`currentDocument.exists=false` hoặc `updateTime` CAS quyết định ai thắng slot.
Đăng ký thành công đầu tiên thắng. UID khác/key khác không thể thay chủ slot,
một UID không thể nhận cả hai role. Khi đủ hai UID thì locked. Duplicate cùng
UID/key được xác nhận idempotently; không mở lại slot khi offline hoặc timeout.

Các endpoint ở cùng origin hiện tại:

| Endpoint | Quyền và hành vi |
|---|---|
| `POST /v1/challenge` | Firebase JWT hợp lệ, role/purpose/ID cố định; nonce 120 giây |
| `POST /v1/register/parent` | Proof-of-possession; claim Parent slot qua CAS |
| `POST /v1/register/child` | Proof-of-possession; claim Child slot qua CAS |
| `GET /v1/family` | Chỉ UID đã đăng ký; trả boolean pairing, role và epoch |
| `POST /v1/token` | Chỉ UID + key Child; token revision monotonic |
| `POST /v1/wake` | Chỉ UID + key Parent; command cố định child-01 |
| `GET /v1/diagnostics` | Chỉ UID đã đăng ký; không trả token/private material |
| `GET /health` | Version/configured boolean; không trả credential |

JWT kiểm RS256, Google key signature, project, issuer, expiry và subject;
UID caller được lấy từ JWT, không tin UID trong body. Proof ký chuỗi UTF-8:
`FL231\nfamily-01\nchild-01\nrole\nuid\nnonce\npurpose\nbase64url(SHA256(payload))`.
Android chuyển chữ ký DER thành r||s 64 bytes cho WebCrypto. Nonce ràng buộc
UID/role/purpose/epoch. Server persist hash proof trước side effect; retry chỉ
được dùng đúng proof đó. Proof hết hạn hoặc có epoch cũ không được replay.
App persist proof bằng SharedPreferences.commit trước POST và retry background
qua WorkManager với exponential backoff, giữ proof tối đa 90 giây.

Worker giới hạn body 8192 bytes, payload 3072, token 2048; từ chối field lạ,
family/device lạ, role overwrite, signature sai, nonce reuse khác payload.
Registry có rate limit persist 32 request/UID/phút và 96 request/family/phút;
wake giữ giới hạn 12/phút, TTL 15 phút, tối đa tám lần thử. Alarm dọn nonce/rate
không bị hoãn vô hạn bởi traffic liên tục. Các giới hạn cũng có thể trì hoãn
người dùng hợp lệ khi có spam; chúng không phải bảo đảm chống DDoS toàn diện.

FCM token nằm tại device document, chỉ server được ghi qua token endpoint;
Rules chặn token writes trực tiếp của mọi client, kể cả Child. Child ký rotation
bằng key đã đăng ký. Commit token cùng conditional epoch write trên family
để membership và token CAS atomic. Không overwrite revision cũ/equal-but-different.
Child tự rebase revision theo server khi dữ liệu v2.3.0 có revision lớn hơn.
Firestore device state/journey events hiện tại được giữ, không xóa collection.

WakeCoordinator kiểm lại current mapping mỗi attempt và ngay trước FCM send.
Request durable gắn Parent UID + epoch. Reset identity làm old pending wake
fail closed. Chỉ dùng token có owner bằng current registered Child UID.
Caller không chọn token, Child UID hay logical device khác. FCM acceptance
không được hiển thị thành GPS success; ACK/journal/checkpoint/idempotency cũ giữ.

Parent đăng ký background và realtime-listen family. Trạng thái chờ là
“Đang chờ Máy Con kết nối”; sau Child registration chuyển sang
“Máy Con · Đang kết nối” rồi áp dụng Health/freshness thật. UI Child và các câu
đã khóa không thay đổi. Enrollment/FCM lỗi không chặn UI hoặc journal GPS.

## Trust và giới hạn bootstrap

Android Keystore tăng trust về việc nắm private key của bản cài đã đăng ký.
Nó KHÔNG tự chứng minh người đăng ký đầu tiên là chủ gia đình, cũng không tự
chứng minh APK được ký bởi release certificate. Public release SHA trong APK
hoặc tự khai certificate là thông tin có thể bị sao chép, không phải credential.
Hardware backing tùy thiết bị; không tuyên bố mọi key đều hardware-backed.

Không bật attestation bắt buộc: cần kiểm chain/root/revocation và certificate
extensions server-side, hỗ trợ thiết bị sideload không đồng nhất. Không dùng
Play Integrity bắt buộc hay giả attestation. Lựa chọn triển khai hiện tại là
device-key proof + first-wins đúng yêu cầu tối thiểu, với rủi ro pre-enrollment
squatting được nêu rõ. Một attacker có thể anonymous sign-in vào project công
khai, tạo key của họ và chiếm slot còn trống trước chủ nhà. Sau khi slot có chủ
thì không tự takeover được. Muốn bảo đảm chủ nhà là người đầu tiên cần thêm
trust anchor (attestation/pinned pre-provisioned install keys/pairing ceremony),
và điều này thay đổi mô hình zero-setup hiện tại. Không âm thầm thêm master secret.

Phải chuẩn bị và giám sát cửa sổ enrollment đầu tiên trước production, cài hai
app ngay khi mở, rồi tắt `ENROLLMENT_ENABLED` sau khi paired. Đây là thao tác
operator ban đầu, không phải UID/config sau mỗi lần mở hoặc update APK. Khi đã
paired, logic vẫn khóa hai slot kể cả nếu flag chưa tắt. URL giữ nguyên
`https://family-location-wake.huytoan0979928450.workers.dev`.

Firestore client state access vẫn dựa trên Firebase JWT UID + server mapping;
Rules không thể tự verify chữ ký Keystore. Device proof bảo vệ enrollment,
token và wake; nó không biến mọi SDK Firestore location/command write thành
attested hardware operation. Rooted device/app compromise vẫn là rủi ro.

## Reinstall và recovery

Update APK cùng signer/package, reboot hoặc process restart giữ app data,
Firebase identity và Keystore entry; retry dùng cùng slot. Token rotation không
làm mất pairing. Cả hai manifest đã tắt Android backup; không dùng backup để
tự dựng lại identity/private key. Clear app data có cùng rủi ro như uninstall.

Full uninstall mất anonymous identity và private key. Bản cài mới KHÔNG được
tự chiếm lại slot cũ, kể cả nếu người dùng nói đó là cùng điện thoại. Chỉ UID
hoặc tên thiết bị/certificate public không đủ bằng chứng. Backend trả slot occupied.

Recovery là luồng riêng cho operator có quyền Google IAM admin trên máy:

1. Đóng enrollment, dừng/đợi request đang chạy; xác nhận chính chủ yêu cầu recovery.
2. Đọc snapshot mapping + updateTime và device revision bằng admin identity.
   Không yêu cầu end user lấy/copy UID, token hoặc gửi credential vào chat.
3. Lưu audit mapping cũ (không lưu private key/token). Tạo kế hoạch CAS bằng
   `cloudflare-wake/recovery-plan.mjs`, không có network hoặc apply trong script.
4. Review kế hoạch: increment epoch; chỉ clear slot bị mất; giữ slot còn lại. Thêm hash UID bị thu hồi vào
   server-controlled retiredUidHashes để identity cũ không claim lại slot trong
   cửa sổ maintenance. Mỗi recovery vẫn cần giám sát first-wins cho UID mới.
   Nếu Child bị mất, atomically vô hiệu owner/token cũ và tăng token revision.
   Giữ nguyên location state và events. CAS thất bại thì đọc lại và review lại,
   không retry overwrite mù.
5. Sau phê duyệt riêng, operator gửi commit bằng admin IAM, mở enrollment có
   giám sát cho slot đó, app mới tự claim; paired thì đóng enrollment.

Không có reset public endpoint, timeout takeover, secret recovery trong APK
hay thao tác xóa cloud đã tự chạy. Recovery mất credential không thể vừa
zero-touch vừa bảo đảm identity trong mô hình này. Nếu cả hai bản cài mất
credential, operator phải recovery cả hai slot. Đây là ngoại lệ maintenance,
không phải bước cài sạch lần đầu hay sử dụng hàng ngày.

## Migration và deploy boundary

v2.3.1/code35 giữ packages, family/device IDs và release signer. Mapping mới
ban đầu không có UID: hai app update tự enroll với identity hiện tại. Không
tự import UID từ secrets v2.3.0, không sửa/xóa cloud để giả test thành công.
Renderer Rules chỉ nhận `--output`, không nhận UID. PARENT_UID/CHILD_UID không
còn được runtime đọc; server chỉ cần fixed project + Google service-account
credential và DO bindings. Firebase Spark + Workers Free + FCM HTTP v1 là
mục tiêu giữ nguyên; không deploy Firebase Functions/Blaze. Cần kiểm quota
thực tế trước go-live, không tuyên bố unlimited miễn phí.

Phải triển khai backend mới và Rules mới theo kế hoạch migration được duyệt,
rồi cho app v2.3.1 enroll. Trong khoảng Rules/mapping thay đổi, v2.3.0 có thể
bị permission denied; không cập nhật Rules production độc lập rồi coi old app
đã được chuyển. Anonymous Auth phải được bật và không tự cleanup identity
đang sử dụng. Service account chỉ lưu Cloudflare secret, không APK/Git.

## Bằng chứng và phần chưa kiểm tra

Xem `VALIDATION_V231.md` để biết lệnh và kết quả thực chạy. Các A–N có model
tests với crypto thật và fault injection; Android proof/journal tests kiểm
serialization, chữ ký và cold-client retry. Test workerd dùng SQLite thực,
Google network được intercept; Rules dùng Firestore emulator thật. Đây không
phải test A–N trên hai điện thoại vật lý. APK update/uninstall/reboot/Android
Keystore persistence và Android WorkManager process death cần kiểm trên máy.

Chưa kiểm: production FCM, end-to-end Parent refresh/Child ACK/GPS production,
Samsung endurance, app foreground/background restrictions, giao diện trạng
thái realtime trên máy thật, Android permissions flow trên máy thật. Bản mới
chưa signed cho đến khi người dùng nhập password trực tiếp trong PowerShell.

STOP trước production để review; không merge master, không install/uninstall,
không tạo/xóa/overwrite release keystore.

Nguồn thiết kế: [Android Keystore](https://developer.android.com/privacy-and-security/keystore),
[Android attestation](https://developer.android.com/privacy-and-security/security-key-attestation),
[Firestore REST Write/preconditions](https://docs.cloud.google.com/firestore/docs/reference/rest/v1/Write),
[Firestore Rules authorization](https://firebase.google.com/docs/rules/basics).
