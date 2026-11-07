# Kết nối Máy Cha để chẩn đoán

Không kết nối được USB không ngăn việc chuyển APK/báo cáo mã hóa bằng file. ADB chỉ giúp kiểm tra bản cài; Firebase Auth/Keystore của release app cần được kiểm tra bên trong APK Recovery, không trích credential bằng shell.

## USB

Dùng cáp đã biết chắc truyền dữ liệu, mở khóa điện thoại, cắm trực tiếp máy tính. Samsung: Cài đặt → Thông tin điện thoại → Thông tin phần mềm → chạm Số hiệu bản tạo7lần; vào Tùy chọn nhà phát triển → Gỡ lỗi USB. Chấp nhận hộp thoại RSA khi nối đúng máy tính của bạn. Chọn Truyền tệp/Android Auto nếu có thông báo USB.

Chỉ sạc/no USB notification: thử cáp dữ liệu khác/cổng khác trước; chưa đủ kết luận cổng điện thoại hỏng. Nếu Windows thấy điện thoại/MTP nhưng không có ADB, kiểm tra Device Manager và [driver Samsung chính thức](https://developer.samsung.com/android-usb-driver). Không dùng driver lạ, không factory reset. Nếu cài đặt bị khóa bởi policy, báo lại thông báo thay vì tự tắt các bảo vệ.

## Wi-Fi — Android11 trở lên

1. Máy tính và Máy Cha cùng Wi-Fi. Trên điện thoại bật Tùy chọn nhà phát triển → Gỡ lỗi không dây → Ghép đôi thiết bị bằng mã ghép đôi; giữ màn hình mở.
2. Chạy trong PowerShell:

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Users\Admin\Documents\ChatGPT\Xác định Vị trí\source\scripts\connect-parent-wireless.ps1'
```

3. Nhập IP:cổng của **hộp thoại ghép đôi**, sau đó nhập mã6số trực tiếp tại prompt adb. Không gửi mã vào chat.
4. Quay về màn hình chính Gỡ lỗi không dây; nhập IP:cổng **kết nối**, thường khác cổng ghép đôi. Script chỉ pair/connect/get-state, không cài app hoặc sửa dữ liệu.
5. Nếu không pair/connect: kiểm tra đúng cổng còn hiệu lực, giữ màn hình mở, cùng Wi-Fi, tránh mạng khách tách thiết bị. Không cần mở cổng router hay tắt firewall toàn máy. Báo đúng thông báo lỗi, không gửi mã ghép đôi.

[Hướng dẫn Android chính thức](https://developer.android.com/studio/run/device#wireless). Sau chẩn đoán có thể tắt Gỡ lỗi không dây để ngắt kết nối.
