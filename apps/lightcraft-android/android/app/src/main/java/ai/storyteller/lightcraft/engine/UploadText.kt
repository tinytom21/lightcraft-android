package ai.storyteller.lightcraft.engine

import ai.storyteller.lightcraft.core.UploadState

/** What the Cloud library page says about the photos sent to the home PC (docs/cloud-library.md, "Send photos to the PC"). */
object UploadText {
    /** The lines of the status, in order; empty when there is nothing to say. [rejected] lines are errors, the rest information. */
    fun lines(s: UploadState, wifiHold: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val total = s.total.toInt()
        // photos found automatically wait for Wi-Fi when the network is metered and the person asked for Wi-Fi only
        val held = if (wifiHold) s.automaticWaiting.toInt() else 0
        val waiting = s.waiting.toInt() - held
        val awaiting = s.awaitingHome.toInt()
        val done = s.added.toInt() + s.duplicate.toInt() + s.rejected.size
        when {
            waiting > 0 -> out += "Sending ${total - s.waiting.toInt() + 1} of $total…" + bytes(s)
            awaiting > 0 -> out += (if (done + awaiting == 1) "1 photo sent" else "${done + awaiting - s.rejected.size} photos sent") +
                ". The home PC adds ${if (awaiting == 1) "it" else "them"} when it next synchronizes."
            s.added.toInt() + s.duplicate.toInt() > 0 -> out += "${s.added.toInt() + s.duplicate.toInt()} sent" +
                (if (s.duplicate.toInt() > 0) ", ${s.duplicate} already on the home PC" else "")
        }
        if (held > 0) out += "$held waiting for Wi-Fi"
        if (s.known.toInt() > 0) out += "${s.known} already in your library, not sent"
        for (r in s.rejected) out += "${r.name} was not added by the home PC: ${r.reason}"
        if (s.skippedHeic.toInt() > 0) {
            val n = s.skippedHeic.toInt()
            out += "$n HEIC photo${if (n == 1) " was" else "s were"} not sent: LightCraft cannot read HEIC yet."
        }
        if (s.skippedOther.toInt() > 0) {
            val n = s.skippedOther.toInt()
            out += "$n photo${if (n == 1) " was" else "s were"} not sent: the home PC cannot take that file type."
        }
        return out
    }

    /** Whether the status is only history (nothing is being sent or waiting for the PC): it can be dismissed. */
    fun finished(s: UploadState): Boolean = s.waiting.toInt() == 0 && s.awaitingHome.toInt() == 0 && lines(s).isNotEmpty()

    private fun bytes(s: UploadState): String =
        if (s.bytesTotal == 0uL) "" else " ${size(s.bytesDone.toLong())} of ${size(s.bytesTotal.toLong())}"

    fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        else -> "${(b + 1023) / 1024} KB"
    }
}
