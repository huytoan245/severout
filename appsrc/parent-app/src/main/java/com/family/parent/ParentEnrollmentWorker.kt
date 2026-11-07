package com.family.parent

import android.content.Context
import androidx.work.*
import com.family.enrollment.EnrollmentClient
import com.family.enrollment.EnrollmentFailure
import com.family.enrollment.EnrollmentStartup
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

class ParentEnrollmentWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs=applicationContext.getSharedPreferences("wake_diag",Context.MODE_PRIVATE)
        fun state(value:String,code:String){if(!isStopped)prefs.edit().putString("enrollment_state",value).putString("enrollment",code).putLong("enrollment_updated_at",System.currentTimeMillis()).apply()}
        if(runAttemptCount>=4){state("RETRY_EXHAUSTED",prefs.getString("enrollment","auth_init_timeout")?:"auth_init_timeout");return Result.failure()}
        state("AUTHENTICATING","auth_initializing")
        return try {
            EnrollmentClient.ensureRegistered(applicationContext, BuildConfig.WAKE_WORKER_URL, "parent", BuildConfig.BOOTSTRAP_TOKEN, force = true)
            if(isStopped)return Result.success()
            FirebaseFirestore.getInstance().enableNetwork()
            state("REGISTERED","registered")
            Result.success()
        } catch (e: EnrollmentFailure) {
            if(isStopped)return Result.success()
            state(if(EnrollmentStartup.recovery(e.code) || (e.status==403 && !e.retryable))"RECOVERY_REQUIRED" else if(e.retryable && EnrollmentStartup.retry(e.code,runAttemptCount))"RETRYING" else "RETRY_EXHAUSTED",e.code)
            if (e.retryable && EnrollmentStartup.retry(e.code,runAttemptCount)) Result.retry() else Result.failure()
        } catch (_: Exception) {
            if(isStopped)return Result.success()
            state(if(runAttemptCount<3)"RETRYING" else "RETRY_EXHAUSTED","firebase_connection_error")
            if(runAttemptCount<3)Result.retry() else Result.failure()
        }
    }
    companion object {
        fun schedule(context: Context, retry: Boolean = false) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("family-parent-enrollment-v231", if(retry)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ParentEnrollmentWorker>().setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork("family-parent-enrollment-periodic-v231", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ParentEnrollmentWorker>(6, TimeUnit.HOURS).setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
