package com.amar.vault.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Gives every picture in the vault that has no vector from the photo model in use one: pictures
 * stored before photo search existed, pictures indexed while its models were not on the device,
 * and pictures that changed. New pictures are embedded as they are indexed; this catches the rest.
 *
 * It runs while the battery is not low, one picture at a time, from the small thumbnail, newest
 * pictures first, and starts itself again until none is left. A picture that cannot be read is
 * recorded with an empty vector, so it is not tried again until it changes.
 */
class PhotoBackfillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val photos = photoSearchOf(app)
        val model = photos.modelId ?: return Result.success()
        val dao = VaultDatabase.get(app).photoVectorDao()
        val gone = dao.deleteOrphans()
        if (gone > 0) VaultLog.i(TAG, "forgot $gone vectors of pictures no longer in the vault")
        // Screenshots were embedded too before; photo search is for photos now.
        val notPhotos = dao.ofItemsNotPhotos()
        if (notPhotos.isNotEmpty()) {
            photos.forget(notPhotos)
            VaultLog.i(TAG, "forgot ${notPhotos.size} vectors of screenshots")
        }
        var embedded = 0
        var unreadable = 0
        val started = System.currentTimeMillis()
        while (!isStopped) {
            val batch = dao.picturesWithout(model, BATCH)
            if (batch.isEmpty()) break
            for (picture in batch) {
                if (isStopped) break
                val bitmap = PhotoThumbnails.load(app, Uri.parse(picture.uri))
                if (bitmap == null) {
                    dao.upsert(PhotoVector(picture.id, model, picture.pHash, ByteArray(0), 0f, System.currentTimeMillis()))
                    unreadable++
                    continue
                }
                try {
                    if (!photos.embed(picture.id, picture.pHash, bitmap)) return Result.success()
                    embedded++
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val ms = System.currentTimeMillis() - started
        VaultLog.i(TAG, "embedded $embedded pictures in $ms ms" + if (unreadable > 0) ", $unreadable unreadable" else "")
        if (isStopped) return Result.success()
        if (dao.countWithout(model) > 0) enqueue(app)
        else {
            // Every picture has a vector from this model, so an earlier model's are no longer needed.
            val replaced = dao.deleteAllBut(model)
            if (replaced > 0) VaultLog.i(TAG, "dropped $replaced vectors of an earlier photo model")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "PhotoBackfill"
        private const val WORK_NAME = "photo_backfill"
        private const val BATCH = 32

        /** Starts (or queues after the running one) a pass over the pictures that need a vector. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PhotoBackfillWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** How code that Hilt does not construct (workers, the indexing pipeline) reaches the one [PhotoSearch]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhotoSearchEntryPoint {
    fun photoSearch(): PhotoSearch
}

fun photoSearchOf(context: Context): PhotoSearch =
    EntryPointAccessors.fromApplication(context.applicationContext, PhotoSearchEntryPoint::class.java).photoSearch()

/**
 * A picture at about [side] pixels on its long side, decoded into ordinary memory: MediaStore's
 * thumbnail where Android has one (it is usually already made), else a decode sized down on the
 * way in. Null when the picture cannot be read.
 */
object PhotoThumbnails {

    fun load(context: Context, uri: Uri, side: Int = 512): Bitmap? {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            try {
                return software(cr.loadThumbnail(uri, Size(side, side), null))
            } catch (e: Exception) {
                // No thumbnail for this one: decode it below.
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longSide = maxOf(info.size.width, info.size.height)
                    if (longSide > side) {
                        val f = side.toFloat() / longSide
                        decoder.setTargetSize(maxOf(1, (info.size.width * f).toInt()), maxOf(1, (info.size.height * f).toInt()))
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A copy in ordinary memory when [bitmap] lives on the GPU, whose pixels cannot be read. */
    private fun software(bitmap: Bitmap): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        } else bitmap
}
