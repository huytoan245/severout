> HƯỚNG DẪN LỊCH SỬ v2.2.11/v2.3.0. Không dùng bước lấy UID/secrets UID
> cho v2.3.1. URL hiện đã xác nhận; thiết kế mới ở [ZERO_SETUP_PAIRING_V231.md](ZERO_SETUP_PAIRING_V231.md).
> Chưa deploy production v2.3.1; chờ review thiết kế.

# Thiết lập Firebase Spark + Cloudflare Workers Free — từng bước

Ngày 03/10/2026. **Chưa deploy production.** Kiểm tra thật bằng Wrangler 4.147.0 trên máy Windows của phiên Codex này trả về `You are not authenticated`. Không dùng tài khoản preview tạm, không có URL production và chưa xác định UID Máy Cha/Máy Con. Không có private key trong source/APK.

## 1. Tạo tài khoản và giữ Free

1. Mở https://dash.cloudflare.com/sign-up bằng trình duyệt của bạn, tạo tài khoản nếu chưa có và xác nhận email. Không gửi mật khẩu cho Codex.
2. Vào **Workers & Pages**. Chọn Workers **Free**; không bật Workers Paid và không cần mua domain để dùng workers.dev.
3. Trong Firebase Console https://console.firebase.google.com/ chọn đúng **family-location-884e5**, kiểm tra nhãn plan là **Spark**. Không bật billing/Blaze. FCM là sản phẩm no-cost; Firestore vẫn có quota Spark.

## 2. Đăng nhập Wrangler trên đúng máy/môi trường

Mở PowerShell trên chính máy Windows này. Dùng Node/Wrangler đã chuẩn bị trong phiên hiện tại; các biến bên dưới chỉ áp dụng cho cửa sổ PowerShell, không đổi cấu hình Windows toàn cục:

```powershell
Set-Location -LiteralPath 'C:\Users\Admin\Documents\ChatGPT\Xác định Vị trí\source\cloudflare-wake'
$env:PATH = 'C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin;' + $env:PATH
$env:NODE_USE_SYSTEM_CA = '1'
$env:WRANGLER_SEND_METRICS = 'false'
function npm { & node 'C:\Users\Admin\Documents\ChatGPT\Xác định Vị trí\tools\firebase-cli\node_modules\npm\bin\npm-cli.js' @args }
node .\node_modules\wrangler\bin\wrangler.js whoami
node .\node_modules\wrangler\bin\wrangler.js login
node .\node_modules\wrangler\bin\wrangler.js whoami
```

`login` mở trang OAuth Cloudflare; bạn tự đăng nhập/chọn tài khoản/cho phép trong trình duyệt. Lần `whoami` cuối phải hiện đúng account bạn muốn dùng. Không copy OAuth token/config Wrangler vào chat hoặc repo. Nếu chuyển sang máy khác, phải cài dependency bằng `npm ci` trong thư mục cloudflare-wake và đăng nhập lại ở máy đó.

## 3. Tạo Worker Free

Trong Cloudflare Dashboard → Workers & Pages → Create application → Worker, tạo Worker tên **family-location-wake**. Nếu tên đó không khả dụng, chọn tên thực khác rồi sửa trường `name` trong `cloudflare-wake/wrangler.jsonc` cho khớp. Giao diện Cloudflare có thể đổi tên nút; chọn luồng Worker/Hello World, không phải Pages.

Worker Hello World ở bước này chỉ để tạo endpoint/account resource. Nó **chưa gửi FCM**. Chưa deploy source wake, chưa thêm private credential. Kiểm tra account vẫn Workers Free. SQLite Durable Object của source wake sẽ được tạo bằng migration `new_sqlite_classes` ở bước deploy cuối; không tạo namespace KV-backed (không phù hợp Workers Free).

## 4. Lấy URL chính xác

