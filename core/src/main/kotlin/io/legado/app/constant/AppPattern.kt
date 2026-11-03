package io.legado.app.constant

import java.util.regex.Pattern

@Suppress("RegExpRedundantEscape", "unused")
object AppPattern {
    val JS_PATTERN: Pattern =
        Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE)
    val EXP_PATTERN: Pattern = Pattern.compile("\\{\\{([\\w\\W]*?)\\}\\}")

    val splitGroupRegex = Regex("[,;，；]")
    val rnRegex = Regex("[\\r\\n]")
    val LFRegex = "\n".toRegex()
    val xmlContentTypeRegex = "(application|text)/\\w*\\+?xml.*".toRegex()
    val regexCharRegex = "[{}()\\[\\].+*?^$\\\\|]".toRegex()
    val spaceRegex = "\\s+".toRegex()
}
