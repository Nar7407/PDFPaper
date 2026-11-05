package com.pdfpaper.app.ui.viewer.speech

import com.pdfpaper.engine.speech.ChapterSource
import com.pdfpaper.engine.speech.SpeechChapter
import com.pdfpaper.engine.speech.SpeechPage
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import kotlin.math.abs

/**
 * Where read aloud is (T34): a page, a paragraph on it, a word inside the
 * paragraph and the chapter the reader is in. The word index is only a hint for
 * the highlight; speech itself is always requested a paragraph at a time.
 */
data class SpeechPosition(
    val pageIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val wordIndex: Int = 0,
    val chapterIndex: Int = 0
)

/** The reading speeds the bar offers, shared by the viewer and the service. */
object SpeechSpeed {

    val PRESETS: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** The preset closest to [value]; a NaN or a negative value becomes 1x. */
    fun nearest(value: Float): Float {
        if (!value.isFinite() || value <= 0f) return 1f
        return PRESETS.minByOrNull { abs(it - value) } ?: 1f
    }

    /** "0.75x", "1x", "1.25x" — a whole speed has no decimal point. */
    fun label(value: Float): String {
        val speed = nearest(value)
        val text = if (speed == speed.toInt().toFloat()) {
            speed.toInt().toString()
        } else {
            speed.toString()
        }
        return "${text}x"
    }
}

/**
 * How long read aloud keeps going. A countdown is a fixed number of minutes; the
 * chapter option is not a clock at all and is handled where a chapter boundary
 * is crossed.
 */
enum class SleepOption(val minutes: Int?) {
    OFF(null),
    END_OF_CHAPTER(null),
    MINUTES_15(15),
    MINUTES_30(30),
    MINUTES_60(60);

    fun label(): String = when (this) {
        OFF -> "Off"
        END_OF_CHAPTER -> "End of chapter"
        MINUTES_15 -> "15 minutes"
        MINUTES_30 -> "30 minutes"
        MINUTES_60 -> "60 minutes"
    }
}

/** A running sleep choice: [option] and the moment it started. */
data class SleepTimer(val option: SleepOption, val startedAtMillis: Long) {

    /** Milliseconds left for a countdown; null for [SleepOption.OFF] and the chapter option. */
    fun remainingMillis(now: Long): Long? {
        val minutes = option.minutes ?: return null
        val end = startedAtMillis + minutes * 60_000L
        return (end - now).coerceAtLeast(0L)
    }

    fun isExpired(now: Long): Boolean {
        val remaining = remainingMillis(now) ?: return false
        return remaining <= 0L
    }

    /** "14:00 left", or the option's own label when it is not a countdown. */
    fun label(now: Long): String {
        val remaining = remainingMillis(now) ?: return option.label()
        val totalSeconds = (remaining + 999L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d left".format(minutes, seconds)
    }
}

/**
 * The chapter list read aloud walks, in document order. [source] is null when
 * there are no chapters at all, which is what lets the UI say "no chapters"
 * instead of pretending "next chapter" did something.
 */
class SpeechChapterList(
    val chapters: List<SpeechChapter>,
    val source: ChapterSource?
) {
    val isEmpty: Boolean get() = chapters.isEmpty()

    /** The chapter whose start is at or before ([pageIndex], [paragraphIndex]). */
    fun chapterAt(pageIndex: Int, paragraphIndex: Int): SpeechChapter? {
        var found: SpeechChapter? = null
        for (chapter in chapters) {
            if (compare(chapter.pageIndex, chapter.paragraphIndex, pageIndex, paragraphIndex) <= 0) {
                found = chapter
            } else {
                break
            }
        }
        return found
    }

    /** The first chapter that starts strictly after the position. */
    fun next(pageIndex: Int, paragraphIndex: Int): SpeechChapter? =
        chapters.firstOrNull {
            compare(it.pageIndex, it.paragraphIndex, pageIndex, paragraphIndex) > 0
        }

    /** The last chapter that starts strictly before the position. */
    fun previous(pageIndex: Int, paragraphIndex: Int): SpeechChapter? =
        chapters.lastOrNull {
            compare(it.pageIndex, it.paragraphIndex, pageIndex, paragraphIndex) < 0
        }

    fun indexOf(chapter: SpeechChapter): Int = chapters.indexOf(chapter)

    private fun compare(
        firstPage: Int,
        firstParagraph: Int,
        secondPage: Int,
        secondParagraph: Int
    ): Int {
        val byPage = firstPage.compareTo(secondPage)
        return if (byPage != 0) byPage else firstParagraph.compareTo(secondParagraph)
    }
}

/**
 * The paragraph cursor. It answers "which paragraph comes next" without reading
 * anything, so the controller can request a page before it exists; a page that
 * turns out to have no text is reported and skipped by the controller, not
 * silently stepped over here.
 */
object SpeechCursors {

