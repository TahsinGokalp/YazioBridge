package studio.kodrika.yaziobridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * 6 saatte bir arka plan eşitlemesi (internet varken). Health Connect arka planda YAZMAYA izin verir;
 * okuma yapılmadığı için ek "arka planda okuma" izni gerekmez. Sonuç ya da hata ekranda gösterilir.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) return Result.success()
        val yazio = Yazio(ctx); val bridge = Bridge(ctx); val vault = Vault(ctx)
        if (!yazio.loggedIn() || !bridge.hasPermissions()) return Result.success()
        val days = (vault.get("days")?.toIntOrNull() ?: 7).coerceAtMost(7)
        return runCatching { bridge.sync(yazio, days) }.fold(
            onSuccess = { vault.set("bgErr", null); Result.success() },
            onFailure = { e ->
                vault.set("bgErr", e.message ?: e.toString())
                if (e is Yazio.HttpErr && e.code in 400..499) Result.success() else Result.retry()
            },
        )
    }

    companion object {
        private const val NAME = "yazio-sync"

        fun enabled(ctx: Context) = Vault(ctx).get("bg") != "0"

        /** Açıksa planlar (zaten planlıysa dokunmaz), kapalıysa iptal eder */
        fun apply(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled(ctx)) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun setEnabled(ctx: Context, on: Boolean) { Vault(ctx).set("bg", if (on) null else "0"); apply(ctx) }
    }
}
