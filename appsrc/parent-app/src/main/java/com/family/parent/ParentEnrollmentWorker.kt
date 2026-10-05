package com.family.parent

import android.content.Context
import androidx.work.*
import com.family.enrollment.EnrollmentClient
import com.family.enrollment.EnrollmentFailure
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

class ParentEnrollmentWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            EnrollmentClient.ensureRegistered(applicationContext, BuildConfig.WAKE_WORKER_URL, "parent", force = true)
            FirebaseFirestore.getInstance().enableNetwork()
            applicationContext.getSharedPreferences("wake_diag", Context.MODE_PRIVATE).edit().putString("enrollment", "registered").apply()
            Result.success()
        } catch (e: EnrollmentFailure) {
            applicationContext.getSharedPreferences("wake_diag", Context.MODE_PRIVATE).edit().putString("enrollment", e.code).apply()
            if (e.retryable) Result.retry() else Result.failure()
        } catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("family-parent-enrollment-v231", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ParentEnrollmentWorker>().setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork("family-parent-enrollment-periodic-v231", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ParentEnrollmentWorker>(6, TimeUnit.HOURS).setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
