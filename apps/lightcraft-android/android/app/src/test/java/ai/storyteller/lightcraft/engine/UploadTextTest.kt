package ai.storyteller.lightcraft.engine

import ai.storyteller.lightcraft.core.UploadRejected
import ai.storyteller.lightcraft.core.UploadState
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Cloud library page's upload status line, and the helpers that name and date the photos that are sent. */
class UploadTextTest {
    private fun state(
        total: UInt = 0u, waiting: UInt = 0u, autoWaiting: UInt = 0u, awaitingHome: UInt = 0u, added: UInt = 0u, duplicate: UInt = 0u, known: UInt = 0u,
        rejected: List<UploadRejected> = emptyList(), heic: UInt = 0u, other: UInt = 0u, done: ULong = 0uL, all: ULong = 0uL,
    ) = UploadState(total, waiting, autoWaiting, awaitingHome, false, added, duplicate, known, rejected, heic, other, done, all)

    @Test
    fun sendingShowsWhereItIs() {
        val s = state(total = 12u, waiting = 10u, awaitingHome = 2u, done = 6_000_000uL, all = 30_000_000uL)
        assertEquals(listOf("Sending 3 of 12… 5.7 MB of 28.6 MB"), UploadText.lines(s))
        assertFalse(UploadText.finished(s))
    }

    @Test
    fun sentPhotosWaitForTheHomePcAndThenAreCounted() {
        assertEquals(listOf("3 photos sent. The home PC adds them when it next synchronizes."), UploadText.lines(state(total = 3u, awaitingHome = 3u)))
        assertEquals(listOf("1 photo sent. The home PC adds it when it next synchronizes."), UploadText.lines(state(total = 1u, awaitingHome = 1u)))
        val done = state(total = 12u, added = 11u, duplicate = 1u)
        assertEquals(listOf("12 sent, 1 already on the home PC"), UploadText.lines(done))
        assertTrue(UploadText.finished(done))
    }

    @Test
    fun heicIsCountedWithTheReason() {
        assertEquals(listOf("2 HEIC photos were not sent: LightCraft cannot read HEIC yet."), UploadText.lines(state(heic = 2u)))
        assertEquals(listOf("1 HEIC photo was not sent: LightCraft cannot read HEIC yet."), UploadText.lines(state(heic = 1u)))
    }

    @Test
    fun refusalsAndKnownPhotosAreListed() {
        val s = state(total = 1u, rejected = listOf(UploadRejected("IMG_1.jpg", "the folder matches an exclude pattern")), known = 2u)
        assertEquals(listOf("2 already in your library, not sent", "IMG_1.jpg was not added by the home PC: the folder matches an exclude pattern"), UploadText.lines(s))
    }

    @Test
    fun automaticPhotosWaitForWifiWhenHeld() {
        val s = state(total = 4u, waiting = 3u, autoWaiting = 3u, awaitingHome = 1u)
        assertEquals(listOf("1 photo sent. The home PC adds it when it next synchronizes.", "3 waiting for Wi-Fi"), UploadText.lines(s, wifiHold = true))
        assertEquals(listOf("Sending 2 of 4…"), UploadText.lines(s, wifiHold = false))
        val mixed = state(total = 4u, waiting = 3u, autoWaiting = 2u, awaitingHome = 1u)
        assertEquals(listOf("Sending 2 of 4…", "2 waiting for Wi-Fi"), UploadText.lines(mixed, wifiHold = true))
    }

    @Test
    fun nothingToSayIsEmpty() {
        assertTrue(UploadText.lines(state()).isEmpty())
        assertFalse(UploadText.finished(state()))
    }

    @Test
    fun extensionsComeFromTheTypeWhenTheNameHasNone() {
        assertEquals("photo.jpg", PhoneUploads.withExtension("photo", "image/jpeg"))
        assertEquals("photo.dng", PhoneUploads.withExtension("photo", "image/x-adobe-dng"))
        assertEquals("IMG.jpg", PhoneUploads.withExtension("IMG.jpg", "image/png"))
        assertEquals("photo", PhoneUploads.withExtension("photo", "application/octet-stream"))
    }

    @Test
    fun exifDatesGiveTheDayTheCameraRecorded() {
        assertEquals("2026-03-14", PhoneUploads.dayFromExif("2026:03:14 23:59:59"))
        assertNull(PhoneUploads.dayFromExif("0000:00:00 00:00:00"))
        assertNull(PhoneUploads.dayFromExif("garbage"))
    }

    @Test
    fun theDayIsTheLocalOne() {
        // 23:30 UTC on 14 July is already the 15th in Lisbon (summer time) and still the 14th in New York
        val ms = java.time.Instant.parse("2026-07-14T23:30:00Z").toEpochMilli()
        assertEquals("2026-07-15", PhoneUploads.dayOf(ms, ZoneId.of("Europe/Lisbon")))
        assertEquals("2026-07-14", PhoneUploads.dayOf(ms, ZoneId.of("America/New_York")))
    }

    @Test
    fun theScanLineSaysWhenAndWhy() {
        assertEquals("Last checked for new camera photos: 5 minutes ago, found 2.", UploadText.scanLine("5 minutes ago", 2, null))
        assertEquals("Last checked for new camera photos: now. Not looked: no access to all photos.", UploadText.scanLine("now", 0, "no access to all photos"))
        assertTrue(UploadText.scanLine(null, 0, null).startsWith("Not checked"))
    }

    private val since = 1_800_000_000_000L // ms

    @Test
    fun aPhotoTakenInTheMinuteAfterSwitchingOnIsNew() {
        val taken = since + 30_000
        assertTrue(PhoneUploads.isNew(taken, taken / 1000 + 1, since))
        assertTrue(PhoneUploads.isNew(since + 1, since / 1000, since))
    }

    @Test
    fun olderPhotosAreNotNew() {
        // taken and added before the switch
        assertFalse(PhoneUploads.isNew(since - 600_000, since / 1000 - 590, since))
        // an old photo copied into the folder after the switch keeps its old date taken
        assertFalse(PhoneUploads.isNew(since - 30L * 86_400_000, since / 1000 + 60, since))
    }

    @Test
    fun aDateTakenShiftedByTheTimeZoneStillCounts() {
        // the camera's local time (UTC+1) stored as if it were UTC is an hour early
        val taken = since + 20_000 - 3_600_000
        assertTrue(PhoneUploads.isNew(taken, (since + 20_000) / 1000, since))
    }

    @Test
    fun withoutADateTakenTheAddedTimeCounts() {
        assertEquals(1_234_000L, PhoneUploads.effectiveTaken(0, 1234))
        assertEquals(99L, PhoneUploads.effectiveTaken(99, 1234))
        assertTrue(PhoneUploads.isNew(0, since / 1000 + 5, since))
        assertFalse(PhoneUploads.isNew(0, since / 1000 - 3600, since))
    }

    @Test
    fun theMarkLooksBackAndNeverPassesAFailedPhoto() {
        assertEquals(880L, PhoneUploads.nextMark(100, 1000, null))
        assertEquals(100L, PhoneUploads.nextMark(100, 150, null))
        assertEquals(500L, PhoneUploads.nextMark(100, 1000, 500))
    }
}
