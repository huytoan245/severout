from pathlib import Path
import json
ROOT = Path('appsrc')

# 220 online-safety reminders are generated from 55 concrete scam scenarios.
# Four phrasings per scenario keep the messages short while preserving the
# existing shuffle-bag/no-repeat behavior.
SCENARIOS = [
    ("người hỏi mã OTP", "Không cung cấp OTP cho bất kỳ ai, kể cả người tự xưng là nhân viên ngân hàng."),
    ("người hỏi mật khẩu hoặc mã PIN", "Không chia sẻ mật khẩu, mã PIN hay mã mở khóa điện thoại."),
    ("người xin số CVV của thẻ", "Không cung cấp CVV hoặc ảnh đầy đủ hai mặt thẻ ngân hàng."),
    ("link đăng nhập gửi qua SMS", "Tự mở ứng dụng hoặc website chính thức thay vì bấm link lạ."),
    ("link lạ trên Zalo hoặc Messenger", "Kiểm tra người gửi bằng một kênh liên lạc khác trước khi mở."),
    ("mã QR không rõ nguồn gốc", "Đọc kỹ màn hình trước khi quét hoặc xác nhận giao dịch QR."),
    ("file APK gửi ngoài cửa hàng ứng dụng", "Không cài ứng dụng không rõ nguồn gốc."),
    ("ứng dụng lạ xin quyền Trợ năng", "Không cấp quyền Trợ năng nếu bạn không hiểu rõ lý do."),
    ("ứng dụng lạ xin quyền đọc SMS", "Chỉ cấp quyền thực sự cần thiết cho ứng dụng đáng tin cậy."),
    ("người yêu cầu chia sẻ màn hình", "Không chia sẻ màn hình khi đang mở ngân hàng hoặc ví điện tử."),
    ("người yêu cầu cài AnyDesk hoặc TeamViewer", "Không cho người lạ điều khiển điện thoại từ xa."),
    ("cuộc gọi tự xưng công an", "Công an không yêu cầu chuyển tiền vào tài khoản để xác minh."),
    ("cuộc gọi tự xưng tòa án hoặc viện kiểm sát", "Ngắt cuộc gọi và tự liên hệ cơ quan qua số chính thức."),
    ("người nói phải chuyển tiền vào tài khoản an toàn", "Ngân hàng không yêu cầu chuyển toàn bộ tiền sang một tài khoản khác để bảo vệ."),
    ("cuộc gọi dọa tài khoản ngân hàng bị khóa", "Tự mở ứng dụng ngân hàng hoặc gọi tổng đài chính thức để kiểm tra."),
    ("người biết họ tên hoặc số CCCD của bạn", "Biết thông tin cá nhân không chứng minh người gọi là cơ quan thật."),
    ("người quen nhắn vay tiền gấp", "Gọi trực tiếp cho người đó trước khi chuyển tiền."),
    ("người thân báo cấp cứu và cần tiền", "Xác minh bằng số điện thoại quen thuộc hoặc hỏi một người thân khác."),
    ("giọng nói quen thuộc nhưng yêu cầu chuyển tiền", "Giọng nói có thể bị giả bằng AI; hãy dùng câu hỏi riêng để xác minh."),
    ("video khuôn mặt quen thuộc yêu cầu tiền", "Hình ảnh cũng có thể bị giả; hãy kiểm tra qua kênh khác."),
    ("tài khoản bạn bè gửi link bình chọn", "Hỏi lại người bạn trước khi đăng nhập hoặc cung cấp mã xác thực."),
    ("người nhờ nhận hộ rồi chuyển tiếp tiền", "Không dùng tài khoản của bạn để trung chuyển tiền không rõ nguồn gốc."),
    ("đề nghị thuê tài khoản ngân hàng hoặc SIM", "Không cho thuê hoặc cho mượn tài khoản tài chính và SIM."),
    ("shipper hỏi mã OTP ngân hàng", "Shipper không cần OTP ngân hàng để giao hàng."),
    ("shipper gửi QR để xác nhận đơn", "Kiểm tra đơn trong ứng dụng mua sắm chính thức."),
    ("người mua gửi link xác nhận nhận tiền", "Nhận tiền không yêu cầu bạn đăng nhập qua link do người mua gửi."),
    ("ảnh chụp biên lai chuyển khoản", "Kiểm tra số dư thực tế trong ứng dụng ngân hàng trước khi giao hàng."),
    ("shop yêu cầu thanh toán ngoài sàn", "Ưu tiên kênh thanh toán chính thức để còn cơ chế bảo vệ giao dịch."),
    ("món hàng rẻ bất thường", "Giá quá tốt có thể là mồi nhử; hãy kiểm tra uy tín người bán."),
    ("việc nhẹ lương cao nhưng phải nạp tiền", "Công việc thật không yêu cầu nạp tiền để được nhận lương."),
    ("nhiệm vụ online kiếm hoa hồng", "Không nạp thêm tiền để mở khóa nhiệm vụ hoặc rút tiền."),
    ("cộng tác viên chốt đơn phải ứng tiền", "Dừng lại nếu công việc bắt bạn ứng tiền trước."),
    ("nhà tuyển dụng chỉ nói chuyện qua Telegram", "Xác minh công ty độc lập trước khi gửi giấy tờ hoặc tiền."),
    ("chuyên gia hứa lấy lại tiền bị lừa", "Không đóng phí trước cho người hứa thu hồi tiền."),
    ("đầu tư cam kết lợi nhuận cao không rủi ro", "Không có đầu tư hợp pháp nào bảo đảm lợi nhuận cao tuyệt đối."),
    ("sàn đầu tư chỉ hiển thị lợi nhuận trong app lạ", "Số dư trên màn hình giả không chứng minh tiền thực sự tồn tại."),
    ("sàn yêu cầu đóng thêm thuế mới cho rút tiền", "Dừng chuyển tiền và xác minh nền tảng từ nguồn độc lập."),
    ("nhóm kéo lệnh yêu cầu nạp tiền", "Không đầu tư chỉ vì người trong nhóm khoe lợi nhuận."),
    ("người hỏi seed phrase ví tiền số", "Không bao giờ chia sẻ seed phrase hoặc khóa riêng của ví."),
    ("quảng cáo đầu tư dùng người nổi tiếng", "Video và giọng nói có thể bị giả bằng AI; hãy tự kiểm tra nguồn."),
    ("thông báo trúng thưởng bất ngờ", "Không đóng phí hoặc cung cấp OTP để nhận phần thưởng không rõ nguồn gốc."),
    ("quà miễn phí nhưng phải trả phí trước", "Không chuyển tiền chỉ để mở khóa một món quà."),
    ("cuộc gọi báo hoàn tiền", "Nhận tiền không cần cung cấp mật khẩu, OTP hoặc CVV."),
    ("QR được nói là để nhận tiền", "Kiểm tra xem ứng dụng đang hiển thị nhận tiền hay thanh toán trước khi xác nhận."),
    ("người quen qua mạng gửi quà quốc tế", "Không chuyển phí hải quan hoặc vận chuyển vào tài khoản cá nhân."),
    ("người yêu qua mạng hỏi vay tiền", "Xác minh danh tính rõ ràng và trao đổi với người thân trước khi chuyển."),
    ("người lạ xin ảnh nhạy cảm", "Không gửi ảnh có thể bị dùng để tống tiền hoặc đe dọa."),
    ("người dọa phát tán ảnh để đòi tiền", "Lưu bằng chứng và tìm người tin cậy hoặc cơ quan hỗ trợ; đừng tiếp tục trả tiền."),
    ("người mới quen nhờ đầu tư vào sàn họ giới thiệu", "Không gửi tiền chỉ dựa trên lòng tin trong một mối quan hệ online."),
    ("người liên tục giục chuyển tiền ngay", "Sự khẩn cấp là thủ thuật phổ biến; hãy dừng lại để kiểm tra."),
    ("yêu cầu giữ bí mật với gia đình", "Một yêu cầu tài chính phải giữ bí mật là dấu hiệu nguy hiểm."),
    ("tài khoản có nhiều người theo dõi hoặc dấu tích", "Uy tín trên mạng có thể bị giả; hãy kiểm tra thông tin độc lập."),
    ("ảnh hóa đơn hoặc giấy tờ gửi qua chat", "Ảnh chụp có thể bị chỉnh sửa; đừng dùng nó làm bằng chứng duy nhất."),
    ("người gọi giữ bạn trên điện thoại lúc chuyển tiền", "Ngắt cuộc gọi rồi tự kiểm tra thông tin trước khi giao dịch."),
    ("tình huống khiến bạn thấy sợ hoặc quá hấp dẫn", "Khi không chắc chắn, đừng chuyển tiền; hãy hỏi người thân trước."),
]
if len(SCENARIOS) != 55:
    raise SystemExit(f'expected 55 scam scenarios, got {len(SCENARIOS)}')

