package ai.storyteller.lightcraft.engine

import ai.storyteller.lightcraft.core.MobileSession
import ai.storyteller.lightcraft.core.UploadKind
import ai.storyteller.lightcraft.core.UploadPhoto
import ai.storyteller.lightcraft.core.UploadQueued
import ai.storyteller.lightcraft.core.cloudUploadKind
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The settings of sending photos to the home PC (kept in the app's preferences). */
class UploadSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("uploads", Context.MODE_PRIVATE)

    /** "Send new camera photos automatically": off until the person turns it on. */
    var auto: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    /** "Only on Wi-Fi" (an unmetered network): on by default. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit().putBoolean("wifiOnly", v).apply()

    /** When [auto] was switched on (ms): photos taken before it are never sent. */
    var since: Long
        get() = prefs.getLong("since", 0L)
        set(v) = prefs.edit().putLong("since", v).apply()

    /** Camera photos added before this (seconds) have been looked at. */
    var watermark: Long
        get() = prefs.getLong("watermark", 0L)
        set(v) = prefs.edit().putLong("watermark", v).apply()

    /** MediaStore ids of the photos added at or after [watermark] that were looked at already. */
    var handled: Set<String>
        get() = prefs.getStringSet("handled", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("handled", v).apply()

    /** Switch the automatic sending on (from now) or off. */
    fun setAuto(on: Boolean, now: Long = System.currentTimeMillis()) {
        if (on && !auto) {
            since = now
            watermark = now / 1000
            handled = emptySet()
        }
        auto = on
    }
}

/**
 * Photos to the home PC: copies the photos to files (the Rust side wants paths), works out the day each was taken, and hands them to
 * the upload queue. The queue, the sending and the status are Rust (`MobileSession.cloud*Upload*`).
 */
object PhoneUploads {
    /** What a scan of the camera folder found. */
    data class Scan(val seen: Int, val queued: UploadQueued?)

    /** Photos picked with the system photo picker: copied and queued. Blocks: call it off the main thread. */
    fun queuePicked(context: Context, session: MobileSession, uris: List<Uri>): UploadQueued {
        val photos = uris.mapNotNull { runCatching { stage(context, session, it) }.getOrNull() }
        return session.cloudEnqueueUploads(photos)
    }

    /**
     * New photos in the camera folder (DCIM/Camera) taken after the automatic sending was switched on, copied and queued. Needs the
     * permission to read all photos: with only the photos the person selected (Android 14) the new ones are not visible and nothing is
     * found. Blocks: call it off the main thread.
     */
    fun scanCamera(context: Context, session: MobileSession, settings: UploadSettings): Scan {
        if (!settings.auto || DeviceMedia.access(context) != DeviceAccess.FULL) return Scan(0, null)
        val look = lookAtCamera(context, settings)
        val staged = mutableListOf<UploadPhoto>()
        val failed = mutableListOf<CameraRow>()
        for (row in look.fresh) {
            val photo = runCatching { stage(context, session, row.uri, row.takenHint) }.getOrNull()
            if (photo != null) staged += photo else failed += row
        }
        val queued = if (staged.isEmpty()) null else session.cloudEnqueueUploads(staged)
        // what could not be copied (the file was busy) is looked at again next time
        settings.commit(look, failed.map { it.id.toString() }.toSet(), failed.minOfOrNull { it.added })
        return Scan(look.fresh.size, queued)
    }

    /** A camera photo as the gallery lists it. */
    class CameraRow(val id: Long, val uri: Uri, val added: Long, val takenHint: Long)

    /** What a look at the camera folder found: the photos to send, and every id seen with when it was added. */
    class Look(val fresh: List<CameraRow>, val seen: Map<String, Long>, val newest: Long)

    private fun UploadSettings.commit(look: Look, failedIds: Set<String>, oldestFailed: Long?) {
        // (a photo still being written can show up a little after a newer one: look back a couple of minutes)
        var mark = maxOf(watermark, look.newest - LOOK_BACK_SECONDS)
        if (oldestFailed != null) mark = minOf(mark, oldestFailed)
        handled = look.seen.filter { (id, added) -> added >= mark && id !in failedIds }.keys
        watermark = mark
    }

    /** The rows of DCIM/Camera not looked at yet that were taken after [UploadSettings.since]. */
    fun lookAtCamera(context: Context, settings: UploadSettings): Look {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN)
        val handled = settings.handled
        val found = mutableListOf<CameraRow>()
        val seen = mutableMapOf<String, Long>()
        var newest = settings.watermark
        context.contentResolver.query(
            collection, columns, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf("DCIM/Camera%", settings.watermark.toString()), "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val rowId = c.getLong(id)
                val addedAt = c.getLong(added)
                seen[rowId.toString()] = addedAt
                newest = maxOf(newest, addedAt)
                if (rowId.toString() in handled) continue
                // taken after the switch was turned on (a photo with no date taken counts from when it was added)
                val takenAt = c.getLong(taken).takeIf { it > 0 } ?: (addedAt * 1000)
                if (takenAt <= settings.since) continue
                found += CameraRow(rowId, android.content.ContentUris.withAppendedId(collection, rowId), addedAt, takenAt)
            }
        }
        return Look(found, seen, newest)
    }

    /**
     * Copy the photo at [uri] to a file for the queue and work out the day it was taken. A name that cannot be sent (HEIC, other types)
     * is not copied: an empty file stands in, and the queue counts it as skipped.
     */
    fun stage(context: Context, session: MobileSession, uri: Uri, takenHint: Long = 0): UploadPhoto {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val name = withExtension(displayName(context, uri) ?: "photo", mime)
        val dir = File(session.cloudUploadDir())
        val dest = File(dir, "copy-${System.nanoTime()}")
        if (cloudUploadKind(name) != UploadKind.SEND) {
            dest.createNewFile()
            return UploadPhoto(dest.absolutePath, name, dayOf(System.currentTimeMillis()), 0L)
        }
        try {
            resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: error("cannot open $uri")
            val taken = exifTaken(dest) ?: takenHint.takeIf { it > 0 } ?: mediaStoreTaken(context, uri) ?: System.currentTimeMillis()
            return UploadPhoto(dest.absolutePath, name, exifDay(dest) ?: dayOf(taken), taken)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** A name with the extension its type implies when it has none. */
    fun withExtension(name: String, mime: String): String {
        if (name.substringAfterLast('.', "").isNotEmpty()) return name
        val ext = when (mime.lowercase(Locale.ROOT)) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/tiff" -> "tif"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/x-adobe-dng", "image/dng" -> "dng"
            else -> return name
        }
        return "$name.$ext"
    }

    /** EXIF DateTimeOriginal as local wall-clock time `yyyy:MM:dd HH:mm:ss`, or null. */
    private fun exifString(file: File): String? =
        runCatching { ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) }.getOrNull()?.takeIf { it.length >= 19 && !it.startsWith("0000") }

    private fun exifTaken(file: File): Long? =
        exifString(file)?.let { runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it.take(19))?.time }.getOrNull() }

    /** The camera's own date (the digits of EXIF DateTimeOriginal): the day it was taken where it was taken. */
    private fun exifDay(file: File): String? = exifString(file)?.let { dayFromExif(it) }

    /** `2026:03:14 10:15:00` is `2026-03-14`; null when it is not a date. */
    fun dayFromExif(s: String): String? =
        Regex("""(\d{4}):(\d{2}):(\d{2})""").find(s)?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
            ?.takeIf { it.substring(5, 7).toInt() in 1..12 && it.substring(8, 10).toInt() in 1..31 }

    /** MediaStore's date taken (or, without that, modified) of a picked photo, ms; null when the picker's URI does not say. */
    private fun mediaStoreTaken(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            c.getLong(0).takeIf { it > 0 } ?: c.getLong(1).takeIf { it > 0 }?.let { it * 1000 }
        }
    }.getOrNull()

    /** The local date of [ms] as `yyyy-MM-dd`. */
    fun dayOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    private const val LOOK_BACK_SECONDS = 120L
    private const val MAX_HANDLED = 2000
}