Mở Worker vừa tạo → Overview/Visit, copy **URL workers.dev thực tế** Cloudflare hiển thị. Kiểm tra mở URL thấy Hello World. Không tự ghép tên account, không dùng URL ví dụ làm production. Ghi lại origin HTTPS, không thêm `/v1/wake`, query, fragment hoặc credential vào URL cấu hình APK.

Chỉ URL công khai/account status cần cung cấp cho Codex để build cấu hình. Không gửi token Cloudflare.

## 5. Xác định UID Máy Cha/Máy Con, tuyệt đối không đoán

Source hiện dùng `FirebaseAuth.signInAnonymously()`. UID là dữ liệu tạo khi app chạy; package, child-01, API key và google-services.json **không phải UID**. Danh sách anonymous users trong Firebase Console không tự chứng minh user nào là Máy Cha.

Bản v2.2.11 thêm:

- Parent → **Sức khỏe** → **Firebase UID Máy Cha**: đọc trực tiếp `FirebaseAuth.currentUser.uid` của chính app đang chạy.
- Child token worker gửi **fcmTokenOwnerUid** từ Firebase Auth thực của Child; Parent → Sức khỏe hiển thị trường này sau token upload/read-back thành công.

Để lấy các giá trị này từ app đang cài, cần nâng cấp v2.2.11 bằng **signer cũ đúng fingerprint**. APK candidate unsigned chưa cài/update được. Không uninstall bản hiện tại, không xóa app data, không cài bản ký bằng key mới để “lấy UID”: các thao tác đó có thể tạo anonymous UID khác. Nếu chưa có quy trình ký cũ, **dừng ở đây**; UID hiện vẫn NOT VERIFIED.

Sau khi nâng cấp đúng signer, mở Parent/Child một lần, chờ sync, đọc hai UID trên Parent Health rồi đối chiếu đúng UID trong Firebase Console → Authentication → Users của family-location-884e5. Hai UID phải khác nhau. Ghi đúng hai giá trị; không chọn user theo phỏng đoán từ ngày tạo/lần đăng nhập.

## 6. Chuẩn bị quyền server và Secrets, sau khi URL/UID đã xác nhận

Bạn tự thực hiện trong console; **không gửi private key, access token, service-account JSON hay mật khẩu cho Codex**.

1. Trong Google Cloud/Firebase đúng project, tạo service account riêng cho wake với quyền FCM send (`roles/firebasecloudmessaging.admin`) và Firestore document read/write (`roles/datastore.user`). Không cấp Owner/Editor chỉ để gửi wake. Kiểm tra Firebase Cloud Messaging API HTTP v1 đang enabled. Không bật Blaze; nếu console đòi billing cho thao tác khác, dừng và kiểm tra đúng API/luồng.
2. Tạo credential service account theo chính sách project; private JSON phải có `project_id = family-location-884e5`. Chỉ lưu credential trong **Cloudflare Worker Settings → Variables and Secrets → Secret** tên `GOOGLE_SERVICE_ACCOUNT_JSON`. Bạn tự thêm JSON đầy đủ qua Dashboard. Không dán vào chat/terminal argument, không đặt trong wrangler.jsonc, appsrc, .env của repo hay APK. Nếu console tạo file tải về, giữ ở vị trí riêng bảo mật ngoài workspace rồi tự quản lý/xóa bản tạm sau provisioning theo quy trình của bạn.
3. Thêm `PARENT_UID` và `CHILD_UID` vào Worker Secrets bằng hai UID thực ở bước 5. Không dùng placeholder. Source kiểm tra chỉ Parent UID đúng được gọi wake và chỉ token của Child UID đúng được dùng.
4. Firestore phải chặn các client khác sửa command/token; quy tắc development mở cho mọi anonymous user không đủ an toàn. Dùng `cloudflare-wake/render-rules.mjs` với hai UID thực để tạo **file rules để review**, đối chiếu rules production hiện tại, rồi mới tự publish rules cho child-01/events. Không xóa document/event cũ. Các server writes bằng service account dùng IAM; client dùng Firebase Security Rules.

Trong thư mục cloudflare-wake, nhập **hai UID công khai đã xác nhận**, rồi tạo file review:

