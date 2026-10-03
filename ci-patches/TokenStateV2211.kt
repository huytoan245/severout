package com.family.child

import android.content.Context

data class TokenSnapshot(val token: String, val generation: Long)

/** One durable monotonic generation for onNewToken and every worker instance. */
object TokenState {
    @Synchronized fun current(context: Context): TokenSnapshot {
        val p = context.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE)
        return TokenSnapshot(p.getString("fcm_token", "") ?: "", p.getLong("fcm_token_generation_v2211", 0L))
    }
    @Synchronized fun observe(context: Context, token: String, expected: Long? = null): TokenSnapshot {
        val before = current(context)
        if (expected != null && before.generation != expected) return before
        if (token.isBlank()) return before
        if (before.token == token && before.generation > 0L) return before
        val next = TokenSnapshot(token, maxOf(System.currentTimeMillis(), before.generation + 1))
        check(context.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE).edit()
            .putString("fcm_token", next.token).putLong("fcm_token_generation_v2211", next.generation)
            .putLong("fcm_token_local_at", System.currentTimeMillis()).putLong("fcm_token_cloud_at", 0L)
            .putBoolean("fcm_wake_ready_v229", false).commit())
        return next
    }
    @Synchronized fun confirm(context: Context, snapshot: TokenSnapshot): Boolean {
        if (current(context) != snapshot) return false
        return context.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE).edit()
            .putLong("fcm_token_cloud_at", System.currentTimeMillis())
            .putLong("fcm_token_confirmed_generation_v2211", snapshot.generation)
            .putBoolean("fcm_wake_ready_v229", true).commit()
    }
}