    fun nextParagraph(
        position: SpeechPosition,
        current: SpeechPage?,
        next: SpeechPage?,
        pageCount: Int
    ): SpeechPosition? {
        val count = current?.paragraphs?.size ?: 0
        if (position.paragraphIndex + 1 < count) {
            return position.copy(paragraphIndex = position.paragraphIndex + 1, wordIndex = 0)
        }
        val hasNextPage = if (pageCount > 0) {
            position.pageIndex + 1 < pageCount
        } else {
            next != null
        }
        if (!hasNextPage) return null
        return SpeechPosition(
            pageIndex = position.pageIndex + 1,
            paragraphIndex = 0,
            wordIndex = 0,
            chapterIndex = position.chapterIndex
        )
    }

    fun previousParagraph(
        position: SpeechPosition,
        current: SpeechPage?,
        previous: SpeechPage?
    ): SpeechPosition? {
        if (position.paragraphIndex > 0) {
            return position.copy(paragraphIndex = position.paragraphIndex - 1, wordIndex = 0)
        }
        val before = previous ?: return null
        val last = before.paragraphs.size - 1
        if (last < 0) return null
        return SpeechPosition(
            pageIndex = position.pageIndex - 1,
            paragraphIndex = last,
            wordIndex = 0,
            chapterIndex = position.chapterIndex
        )
    }
}

/**
 * The voice the reader picked, stored so the same voice is used next time. A
 * voice name may contain any character, so the three parts are Base64-URL
 * encoded as one field (the T38b `ReadingStateCodec` rule).
 */
data class SpeechVoiceKey(val engine: String, val locale: String, val name: String) {

    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(
                listOf(engine, locale, name).joinToString("|") { escape(it) }.toByteArray(UTF_8)
            )

    companion object {
        fun decode(raw: String?): SpeechVoiceKey? {
            if (raw.isNullOrBlank()) return null
            val text = runCatching {
                String(Base64.getUrlDecoder().decode(raw), UTF_8)
            }.getOrNull() ?: return null
            val parts = splitEscaped(text) ?: return null
            if (parts.size != 3) return null
            return SpeechVoiceKey(parts[0], parts[1], parts[2])
        }

        private fun escape(part: String): String = buildString {
            for (ch in part) {
                if (ch == '\\' || ch == '|') append('\\')
                append(ch)
            }
        }

        /** Splits on the unescaped separators; null when a trailing `\` dangles. */
        private fun splitEscaped(text: String): List<String>? {
            val parts = ArrayList<String>(3)
            val current = StringBuilder()
            var i = 0
            while (i < text.length) {
                when (val ch = text[i]) {
                    '\\' -> {
                        if (i + 1 >= text.length) return null
                        current.append(text[i + 1])
                        i += 2
                    }
                    '|' -> {
                        parts.add(current.toString())
                        current.clear()
                        i++
                    }
                    else -> {
                        current.append(ch)
                        i++
                    }
                }
            }
            parts.add(current.toString())
            return parts
        }
    }
}

/** Everything the bar draws, and what the T64 service mirrors in its notification. */
data class SpeechState(
    val active: Boolean = false,
    val speaking: Boolean = false,
    val position: SpeechPosition = SpeechPosition(0, 0),
    val chapter: SpeechChapter? = null,
    val speed: Float = 1f,
    val sleep: SleepOption = SleepOption.OFF,
    val sleepStartedAt: Long = 0L,
    val paragraphText: String = "",
    /** False once the speech engine has shown it cannot report word ranges. */
    val hasWordRanges: Boolean = true,
    val message: String? = null
)

/**
 * "Page 5 of 10, Two, paragraph 3" — the one-line description under the
 * chapter title. A document without chapters simply has no middle part, and the
 * comma keeps that obvious: nothing is left behind to say "no chapter here".
 */
fun describeSpeech(
    pageIndex: Int,
    pageCount: Int,
    position: SpeechPosition,
    chapter: SpeechChapter?
): String {
    val page = if (pageCount > 0) {
        "Page ${(pageIndex + 1).coerceIn(1, pageCount)} of $pageCount"
    } else {
        "Page ${pageIndex + 1}"
    }
    val paragraph = "paragraph ${position.paragraphIndex + 1}"
    return listOfNotNull(page, chapter?.title, paragraph).joinToString(", ")
}