```powershell
$taskParentUid = Read-Host 'UID thực của Máy Cha từ Health'
$taskChildUid = Read-Host 'fcmTokenOwnerUid thực của Máy Con'
node .\render-rules.mjs --parent-uid $taskParentUid --child-uid $taskChildUid --output ..\out\firestore-v2211-reviewed.rules
```

Đối chiếu file với rules hiện tại trước khi publish qua Firebase Console → Firestore Database → Rules. Đây là thay đổi quyền truy cập, không xóa dữ liệu. Không publish nếu UID chưa xác nhận hoặc bạn còn ứng dụng khác dùng cùng project cần các quyền khác. Các APK cũ chưa có token-generation/owner fields cần nâng cấp đúng signer trước.

## 7. Test và deploy source wake, chỉ sau các bước trên

Các lệnh kiểm tra dưới đây không deploy:

```powershell
npm test
npm run check
npm run dry-run
```

Sau khi bạn xác nhận account Free, URL đúng, UID đúng, secrets đủ và rules phù hợp, lệnh deploy source:

```powershell
node .\node_modules\wrangler\bin\wrangler.js deploy
node .\node_modules\wrangler\bin\wrangler.js secret list
```

`secret list` chỉ liệt kê tên, không phải giá trị. Đối chiếu URL deploy với URL thật ở bước 4; gọi `<URL THỰC>/health` phải hiện version 2.2.11 và configured true. Điều này chỉ chứng minh cấu hình được khai báo, **chưa phải FCM end-to-end PASS**. Không dùng `firebase deploy --only functions`; firebase-wake/ cũ chỉ giữ làm lịch sử v2210, không còn là deployment path của v2211.

## 8. Cấu hình APK và nghiệm thu

Build Parent với public origin thực, ví dụ sử dụng biến môi trường **giá trị do bạn đã xác nhận**, không phải URL đoán:

```powershell
$env:FAMILY_LOCATION_WAKE_WORKER_URL = Read-Host 'Dán public HTTPS origin thực từ Cloudflare'
```

Dựng lại source bằng scripts/reconstruct.py, build bằng Gradle/JDK17/SDK36 rồi ký **cùng signer cũ** theo scripts/sign-release.ps1. Child không chứa Worker credential. Parent chỉ chứa public URL; mỗi request dùng Firebase ID token của runtime.

Trên Parent bấm cập nhật và đối chiếu cùng requestId: durable command → wakeBackendFor → wakeDispatchFor/sent → refreshReceivedFor → refreshServiceFor → refreshLocatingFor → refreshCompletedFor/locationTime. FCM `sent` chỉ là Google chấp nhận gửi, không phải Child nhận hay GPS thành công. Parent có timeout 45 giây; command TTL 15 phút. Lặp lại offline, reclaim thường, token rotation và NORMAL priority; chạy Samsung 24–48 giờ theo test matrix.

## Giới hạn chi phí và độ tin cậy

Workers Free/SQLite Durable Objects có quota; vượt quota sẽ lỗi, không phải bảo đảm luôn có wake. Worker giới hạn 12 request/phút cho một Child, tối đa 8 lần kiểm tra/retry cho một command và không dùng cron/polling vĩnh viễn. Firestore Spark có quota riêng; lỗi/quota giữ journal local và Parent timeout rõ. Doze/OEM/Force Stop có giới hạn hệ điều hành. Source hiện chưa thay đổi plan Firebase hoặc account Cloudflare.

Nguồn chính thức: [Cloudflare Free / SQLite Durable Objects](https://developers.cloudflare.com/durable-objects/platform/pricing/), [Wrangler OAuth](https://developers.cloudflare.com/workers/wrangler/commands/general/#login), [Cloudflare Secrets](https://developers.cloudflare.com/workers/configuration/secrets/), [Firebase ID tokens](https://firebase.google.com/docs/auth/admin/verify-id-tokens), [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api), [Firebase pricing](https://firebase.google.com/pricing).
