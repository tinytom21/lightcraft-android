package ai.storyteller.lightcraft.engine

import ai.storyteller.lightcraft.LightcraftApplication
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The background half of sending photos to the home PC (WorkManager, so it goes on after the app is closed, and only when there is a
 * network):
 *
 * * [send]: empties the queue ([PhoneUploads.queuePicked] or the camera scan filled it);
 * * [follow]: looks again for the home PC's answers 15 minutes later while photos wait for it;
 * * [applyAuto]: while "Send new camera photos automatically" is on, a job every 15 minutes (the least WorkManager repeats) that finds
 *   the new camera photos and sends them. A periodic job beats a content observer here: it survives the app being killed and a reboot
 *   without a service, and finds what was taken while there was no network.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as LightcraftApplication
        val engine = app.engine.await().getOrNull() ?: return@withContext Result.retry()
        val session = engine.session
        if (!session.cloudStatus().connected) return@withContext Result.success()
        val settings = UploadSettings(applicationContext)
        try {
            if (inputData.getBoolean(SCAN, false)) {
                PhoneUploads.scanCamera(applicationContext, session, settings, if (inputData.getBoolean(WATCH, false)) "media changed" else "periodic")
            }
            while (!isStopped) {
                val step = session.cloudUploadStep(PhoneUploads.mayUseNetwork(applicationContext, settings))
                step.problems.forEach { Log.w(TAG, it) }
                if (!step.more) break
            }
        } catch (e: Exception) {
            // (a network that failed: what was sent is recorded, the next try goes on from there)
            Log.w(TAG, "upload step failed: ${e.message}")
            return@withContext Result.retry()
        }
        if (session.cloudUploadsPending() > 0u) UploadScheduler.follow(applicationContext)
        // a content-triggered job runs once: the next one waits for the next photo
        if (inputData.getBoolean(WATCH, false) && settings.auto) UploadScheduler.watchCamera(applicationContext, settings, replace = true)
        Result.success()
    }

    companion object {
        const val SCAN = "scan"
        const val WATCH = "watch"
        private const val TAG = "LcUploads"
    }
}

object UploadScheduler {
    private const val SEND = "lc-uploads-send"
    private const val FOLLOW = "lc-uploads-follow"
    private const val CAMERA = "lc-uploads-camera"
    private const val WATCH = "lc-uploads-watch"

    private fun constraints(unmetered: Boolean) =
        Constraints.Builder().setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    /** Send what is queued, as soon as there is a network. A photo the person just picked goes over any network. */
    fun send(context: Context, unmetered: Boolean = false, keep: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(unmetered))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SEND, if (keep) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Photos are with the home PC and wait for its answer: look again in 15 minutes. */
    fun follow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints(false)).setInitialDelay(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(FOLLOW, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * A one-shot job that runs when a picture is added to the gallery (a content URI trigger, a second or so after the last change),
     * scans the camera folder and sends. It runs once, so it enqueues the next one when it is done ([replace]); the periodic job
     * stays as the fallback for a trigger the system missed.
     */
    fun watchCamera(context: Context, settings: UploadSettings = UploadSettings(context), replace: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .addContentUriTrigger(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(3, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(UploadWorker.SCAN to true, UploadWorker.WATCH to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Make the repeating camera job match the settings: scheduled when "Send new camera photos automatically" is on, gone when off. */
    fun applyAuto(context: Context, settings: UploadSettings = UploadSettings(context)) {
        val wm = WorkManager.getInstance(context)
        if (!settings.auto) {
            wm.cancelUniqueWork(CAMERA)
            wm.cancelUniqueWork(WATCH)
            return
        }
        watchCamera(context, settings)
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(settings.wifiOnly))
            .setInputData(workDataOf(UploadWorker.SCAN to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(CAMERA, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
