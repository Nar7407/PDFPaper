package com.pdfpaper.engine.stamp

/** The JSON values a custom stamp list can contain (RFC 8259). */
internal sealed interface JsonValue {
    class Obj(val entries: Map<String, JsonValue>) : JsonValue
    class Arr(val items: List<JsonValue>) : JsonValue
    class Str(val value: String) : JsonValue
    class Num(val value: Double) : JsonValue
    class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/**
 * A small strict JSON reader for the custom stamp list (task T23); the engine
 * adds no dependency for a format it defines itself. [parse] never throws —
 * malformed input is a failed [Result] with the offset that stopped it.
 */
internal object MiniJson {

    private const val MAX_DEPTH = 64

    fun parse(text: String): Result<JsonValue> = runCatching {
        val reader = Reader(text)
        val value = reader.value(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("unexpected trailing content")
        value
    }

    private class Reader(private val text: String) {

        private var at = 0

        fun atEnd(): Boolean = at >= text.length

        fun skipWhitespace() {
            while (at < text.length && text[at] in " \t\n\r") at++
        }

        fun fail(message: String): Nothing = error("invalid JSON at offset $at: $message")

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            val c = text[at]
            return when {
                c == '{' -> obj(depth)
                c == '[' -> arr(depth)
                c == '"' -> JsonValue.Str(string())
                c == 't' -> {
                    literal("true")
                    JsonValue.Bool(true)
                }
                c == 'f' -> {
                    literal("false")
                    JsonValue.Bool(false)
                }
                c == 'n' -> {
                    literal("null")
                    JsonValue.Null
                }
                c == '-' || c.isDigit() -> JsonValue.Num(number())
                else -> fail("unexpected character '$c'")
            }
        }

        private fun obj(depth: Int): JsonValue.Obj {
            at++ // '{'
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek('}')) {
                at++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[at] != '"') fail("expected a member name")
                val key = string()
                expect(':')
                entries[key] = value(depth + 1)
                skipWhitespace()
                when {
                    atEnd() -> fail("unterminated object")
                    peek(',') -> at++
                    peek('}') -> {
                        at++
                        return JsonValue.Obj(entries)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): JsonValue.Arr {
            at++ // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek(']')) {
                at++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += value(depth + 1)
                skipWhitespace()
                when {
                    atEnd() -> fail("unterminated array")
                    peek(',') -> at++
                    peek(']') -> {
                        at++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            at++ // '"'
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(escape())
                    c.code < 0x20 -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            if (atEnd()) fail("unterminated escape")
            return when (val c = text[at++]) {
                '"', '\\', '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicode()
                else -> fail("unknown escape '\\$c'")
            }
        }

        private fun unicode(): Char {
            var code = 0
            repeat(4) {
                if (atEnd()) fail("truncated \\u escape")
                val digit = Character.digit(text[at++], 16)
                if (digit < 0) fail("bad \\u escape")
                code = code * 16 + digit
            }
            return code.toChar()
        }

        private fun number(): Double {
            val start = at
            if (peek('-')) at++
            if (atEnd() || !text[at].isDigit()) fail("invalid number")
            while (at < text.length && text[at].isDigit()) at++
            if (peek('.')) {
                at++
                if (atEnd() || !text[at].isDigit()) fail("invalid number")
                while (at < text.length && text[at].isDigit()) at++
            }
            if (peek('e') || peek('E')) {
                at++
                if (peek('+') || peek('-')) at++
                if (atEnd() || !text[at].isDigit()) fail("invalid number")
                while (at < text.length && text[at].isDigit()) at++
            }
            val token = text.substring(start, at)
            val value = token.toDoubleOrNull() ?: fail("invalid number '$token'")
            if (!value.isFinite()) fail("number '$token' is out of range")
            return value
        }

        private fun literal(expected: String) {
            if (!text.startsWith(expected, at)) fail("invalid literal")
            at += expected.length
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (atEnd() || text[at] != c) fail("expected '$c'")
            at++
        }

        private fun peek(c: Char): Boolean = at < text.length && text[at] == c
    }
}
