package com.family.parent

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.family.enrollment.EnrollmentFailure
import com.family.enrollment.EnrollmentMode
import com.family.enrollment.ParentRecovery
import java.util.concurrent.Executors

/** Separate launcher: background enrollment stays blocked until approved recovery completes. */
class RecoveryActivity:ComponentActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var status:TextView
    private lateinit var exportButton:Button
    private lateinit var applyButton:Button
    private var encryptedReport:String?=null
    private val saveReport=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)runTask { val data=encryptedReport?:error("inspect_first");contentResolver.openOutputStream(uri,"wt")!!.use{it.write(data.toByteArray())};"Đã lưu báo cáo mã hóa. Chuyển file về máy tính; không gửi dữ liệu định danh vào chat." }
    }
    private val importApproval=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runTask {
            val envelope=contentResolver.openInputStream(uri)!!.use{input->
                val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                while(true){val n=input.read(buffer);if(n<0)break;check(bytes.size()+n<=16_384);bytes.write(buffer,0,n)}
                bytes.toString("UTF-8")
            }
            ParentRecovery.importApproval(this,BuildConfig.RECOVERY_OPERATOR_KEY,envelope)
            runOnUiThread{applyButton.isEnabled=true}
            "Đã kiểm tra phiếu phê duyệt Parent-only. Chưa thay định danh hoặc gửi rebind."
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(!EnrollmentMode.recovery(this)){finish();return}
        if(getSharedPreferences("parent_recovery_v233",MODE_PRIVATE).getBoolean("completed",false)){
            startActivity(Intent(this,MainActivity::class.java));finish();return
        }
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,36,28,24)}
        fun text(value:String)=TextView(this).apply{text=value;textSize=17f;column.addView(this)}
        text("Kết Nối · Parent Recovery v2.3.3")
        text("Chỉ kiểm tra khi mở. Không tự tạo UID/key, đổi Child hoặc rebind. Không gỡ ứng dụng/Xóa dữ liệu.")
        status=text("Đang đọc Auth/Keystore…")
        fun button(label:String,action:()->Unit)=Button(this).apply{text=label;setOnClickListener{action()};column.addView(this)}
        button("Kiểm tra lại"){inspect()}
        exportButton=button("Lưu báo cáo mã hóa"){
            runTask{encryptedReport=ParentRecovery.export(this,BuildConfig.RECOVERY_OPERATOR_KEY);runOnUiThread{saveReport.launch("Family-Parent-Recovery-encrypted.json")};"Chọn nơi lưu báo cáo mã hóa."}
        }.apply{isEnabled=false}
        button("Nhập phiếu phê duyệt"){importApproval.launch(arrayOf("application/json","application/octet-stream"))}
        applyButton=button("Thực hiện Parent-only recovery đã duyệt"){
            android.app.AlertDialog.Builder(this).setTitle("Khôi phục Parent đã được duyệt?")
                .setMessage("Chỉ thực hiện khi operator đã xác minh máy/binding và bạn đã phê duyệt đổi Parent UID/key, tăng epoch. Child và lịch sử giữ nguyên.")
                .setNegativeButton("Dừng",null).setPositiveButton("Thực hiện"){_,_->runTask{
                    ParentRecovery.applyApproved(this,BuildConfig.WAKE_WORKER_URL)
                    runOnUiThread{startActivity(Intent(this,MainActivity::class.java));finish()};"Parent đã được xác thực."
                }}.show()
        }.apply{isEnabled=false}
        setContentView(column);inspect()
    }
    private fun inspect(){runTask{
        val data=ParentRecovery.inspect(this)
        runOnUiThread{exportButton.isEnabled=true}
        "Auth local: ${data.auth}\nKeystore: ${data.key}\nKey sử dụng được: ${data.usable}\nMã: ${data.code}\nKhông hiện UID/key/material."
    }}
    private fun runTask(action:()->String){
        if(isFinishing)return
        worker.execute{
            val result=try{action()}catch(e:Exception){
                val safe=setOf("inspect_first","backend_guard_required","separate_operator_approval_required","approval_expired","approval_signature_invalid","approval_scope_invalid","existing_key_preserved","device_changed","auth_changed_since_inspection","auth_creation_unresolved_stop","identity_changed","key_changed","fresh_identity_not_confirmed","backend_guard_not_confirmed","operation_already_prepared","recovery_state_changed","recovery_result_invalid")
                "DỪNG · "+if(e is EnrollmentFailure)e.code else e.message?.takeIf{it in safe}?:"operation_failed"
            }
            runOnUiThread{if(!isDestroyed)status.text=result}
        }
    }
    override fun onDestroy(){worker.shutdown();super.onDestroy()}
}
