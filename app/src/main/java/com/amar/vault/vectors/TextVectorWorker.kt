package com.amar.vault.vectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import com.amar.vault.photos.photoSearchOf
import java.util.concurrent.TimeUnit

class TextVectorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val search = photoSearchOf(app)
        if (!search.isReady || !search.install()) return Result.success()
        val indexer = TextVectorIndexer(VaultDatabase.get(app), search)
        val all = inputData.getBoolean(KEY_ALL, false)
        if (DeviceTier.tier(app, true) == SearchTier.CORE) return Result.success()
        DeviceTier.measureOnce(app, search)
        if (Pacing.tooHot(app) || Pacing.saver(app)) {
            enqueueLater(app)
            return Result.success()
        }
        val first = indexer.run(1, shouldStop = { isStopped || Pacing.tooHot(app) }) { done, left ->
            setProgress(workDataOf(KEY_DONE to done, KEY_LEFT to left, KEY_TIER to 1))
        }
        if (first.remaining < 0 || isStopped) return Result.success()
        if (first.remaining > 0) {
            enqueueLater(app)
            return Result.success()
        }
        if (!all && !DeviceTier.embedsEveryPiece(app)) return Result.success()
        if (!all && !Pacing.charging(app)) {
            enqueueOnCharge(app)
            return Result.success()
        }
        val second = indexer.run(2, shouldStop = { isStopped || Pacing.tooHot(app) || (!all && !Pacing.charging(app)) }) { done, left ->
            setProgress(workDataOf(KEY_DONE to done, KEY_LEFT to left, KEY_TIER to 2))
        }
        if (second.remaining > 0 && !isStopped) {
            if (Pacing.charging(app) || all) enqueueLater(app) else enqueueOnCharge(app)
        } else if (second.remaining == 0) {
            val model = search.textModelId
            if (model != null) {
                val dropped = VaultDatabase.get(app).textVectorDao().deleteAllBut(model)
                if (dropped > 0) VaultLog.i(TAG, "dropped $dropped vectors of an earlier text model")
            }
            indexer.refreshCoverage()
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "TextVectorWorker"
        const val WORK_NAME = "text_vectors"
        const val WORK_ON_CHARGE = "text_vectors_on_charge"
        const val KEY_DONE = "done"
        const val KEY_LEFT = "left"
        const val KEY_TIER = "tier"
        const val KEY_ALL = "all"

        fun enqueue(context: Context, all: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<TextVectorWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(KEY_ALL to all))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        private fun enqueueLater(context: Context) {
            val request = OneTimeWorkRequestBuilder<TextVectorWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        private fun enqueueOnCharge(context: Context) {
            val request = OneTimeWorkRequestBuilder<TextVectorWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_ON_CHARGE, ExistingWorkPolicy.KEEP, request)
        }
    }
}

object Pacing {
    fun charging(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun saver(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    fun tooHot(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }
}
