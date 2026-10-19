package com.pdfpaper.engine.tools

import com.pdfpaper.engine.annot.AnnotationSpec
import com.pdfpaper.engine.annot.CommentStatus
import com.pdfpaper.engine.annot.HighlightSpec
import com.pdfpaper.engine.annot.NoteSpec
import com.pdfpaper.engine.annot.PdfComment
import com.pdfpaper.engine.annot.PdfComments
import com.pdfpaper.engine.annot.PdfDate
import com.pdfpaper.engine.annot.ReplySpec
import com.pdfpaper.engine.annot.ReplyType
import com.pdfpaper.engine.annot.StatusSpec
import com.pdfpaper.engine.annot.UnderlineSpec
import com.pdfpaper.engine.annot.comments
import com.pdfpaper.engine.geom.PdfRect
import com.pdfpaper.engine.graphics.PdfColor
import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Comment CLI (task T22).
 *
 * Usage:
 *   cli comments list <file.pdf>
 *   cli comments add <file.pdf> --page N --type note|highlight|underline --text <s>
 *       [--at x,y] [--rect l,b,r,t ...] [--author a] [--subject s]
 *       [--color r,g,b] [--open] --out out.pdf
 *   cli comments reply <file.pdf> --to ID --text <s> [--author a] [--subject s]
 *       [--color r,g,b] [--group] --out out.pdf
 *   cli comments set-status <file.pdf> --id ID --status accepted|rejected|cancelled|completed|resolved|none
 *       [--author a] --out out.pdf
 *
 * Coordinates are PDF user-space points from the bottom-left of the page, the
 * same space `comments list` prints. IDs are annotation object numbers shown
 * by `comments list`; they change when a file is saved. `--text` takes the
 * rest of the words up to the next `--option`, so it does not need quoting;
 * quote it anyway when it contains `--`. `--color` accepts 0..1 or 0..255
 * components. `resolved` is accepted as a synonym for `completed`, Acrobat
 * DC's label for the Completed review state.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "comments") {
        printUsage()
        kotlin.system.exitProcess(2)
    }
    val status = when (args.getOrNull(1)) {
        "list" -> list(args.drop(2))
        "add" -> add(args.drop(2))
        "reply" -> reply(args.drop(2))
        "set-status" -> setStatus(args.drop(2))
        else -> failUsage("use 'comments list', 'comments add', 'comments reply' or 'comments set-status'")
    }
    kotlin.system.exitProcess(status)
}

// -- Commands -----------------------------------------------------------------

private fun list(args: List<String>): Int = withParsed(args) { parsed ->
    val document = open(parsed.requireFile(0))
    val threads = document.comments()
    val count = printThreads(threads)
    if (count == 0) println("No comments") else println("$count comment(s)")
    0
}

private fun add(args: List<String>): Int = withParsed(args) { parsed ->
    val document = open(parsed.requireFile(0))
    val pageNumber = parsed.requireInt("page")
    val pageIndex = pageNumber - 1
    val pages = document.pages()
    if (pageIndex !in pages.indices) {
        fail("page $pageNumber is out of range (1..${pages.size})")
    }
    val type = parsed.requireOption("type").lowercase()
    val contents = parsed.requireOption("text")
    val author = parsed.option("author")
    val subject = parsed.option("subject")
    val color = parsed.option("color")?.let(::parseColor)
    val open = parsed.flag("open")
    val spec: AnnotationSpec = when (type) {
        "note" -> {
            val at = parsed.option("at")?.let(::parsePoint)
                ?: (pages[pageIndex].cropBox?.let { PdfRect.of(it) } ?: PdfRect(0.0, 0.0, 612.0, 792.0))
                    .let { box -> box.left + 24.0 to box.top - 24.0 }
            NoteSpec(
                pageIndex = pageIndex,
                rect = PdfRect(at.first, at.second - NOTE_SIZE, at.first + NOTE_SIZE, at.second),
                contents = contents,
                color = color ?: NoteSpec.DEFAULT_NOTE_COLOR,
                title = author,
                open = open,
                subject = subject,
                popup = true
            )
        }
        "highlight", "underline" -> {
            val rects = parsed.optionValues("rect").map(::parseRect)
            if (rects.isEmpty()) {
                failUsage("--rect l,b,r,t is required for a $type (repeat it for several lines)")
            }
            if (type == "highlight") {
                HighlightSpec(
                    pageIndex = pageIndex,
                    rects = rects,
                    color = color ?: HighlightSpec.DEFAULT_HIGHLIGHT_COLOR,
                    contents = contents,
                    title = author,
                    subject = subject,
                    open = open,
                    popup = true
                )
            } else {
                UnderlineSpec(
                    pageIndex = pageIndex,
                    rects = rects,
                    color = color ?: UnderlineSpec.DEFAULT_UNDERLINE_COLOR,
                    contents = contents,
                    title = author,
                    subject = subject,
                    open = open,
                    popup = true
                )
            }
        }
        else -> failUsage("unknown type '$type' (use note, highlight or underline)")
    }
    save(document, parsed, listOf(spec))
}