tips = []
for scenario, advice in SCENARIOS:
    tips.extend([
        f"Cảnh giác với {scenario}. {advice}",
        f"Nếu gặp {scenario}, hãy dừng lại kiểm tra. {advice}",
        f"Đừng vội tin {scenario}. {advice}",
        f"Gặp {scenario}? {advice}",
    ])
if len(tips) != 220 or len(set(tips)) != 220:
    raise SystemExit('online safety tips must be 220 unique strings')
items = ',\n'.join('        ' + json.dumps(t, ensure_ascii=False) for t in tips)
wisdom = f'''package com.family.child

import android.content.Context

/** Online-safety reminder shuffle bag. */
object WisdomStore {{
    private const val PREFS = "online_safety_rotation_v3"
    private const val KEY_ORDER = "order"
    private const val KEY_POS = "position"
    private const val KEY_LAST = "last_index"
    private val lock = Any()

    fun next(context: Context): String = synchronized(lock) {{
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var order = prefs.getString(KEY_ORDER, null)?.split(',')?.mapNotNull {{ it.toIntOrNull() }}?.filter {{ it in QUOTES.indices }} ?: emptyList()
        var pos = prefs.getInt(KEY_POS, 0)
        val last = prefs.getInt(KEY_LAST, -1)
        if (order.size != QUOTES.size || order.toSet().size != QUOTES.size || pos !in 0 until QUOTES.size) {{ order = newOrder(last); pos = 0 }}
        val index = order[pos]
        val nextPos = pos + 1
        val editor = prefs.edit().putInt(KEY_LAST, index)
        if (nextPos >= QUOTES.size) editor.remove(KEY_ORDER).putInt(KEY_POS, QUOTES.size)
        else editor.putString(KEY_ORDER, order.joinToString(",")).putInt(KEY_POS, nextPos)
        editor.apply()
        QUOTES[index]
    }}

    fun count(): Int = QUOTES.size

    private fun newOrder(last: Int): List<Int> {{
        val order = QUOTES.indices.shuffled().toMutableList()
        if (order.size > 1 && order.first() == last) {{
            val swap = 1 + (System.nanoTime().toInt().and(Int.MAX_VALUE) % (order.size - 1))
            val t = order[0]; order[0] = order[swap]; order[swap] = t
        }}
        return order
    }}

    private val QUOTES = listOf(
{{items}}
    )
}}
'''.replace('{items}', items)
(ROOT / 'child-app/src/main/java/com/family/child/WisdomStore.kt').write_text(wisdom, encoding='utf-8')
