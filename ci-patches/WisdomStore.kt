package com.family.child

import android.content.Context

/**
 * Local quote rotation. Every quote is shown once before the next shuffle cycle.
 * The current shuffled order and position survive app restarts.
 */
object WisdomStore {
    private const val PREFS = "wisdom_rotation_v2"
    private const val KEY_ORDER = "order"
    private const val KEY_POS = "position"
    private const val KEY_LAST = "last_index"
    private val lock = Any()

    fun next(context: Context): String = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var order = prefs.getString(KEY_ORDER, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in QUOTES.indices }
            ?: emptyList()
        var pos = prefs.getInt(KEY_POS, 0)
        val last = prefs.getInt(KEY_LAST, -1)

        if (order.size != QUOTES.size || order.toSet().size != QUOTES.size || pos !in 0 until QUOTES.size) {
            order = newOrder(last)
            pos = 0
        }

        val index = order[pos]
        val nextPos = pos + 1
        val editor = prefs.edit().putInt(KEY_LAST, index)
        if (nextPos >= QUOTES.size) {
            editor.remove(KEY_ORDER).putInt(KEY_POS, QUOTES.size)
        } else {
            editor.putString(KEY_ORDER, order.joinToString(",")).putInt(KEY_POS, nextPos)
        }
        editor.apply()
        QUOTES[index]
    }

    fun count(): Int = QUOTES.size

    private fun newOrder(last: Int): List<Int> {
        val order = QUOTES.indices.shuffled().toMutableList()
        if (order.size > 1 && order.first() == last) {
            val swap = 1 + (System.nanoTime().toInt().and(Int.MAX_VALUE) % (order.size - 1))
            val t = order[0]
            order[0] = order[swap]
            order[swap] = t
        }
        return order
    }

    private val QUOTES = listOf(
        "Mỗi ngày đều là một cơ hội để bắt đầu tốt hơn.",
        "Hành trình dài bắt đầu từ một bước nhỏ.",
        "Kiên trì hôm nay tạo nên kết quả ngày mai.",
        "Bình tĩnh giúp ta nhìn mọi việc rõ hơn.",
        "Biết trân trọng hiện tại là một dạng hạnh phúc.",
        "Đi chậm vẫn tốt hơn là không tiến về phía trước.",
        "Một lời tử tế có thể làm ngày của ai đó tốt đẹp hơn.",
        "Những cố gắng nhỏ lặp lại mỗi ngày sẽ tạo nên thay đổi lớn.",
        "Thời gian quý giá nhất là thời gian dành cho điều có ý nghĩa.",
        "Hãy học một điều mới, dù chỉ một chút mỗi ngày.",
        "Khi khó khăn đến, hãy tập trung vào điều mình có thể làm.",
        "Sự chân thành luôn có giá trị lâu dài.",
        "Đừng vội so sánh hành trình của mình với người khác.",
        "Một tâm trí bình an giúp ta mạnh mẽ hơn.",
        "Điều tốt đẹp thường bắt đầu từ những thói quen nhỏ.",
        "Hôm nay làm tốt hơn hôm qua đã là một tiến bộ.",
        "Biết lắng nghe cũng là một cách thể hiện sự quan tâm.",
        "Giữ lời hứa là cách bền vững để xây dựng niềm tin.",
        "Sống có mục tiêu giúp mỗi ngày trở nên đáng giá.",
        "Không cần hoàn hảo, chỉ cần tiếp tục tiến bộ.",
        "Một quyết định bình tĩnh thường tốt hơn một phản ứng vội vàng.",
        "Hãy dành thời gian cho gia đình và những người mình yêu quý.",
        "Sự tử tế không làm ta mất gì nhưng có thể mang lại rất nhiều.",
        "Khó khăn là nơi ta nhận ra sức mạnh của chính mình.",
        "Hạnh phúc thường nằm trong những điều giản dị.",
        "Hãy nghỉ ngơi khi cần, nhưng đừng từ bỏ điều quan trọng.",
        "Biết ơn những điều đang có giúp lòng mình nhẹ hơn.",
        "Lắng nghe bản thân cũng quan trọng như lắng nghe người khác.",
        "Mỗi sai lầm đều có thể trở thành một bài học.",
        "Thành công bền vững được tạo nên từ sự đều đặn.",
        "Một ngày tốt đẹp có thể bắt đầu bằng một suy nghĩ tích cực.",
        "Hãy chọn điều đúng, ngay cả khi điều đó khó hơn.",
        "Thời gian không quay lại, hãy dùng nó cho điều đáng quý.",
        "Sự tự tin lớn lên từ những việc mình kiên trì hoàn thành.",
        "Đừng ngại hỏi khi chưa biết; học hỏi là một sức mạnh.",
        "Gia đình là nơi ta luôn có thể tìm thấy sự quan tâm.",
        "Một nụ cười chân thành có thể làm khoảng cách trở nên gần hơn.",
        "Hãy để hành động tốt nói thay cho những lời hứa lớn.",
        "Sự kiên nhẫn hôm nay có thể tránh một quyết định đáng tiếc.",
        "Mỗi buổi sáng là một trang mới để viết điều tốt đẹp.",
        "Người mạnh mẽ không phải người không mệt, mà là người biết đứng dậy.",
        "Điều đáng quý không phải đi nhanh, mà là đi đúng hướng.",
        "Hãy giữ cho mình một khoảng lặng giữa những ngày bận rộn.",
        "Một việc nhỏ làm đến nơi đến chốn vẫn đáng quý hơn nhiều dự định.",
        "Sự chăm chỉ âm thầm thường tạo nên kết quả rõ ràng.",
        "Khi lòng nhẹ nhàng, con đường phía trước cũng dễ nhìn hơn.",
        "Đừng để một ngày khó khăn khiến bạn quên những ngày tốt đẹp.",
        "Chậm một chút để hiểu đúng vẫn hơn nhanh mà vội vàng.",
        "Mỗi người đều có nhịp đi riêng; hãy tôn trọng nhịp của mình.",
        "Thói quen tốt là món quà ta gửi cho chính mình trong tương lai.",
        "Hãy giữ sự tò mò, vì nó mở ra những cánh cửa mới.",
        "Đừng ngại bắt đầu lại khi bạn đã hiểu mình cần thay đổi điều gì.",
        "Một cuộc trò chuyện chân thành có thể tháo gỡ nhiều hiểu lầm.",
        "Sự bình yên bắt đầu từ cách ta nhìn nhận những điều không thể đổi.",
        "Hãy làm điều cần làm trước khi chờ cảm hứng xuất hiện.",
        "Ngày mai sẽ dễ hơn nếu hôm nay ta chuẩn bị tốt.",
        "Thành quả lớn thường được xây từ những ngày rất bình thường.",
        "Đừng quên tự ghi nhận những bước tiến nhỏ của mình.",
        "Điều tốt đẹp cần thời gian, giống như cây cần ngày tháng để lớn lên.",
        "Khi chưa biết đường, hãy bắt đầu bằng bước chắc chắn nhất.",
        "Một trái tim biết cảm thông sẽ nhìn thấy nhiều điều người khác bỏ qua.",
        "Đừng để sự nóng vội lấy mất sự sáng suốt.",
        "Hãy nói lời cảm ơn khi còn có thể nói.",
        "Sự tôn trọng luôn là nền tảng của một mối quan hệ tốt đẹp.",
        "Bớt một lời trách móc, thêm một lần lắng nghe.",
        "Không phải ngày nào cũng dễ, nhưng ngày nào cũng có điều để học.",
        "Hãy làm tốt phần việc của mình rồi để thời gian trả lời.",
        "Sự tử tế với chính mình cũng quan trọng như tử tế với người khác.",
        "Một kế hoạch đơn giản được thực hiện tốt hơn một kế hoạch hoàn hảo bị bỏ dở.",
        "Giữ được bình tĩnh là giữ được một nửa cách giải quyết vấn đề.",
        "Hãy tập trung vào bước tiếp theo thay vì lo cả chặng đường.",
        "Những ngày bình thường cũng xứng đáng được trân trọng.",
        "Đừng quên rằng nghỉ ngơi cũng là một phần của hành trình.",
        "Một người biết nhận lỗi luôn có cơ hội để trưởng thành hơn.",
        "Hãy dành sự chú ý cho điều thực sự quan trọng với bạn.",
        "Lời nói nhẹ nhàng thường đi xa hơn lời nói lớn tiếng.",
        "Kiến thức tăng lên khi ta chịu khó đặt câu hỏi.",
        "Càng hiểu mình, ta càng dễ chọn con đường phù hợp.",
        "Đừng sợ tiến chậm; chỉ cần đừng đứng yên quá lâu.",
        "Một ngày có ý nghĩa không nhất thiết phải là một ngày bận rộn.",
        "Hãy giữ những người khiến bạn trở thành phiên bản tốt hơn ở gần mình.",
        "Tập trung vào giải pháp giúp ta mạnh hơn việc chỉ nhìn vào vấn đề.",
        "Khi có thể giúp ai đó, hãy giúp bằng sự chân thành.",
        "Sự giản dị thường mang lại cảm giác bền vững nhất.",
        "Đừng đánh đổi sự bình yên chỉ để thắng một cuộc tranh luận.",
        "Mỗi lần vượt qua nỗi sợ nhỏ, ta lại mạnh hơn một chút.",
        "Hãy làm những việc tương lai của bạn sẽ cảm ơn.",
        "Có những lúc im lặng là cách tốt nhất để giữ sự bình tĩnh.",
        "Thành công không chỉ là đến đích, mà còn là cách ta đi trên đường.",
        "Sự chăm sóc chân thành luôn được cảm nhận, dù không cần nói nhiều.",
        "Đừng để một sai sót nhỏ che khuất cả quá trình cố gắng.",
        "Hãy tin vào tiến bộ được tạo nên từ sự đều đặn.",
        "Không biết là bình thường; không chịu học mới là điều đáng tiếc.",
        "Mỗi lần kiên nhẫn thêm một chút, ta có thêm một cơ hội hiểu nhau.",
        "Hãy dành thời gian cho những điều khiến tâm trí được nghỉ ngơi.",
        "Một căn phòng sáng lên từ ánh đèn, một ngày sáng lên từ thái độ.",
        "Thời gian dành cho người thân chưa bao giờ là thời gian lãng phí.",
        "Hãy nhớ rằng điều quan trọng nhất đôi khi rất gần bên mình.",
        "Sự rõ ràng giúp ta tiết kiệm nhiều thời gian và hiểu lầm.",
        "Đừng quyết định chuyện lớn trong lúc cảm xúc đang quá mạnh.",
        "Hãy kiên nhẫn với những điều đang trong quá trình hoàn thiện.",
        "Một bước đúng hướng hôm nay đáng giá hơn nhiều lời hứa cho ngày mai.",
        "Hãy giữ những kỷ niệm đẹp, nhưng đừng quên sống với hiện tại.",
        "Mọi người đều cần được lắng nghe trước khi được khuyên bảo.",
        "Hãy cố gắng hiểu trước khi mong người khác hiểu mình.",
        "Một thói quen tốt bắt đầu từ một quyết định rất nhỏ.",
        "Đừng chờ mọi điều hoàn hảo mới bắt đầu.",
        "Mỗi ngày chăm sóc sức khỏe là một khoản đầu tư dài hạn.",
        "Sự chủ động giúp ta bớt lo lắng về những điều có thể chuẩn bị.",
        "Hãy trân trọng người luôn ở bên trong những ngày bình thường.",
        "Khi biết đủ, ta dễ nhận ra mình đang có rất nhiều.",
        "Một lời xin lỗi đúng lúc có thể chữa lành một khoảng cách dài.",
        "Hãy giữ lời nói của mình đáng tin như chữ ký của mình.",
        "Sống chậm lại đôi khi giúp ta đi xa hơn.",
        "Đừng để sự bận rộn làm ta quên điều thật sự quan trọng.",
        "Một việc được làm bằng cả sự chú ý thường có chất lượng khác biệt.",
        "Hãy để ngày khó khăn dạy bạn, đừng để nó định nghĩa bạn.",
        "Mỗi người tử tế ta gặp đều là một món quà của cuộc sống.",
        "Đừng ngại thay đổi kế hoạch khi mục tiêu vẫn còn đúng.",
        "Học cách nói không cũng là cách bảo vệ thời gian của mình.",
        "Sự trưởng thành thường đến từ những bài học không có trong sách.",
        "Hãy giữ đầu óc mở nhưng cũng giữ nguyên tắc của mình.",
        "Một người bạn tốt giúp ta nhìn rõ hơn khi mình đang rối.",
        "Đừng quên dành một phần ngày cho những điều khiến bạn vui.",
        "Sự đều đặn thắng sự bốc đồng trong những hành trình dài.",
        "Có trách nhiệm với lựa chọn của mình là một dạng tự do.",
        "Hãy nhìn vào điều đã tiến bộ, không chỉ điều còn thiếu.",
        "Một ngày bình yên cũng là một ngày thành công.",
        "Giữ được sự khiêm tốn giúp ta luôn còn chỗ để học thêm.",
        "Đừng ngại thay đổi góc nhìn khi có thêm thông tin mới.",
        "Sự chu đáo được thể hiện rõ nhất trong những việc nhỏ.",
        "Hãy dành năng lượng cho điều bạn có thể tạo ra khác biệt.",
        "Mỗi lần hoàn thành một việc nhỏ, ta xây thêm niềm tin vào chính mình.",
        "Một lời động viên đúng lúc có thể trở thành sức mạnh rất lâu.",
        "Đừng để nỗi lo ngày mai lấy mất sự bình yên của hôm nay.",
        "Hãy chuẩn bị tốt rồi bước đi với sự bình tĩnh.",
        "Có những câu trả lời chỉ xuất hiện sau khi ta đủ kiên nhẫn.",
        "Sự quan tâm thật sự thường nằm trong việc nhớ những điều nhỏ bé.",
        "Đừng tiếc thời gian dành cho việc học cách làm tốt hơn.",
        "Khi mệt, hãy giảm tốc độ chứ không nhất thiết phải dừng lại.",
        "Hãy để lòng biết ơn kết thúc một ngày nhiều suy nghĩ.",
        "Một buổi sáng có kế hoạch giúp cả ngày bớt vội vàng.",
        "Sự tự giác giúp ta tiến lên ngay cả khi không ai nhắc nhở.",
        "Đừng đánh giá một người chỉ qua một khoảnh khắc khó khăn.",
        "Hãy giữ những điều tốt đẹp đủ lâu để chúng thành thói quen.",
        "Mỗi ngày đọc thêm vài trang cũng có thể mở rộng cả một thế giới.",
        "Điều ta cho đi bằng sự chân thành thường quay lại theo cách bất ngờ.",
        "Hãy học cách phân biệt điều khẩn cấp với điều thật sự quan trọng.",
        "Sự an nhiên không đến từ việc không có vấn đề, mà từ cách ta đối diện.",
        "Một quyết định có suy nghĩ luôn đáng giá hơn một lựa chọn vội vàng.",
        "Đừng để sự hoàn hảo ngăn bạn hoàn thành điều có ích.",
        "Hãy cho bản thân thời gian để hiểu những điều mới.",
        "Mỗi người có một câu chuyện mà ta chưa biết hết.",
        "Sự cảm thông bắt đầu khi ta ngừng phán xét quá nhanh.",
        "Hãy giữ sự tử tế kể cả khi không ai nhìn thấy.",
        "Một ngày tốt không cần nhiều, chỉ cần vài điều thật sự ý nghĩa.",
        "Đừng quên gọi hỏi thăm người mà bạn đang nhớ đến.",
        "Sự chuẩn bị tốt làm cho cơ hội trở nên hữu ích hơn.",
        "Hãy kiên định với mục tiêu nhưng linh hoạt với cách thực hiện.",
        "Những gì ta luyện tập mỗi ngày sẽ dần trở thành con người của ta.",
        "Đừng để ý kiến của số đông thay thế suy nghĩ của chính mình.",
        "Hãy lắng nghe cả những điều người khác chưa nói thành lời.",
        "Một bữa cơm bình yên bên gia đình là điều rất đáng trân trọng.",
        "Đôi khi thay đổi nhỏ trong cách nghĩ tạo nên thay đổi lớn trong cảm xúc.",
        "Hãy làm việc chăm chỉ nhưng cũng nhớ sống một cuộc đời có niềm vui.",
        "Sự trung thực giúp mọi mối quan hệ bền hơn theo thời gian.",
        "Đừng ngại thừa nhận mình cần sự giúp đỡ.",
        "Một người biết hợp tác có thể đi xa hơn một người chỉ muốn tự làm mọi thứ.",
        "Hãy bảo vệ thời gian tập trung như bảo vệ một điều quý giá.",
        "Mỗi ngày vận động một chút là cách cơ thể cảm ơn bạn về sau.",
        "Đừng để một lời nói thiếu suy nghĩ làm hỏng một mối quan hệ đáng quý.",
        "Hãy nói điều cần nói bằng cách khiến người khác vẫn muốn lắng nghe.",
        "Sự cẩn thận hôm nay có thể tránh nhiều phiền phức ngày mai.",
        "Một người biết giữ bình tĩnh thường nhìn thấy nhiều lựa chọn hơn.",
        "Hãy để sự tiến bộ là động lực, không phải áp lực.",
        "Đừng quên rằng bạn có quyền thay đổi khi đã hiểu mình hơn.",
        "Mỗi khoảng thời gian khó khăn rồi cũng sẽ trở thành một phần câu chuyện đã qua.",
        "Hãy chọn bạn bè bằng sự tin cậy, không chỉ bằng sự vui vẻ.",
        "Sự thấu hiểu cần thời gian và sự lắng nghe thật sự.",
        "Đừng để điện thoại lấy hết những phút giây đang ở bên người thân.",
        "Một việc tốt âm thầm vẫn có giá trị dù không ai biết.",
        "Hãy đặt câu hỏi tốt nếu muốn tìm câu trả lời tốt.",
        "Sự tự tin không cần ồn ào; nó đến từ việc biết mình đã chuẩn bị.",
        "Đừng bỏ cuộc chỉ vì kết quả chưa đến đúng lúc bạn mong đợi.",
        "Hãy giữ một góc nhỏ trong ngày chỉ dành cho sự bình yên.",
        "Mỗi trải nghiệm đều có thể dạy ta điều gì đó nếu ta chịu nhìn lại.",
        "Sự chủ động trong hôm nay tạo thêm lựa chọn cho ngày mai.",
        "Đừng ngại nói lời yêu thương với gia đình khi còn có thể.",
        "Hãy trân trọng những người vui khi bạn tiến bộ.",
        "Một cuộc sống tốt được xây từ nhiều ngày bình thường sống có ý nghĩa.",
        "Sự nhẫn nại giúp những điều khó trở nên có thể.",
        "Đừng mang mọi chuyện của hôm qua vào một buổi sáng mới.",
        "Hãy để mỗi lần vấp ngã giúp bước chân sau chắc hơn.",
        "Người biết quản lý thời gian thường có thêm thời gian cho người mình yêu quý.",
        "Một lời khen chân thành có thể giúp ai đó tự tin hơn rất nhiều.",
        "Hãy làm điều tử tế ngay cả khi việc đó rất nhỏ.",
        "Đừng ngại chậm lại để chắc rằng mình đang đi đúng đường.",
        "Sự tập trung là cách biến thời gian thành kết quả.",
        "Hãy dành thời gian nhìn lại để biết mình đã đi được bao xa.",
        "Một ngày có khó khăn vẫn có thể có những khoảnh khắc đẹp.",
        "Đừng để thất bại tạm thời trở thành kết luận cuối cùng.",
        "Hãy tìm niềm vui trong quá trình, không chỉ ở kết quả.",
        "Sự nhất quán khiến những mục tiêu xa dần trở nên gần.",
        "Một trái tim bình tĩnh thường đưa ra lựa chọn sáng suốt.",
        "Hãy quý trọng sức khỏe khi cơ thể vẫn đang khỏe mạnh.",
        "Đừng quên rằng những người thân yêu cũng cần thời gian của bạn.",
        "Sự khiêm nhường khiến thành công trở nên đẹp hơn.",
        "Hãy sống sao để cuối ngày bạn thấy thời gian của mình được dùng xứng đáng.",
        "Một bước nhỏ đúng lúc có thể thay đổi cả hướng đi.",
        "Đừng sợ những ngày bắt đầu lại; đôi khi đó là cơ hội tốt nhất.",
        "Hãy giữ hy vọng, nhưng cũng hãy hành động.",
        "Sự kiên trì có thể biến điều khó thành điều quen thuộc.",
        "Một người biết cảm ơn sẽ nhìn thấy nhiều điều đáng quý hơn.",
        "Hãy chọn bình yên khi một cuộc tranh cãi không còn ý nghĩa.",
        "Đừng quên chăm sóc chính mình trong lúc chăm sóc người khác.",
        "Sự tin cậy được xây rất lâu nhưng có thể mất chỉ trong một phút.",
        "Hãy dùng lời nói để kết nối, không phải để làm tổn thương.",
        "Mỗi buổi tối bình yên là một lý do để biết ơn.",
        "Đừng quá khắt khe với một phiên bản của mình đang cố gắng học hỏi.",
        "Hãy làm điều có ích ngay cả khi chưa ai ghi nhận.",
        "Sự trưởng thành là biết điều gì đáng để giữ và điều gì nên buông.",
        "Một ngày mới luôn cho ta thêm một cơ hội để làm điều đúng.",
        "Hãy để sự tử tế trở thành thói quen không cần lý do.",
        "Đừng quên rằng thời gian bên nhau quý hơn nhiều món quà.",
        "Sự rõ ràng trong mục tiêu giúp bước chân bớt do dự.",
        "Hãy giữ lòng biết ơn cho cả những điều rất nhỏ.",
        "Một cuộc đời ý nghĩa được tạo nên từ cách ta sống từng ngày."
    )
}