private fun reply(args: List<String>): Int = withParsed(args) { parsed ->
    val document = open(parsed.requireFile(0))
    val targetId = parsed.requireInt("to")
    val target = findComment(document, targetId)
    val spec = ReplySpec(
        pageIndex = target.pageIndex,
        targetObjNum = targetId,
        contents = parsed.requireOption("text"),
        title = parsed.option("author"),
        subject = parsed.option("subject"),
        color = parsed.option("color")?.let(::parseColor),
        replyType = if (parsed.flag("group")) ReplyType.GROUP else ReplyType.REPLY
    )
    save(document, parsed, listOf(spec))
}

private fun setStatus(args: List<String>): Int = withParsed(args) { parsed ->
    val document = open(parsed.requireFile(0))
    val targetId = parsed.requireInt("id")
    val target = findComment(document, targetId)
    val spec = StatusSpec(
        pageIndex = target.pageIndex,
        targetObjNum = targetId,
        status = statusOf(parsed.requireOption("status")),
        title = parsed.option("author")
    )
    save(document, parsed, listOf(spec))
}

// -- Output -------------------------------------------------------------------

/** Returns the number of comments printed, replies included. */
private fun printThreads(threads: List<PdfComment>): Int {
    var count = 0
    val stack = ArrayDeque<Pair<PdfComment, Int>>()
    for (thread in threads.asReversed()) stack.addLast(thread to 0)
    while (stack.isNotEmpty()) {
        val (comment, depth) = stack.removeLast()
        println(line(comment, depth))
        count++
        for (reply in comment.replies.asReversed()) stack.addLast(reply to depth + 1)
    }
    return count
}

private fun line(comment: PdfComment, depth: Int): String {
    val indent = "  ".repeat(depth)
    val rect = comment.rect
        ?.let { "[${number(it.left)},${number(it.bottom)},${number(it.right)},${number(it.top)}]" }
        ?: "-"
    val status = if (comment.resolved && comment.status == CommentStatus.NONE) "resolved"
    else comment.status.name.lowercase()
    return "$indent#${comment.id}\tpage ${comment.pageIndex + 1}\t$rect\t${comment.type}\t" +
        "${comment.author ?: "-"}\t${date(comment)}\t$status\t${comment.inReplyToId ?: "-"}\t" +
        comment.contents.orEmpty()
}

