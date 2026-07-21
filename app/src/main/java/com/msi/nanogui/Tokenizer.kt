package com.msi.nanogui

import android.content.Context
import java.util.regex.Pattern

enum class TokenType {
    KEYWORD, STRING, COMMENT, PLAIN
}

data class Token(
    val start: Int,
    val end: Int,
    val type: TokenType
)

class Tokenizer(private val context: Context, private val extension: String) {
    private val patternCache = HashMap<String, Triple<Pattern?, Pattern?, Pattern?>>()

    private fun getPatternsForExtension(ext: String): Triple<Pattern?, Pattern?, Pattern?> {
        val cached = patternCache[ext]
        if (cached != null) return cached

        val engine = EditorEngine()
        var commentPat: Pattern? = null
        var stringPat: Pattern? = null
        var keywordPat: Pattern? = null

        // Try getting patterns using current Raw EditorEngine resources method safely
        try {
            val identifier = context.resources.getIdentifier("syntax_${ext.lowercase()}", "raw", context.packageName)
            if (identifier != 0) {
                val inputStream = context.resources.openRawResource(identifier)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
                val jsonStr = reader.use { it.readText() }
                val json = org.json.JSONObject(jsonStr)

                val kw = json.optString("keyword_pattern", "")
                keywordPat = if (kw.isNotEmpty()) Pattern.compile(kw) else null

                val str = json.optString("string_pattern", "")
                stringPat = if (str.isNotEmpty()) Pattern.compile(str) else null

                val com = json.optString("comment_pattern", "")
                commentPat = if (com.isNotEmpty()) Pattern.compile(com) else null
            } else {
                commentPat = Pattern.compile("#.*|//.*")
                keywordPat = Pattern.compile("\\b(if|else|for|while|return|val|var|fun|class|interface|import|package|fun|return|true|false|null)\\b")
                stringPat = Pattern.compile("\"[^\"]*\"|'[^']*'")
            }
        } catch (e: Exception) {
            commentPat = Pattern.compile("#.*|//.*")
            keywordPat = Pattern.compile("\\b(if|else|for|while|return|val|var|fun|class|interface|import|package|fun|return|true|false|null)\\b")
            stringPat = Pattern.compile("\"[^\"]*\"|'[^']*'")
        }

        val triple = Triple(keywordPat, stringPat, commentPat)
        patternCache[ext] = triple
        return triple
    }

    fun tokenize(line: String): List<Token> {
        val tokens = ArrayList<Token>()
        if (line.isEmpty()) return tokens

        val startTime = System.currentTimeMillis()
        val timeoutMs = 50L // 50ms timeout rule to prevent ANR on large non-newline lines

        val isPlain = BooleanArray(line.length) { true }
        val (keywordPattern, stringPattern, commentPattern) = getPatternsForExtension(extension)

        // 1. Comments
        commentPattern?.let { pat ->
            try {
                val matcher = pat.matcher(line)
                while (matcher.find()) {
                    if (System.currentTimeMillis() - startTime > timeoutMs) return emptyList()
                    val start = matcher.start()
                    val end = matcher.end()
                    for (i in start until end) {
                        isPlain[i] = false
                    }
                    tokens.add(Token(start, end, TokenType.COMMENT))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Strings
        if (extension != "json") {
            stringPattern?.let { pat ->
                try {
                    val matcher = pat.matcher(line)
                    while (matcher.find()) {
                        if (System.currentTimeMillis() - startTime > timeoutMs) return emptyList()
                        val start = matcher.start()
                        val end = matcher.end()
                        var overlap = false
                        for (i in start until end) {
                            if (!isPlain[i]) {
                                overlap = true
                                break
                            }
                        }
                        if (!overlap) {
                            for (i in start until end) {
                                isPlain[i] = false
                            }
                            tokens.add(Token(start, end, TokenType.STRING))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 3. Keywords
        keywordPattern?.let { pat ->
            try {
                val matcher = pat.matcher(line)
                while (matcher.find()) {
                    if (System.currentTimeMillis() - startTime > timeoutMs) return emptyList()
                    val start = matcher.start()
                    val end = matcher.end()
                    var overlap = false
                    for (i in start until end) {
                        if (!isPlain[i]) {
                            overlap = true
                            break
                        }
                    }
                    if (!overlap) {
                        for (i in start until end) {
                            isPlain[i] = false
                        }
                        tokens.add(Token(start, end, TokenType.KEYWORD))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return tokens
    }
}
