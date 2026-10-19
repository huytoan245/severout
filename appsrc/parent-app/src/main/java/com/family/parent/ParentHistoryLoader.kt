package com.family.parent

import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import java.util.concurrent.atomic.AtomicBoolean

/** Daily data is complete, independently of the small realtime/render windows. */
object ParentHistoryLoader {
    private const val PAGE=500L
    fun load(start:Long,end:Long,onProgress:(Int)->Unit,done:(List<UiEvent>?,String?)->Unit):()->Unit {
        val cancelled=AtomicBoolean(false); val fallback=AtomicBoolean(false)
        val all=LinkedHashMap<String,UiEvent>()
        val collection=FirebaseFirestore.getInstance().collection("devices").document("child-01").collection("events")
        fun finish(){if(!cancelled.get())done(all.values.sortedWith(compareBy({it.time},{it.documentId})),null)}
        fun fail(){if(cancelled.get()||!fallback.compareAndSet(false,true))return
            ParentHttpsBridge.readEvents(start,end){rows,error->if(!cancelled.get())done(rows?.map{UiEvent(it.type,it.time,it.lat,it.lon,it.accuracy,it.id,it.offlineSince,it.durationMs,it.documentId)},error)}}
        fun add(doc:DocumentSnapshot){doc.toObject(UiEvent::class.java)?.copy(documentId=doc.id)?.let{all[doc.id]=it}}
        fun context(after:DocumentSnapshot?=null){
            if(cancelled.get())return
            var q=collection.whereLessThan("time",start).orderBy("time",Query.Direction.DESCENDING).orderBy(FieldPath.documentId(),Query.Direction.DESCENDING).limit(PAGE)
            if(after!=null)q=q.startAfter(after)
            q.get(Source.SERVER).addOnSuccessListener{page->
                if(cancelled.get())return@addOnSuccessListener
                page.documents.forEach(::add);onProgress(all.size)
                // Carry the lifecycle anchor into the day, including its earlier starts/ends.
                val anchor=page.documents.any{it.getString("type") in setOf("visit_start","trip_start")}
                if(page.size()<PAGE || anchor)finish() else context(page.documents.last())
            }.addOnFailureListener{fail()}
        }
        fun page(after:DocumentSnapshot?=null){
            if(cancelled.get())return
            var q=collection.whereGreaterThanOrEqualTo("time",start).whereLessThan("time",end).orderBy("time").orderBy(FieldPath.documentId()).limit(PAGE)
            if(after!=null)q=q.startAfter(after)
            q.get(Source.SERVER).addOnSuccessListener{result->
                if(cancelled.get())return@addOnSuccessListener
                result.documents.forEach(::add);onProgress(all.size)
                if(result.size()<PAGE)context() else page(result.documents.last())
            }.addOnFailureListener{fail()}
        }
        page();return {cancelled.set(true)}
    }
}
