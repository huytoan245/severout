package com.family.child

import android.content.Context
import com.family.core.RefreshStage
import org.json.JSONObject

/** A small durable stage journal, including failures before LocationService can start. */
object RefreshStatus {
    fun progress(context: Context, request: Long, stage: RefreshStage, code: String = "", message: String = "") {
        if(request<=0) return
        val now=System.currentTimeMillis()
        val fields=mutableMapOf<String,Any?>("refreshErrorCode" to code)
        val prefix=when(stage){
            RefreshStage.FCM_RECEIVED -> "refreshReceived"
            RefreshStage.SERVICE_STARTED -> "refreshService"
            RefreshStage.GPS_ACQUIRING -> "refreshLocating"
            RefreshStage.GPS_VALIDATED -> "refreshValidated"
            RefreshStage.LOCAL_PERSISTED -> "refreshPersisted"
            RefreshStage.FAILED -> "refreshFailed"
            else -> return
        }
        fields[prefix+"For"]=request;fields[prefix+"At"]=now
        if(stage==RefreshStage.SERVICE_STARTED){fields["refreshAckFor"]=request;fields["refreshAckAt"]=now}
        fields["refreshResult"]=if(stage==RefreshStage.FAILED) "failed" else stage.name.lowercase()
        if(message.isNotEmpty()) fields["lastError"]=message
        val accepted=try{PendingStore(context).useStore { store ->
            val before=JSONObject(store.meta("refresh_stage_v232")?:"{}")
            val terminal=JSONObject(store.meta("refresh_result")?:"{}")
            val old=before.optLong("request")
            val rank=before.optInt("rank",-1)
            if(terminal.optLong("request")>=request || old>request || (old==request && rank>=stage.ordinal)) false
            else {store.putMeta("refresh_stage_v232",JSONObject().put("request",request).put("rank",stage.ordinal).put("time",now).toString())
                if(stage==RefreshStage.FAILED)store.finishRefresh(request,JSONObject().put("request",request).put("fields",JSONObject(fields)).toString())
                true}
        }}catch(_:Exception){false}
        if(accepted)ChildDeviceWriter.write(fields)
    }
    fun retry(context: Context) {
        val envelope=try{PendingStore(context).useStore { JSONObject(it.meta("refresh_result")?:"{}") }}catch(_:Exception){return}
        val request=envelope.optLong("request"); val data=envelope.optJSONObject("fields")?:return
        if(request<=context.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE).getLong("refresh_delivered_for_v2210",0))return
        val fields=data.keys().asSequence().associateWith { if(data.isNull(it))null else data.get(it) }
        ChildDeviceWriter.write(fields){ok->if(ok)context.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE).edit().putLong("refresh_delivered_for_v2210",request).apply()}
    }
}