private fun date(comment: PdfComment): String {
    val raw = comment.created ?: comment.modified ?: return "-"
    val millis = PdfDate.millis(raw) ?: return raw
    return DATE_FORMAT.format(Instant.ofEpochMilli(millis))
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private const val NOTE_SIZE = 20.0

private fun open(file: File): PdfDocument =
    PdfDocument.open(FileSource(file)).getOrElse { error ->
        System.err.println("Could not open ${file.absolutePath}: ${error.message ?: error::class.simpleName}")
        kotlin.system.exitProcess(1)
    }

private fun findComment(document: PdfDocument, id: Int): PdfComment {
    val queue = ArrayDeque(document.comments())
    while (queue.isNotEmpty()) {
        val comment = queue.removeFirst()
        if (comment.id == id) return comment
        queue.addAll(comment.replies)
    }
    fail("no comment with id $id (run 'comments list' first)")
}

private fun save(document: PdfDocument, parsed: CommentArguments, specs: List<AnnotationSpec>): Int {
    val out = File(parsed.option("out") ?: "out.pdf")
    val parent = out.absoluteFile.parentFile
    if (parent != null && !parent.isDirectory) failUsage("output directory does not exist: ${parent.path}")
    val data = PdfComments.edit(document, specs).getOrElse { error ->
        fail(error.message ?: error::class.simpleName ?: "save failed")
    }
    out.writeBytes(data)
    println("Wrote ${specs.size} comment change(s) to ${out.absolutePath}")
    return 0
}

private fun number(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else com.pdfpaper.engine.write.PdfSerializer.formatReal(value)

// -- Argument parsing ---------------------------------------------------------

/** Options that take no value. */
private val FLAGS = setOf("open", "group")

private class CommentArguments(
    val positionals: List<String>,
    private val options: Map<String, List<String>>
) {
    fun requireFile(index: Int): File = File(requirePositional(index, "file"))

    fun requirePositional(index: Int, name: String): String =
        positionals.getOrNull(index) ?: failUsage("missing <$name>")

    fun option(name: String): String? = options[name]?.lastOrNull()

    fun optionValues(name: String): List<String> = options[name].orEmpty()

    fun requireOption(name: String): String = option(name) ?: failUsage("--$name is required")

    fun requireInt(name: String): Int =
        requireOption(name).toIntOrNull() ?: failUsage("--$name must be an integer")

    fun flag(name: String): Boolean = options.containsKey(name)
}

/**
 * Parses `--key value`, `--key=value`, repeated options (each value is kept)
 * and flags. `--text` swallows the following words up to the next option so a
 * sentence needs no quoting.
 */
private fun withParsed(args: List<String>, block: (CommentArguments) -> Int): Int {
    val positionals = ArrayList<String>()
    val options = LinkedHashMap<String, MutableList<String>>()
    fun add(name: String, value: String) {
        options.getOrPut(name) { ArrayList() } += value
    }
    var i = 0
    while (i < args.size) {
        val token = args[i]
        if (!token.startsWith("--")) {
            positionals += token
            i++
            continue
        }
        val body = token.removePrefix("--")
        val equals = body.indexOf('=')
        when {
            equals >= 0 -> {
                add(body.substring(0, equals), body.substring(equals + 1))
                i++
            }
            body in FLAGS -> {
                add(body, "true")
                i++
            }
            body == "text" -> {
                val text = StringBuilder()
                i++
                while (i < args.size && !args[i].startsWith("--")) {
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(args[i])
                    i++
                }
                if (text.isEmpty()) failUsage("--text needs a value")
                add("text", text.toString())
            }
            else -> {
                val value = args.getOrNull(i + 1) ?: failUsage("--$body needs a value")
                add(body, value)
                i += 2
            }
        }
    }
    return block(CommentArguments(positionals, options))
}

private fun parseNumbers(raw: String, count: Int, what: String): List<Double> {
    val parts = raw.split(',').map { it.trim() }
    if (parts.size != count) failUsage("$what expects $count comma-separated numbers")
    return parts.map { it.toDoubleOrNull() ?: failUsage("$what expects numbers") }
}

private fun parsePoint(raw: String): Pair<Double, Double> {
    val values = parseNumbers(raw, 2, "--at")
    return values[0] to values[1]
}

private fun parseRect(raw: String): PdfRect {
    val values = parseNumbers(raw, 4, "--rect")
    return PdfRect(values[0], values[1], values[2], values[3])
}

private fun parseColor(value: String): PdfColor {
    val components = parseNumbers(value, 3, "--color")
    val scaled = components.map { if (it > 1.0) it / 255.0 else it }
    return PdfColor(scaled[0].coerceIn(0.0, 1.0), scaled[1].coerceIn(0.0, 1.0), scaled[2].coerceIn(0.0, 1.0))
}

private fun statusOf(value: String): CommentStatus = when (value.lowercase()) {
    "accepted" -> CommentStatus.ACCEPTED
    "rejected" -> CommentStatus.REJECTED
    "cancelled", "canceled" -> CommentStatus.CANCELLED
    "completed", "resolved" -> CommentStatus.COMPLETED
    "none" -> CommentStatus.NONE
    else -> failUsage(
        "unknown status '$value' (use accepted, rejected, cancelled, completed, resolved or none)"
    )
}

private fun printUsage() {
    System.err.println(
        """
        Usage:
          cli comments list <file.pdf>
          cli comments add <file.pdf> --page N --type note|highlight|underline --text <s>
              [--at x,y] [--rect l,b,r,t ...] [--author a] [--subject s]
              [--color r,g,b] [--open] --out out.pdf
          cli comments reply <file.pdf> --to ID --text <s> [--author a] [--subject s]
              [--color r,g,b] [--group] --out out.pdf
          cli comments set-status <file.pdf> --id ID
              --status accepted|rejected|cancelled|completed|resolved|none
              [--author a] --out out.pdf

        Coordinates are PDF points from the bottom-left of the page. IDs are the
        object numbers printed by 'comments list'. --text takes the words up to
        the next --option. --color accepts 0..1 or 0..255 components. 'resolved'
        is Acrobat DC's label for the Completed review state.
        """.trimIndent()
    )
}

private fun failUsage(message: String): Nothing {
    System.err.println("Error: $message")
    printUsage()
    kotlin.system.exitProcess(2)
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    kotlin.system.exitProcess(1)
}
