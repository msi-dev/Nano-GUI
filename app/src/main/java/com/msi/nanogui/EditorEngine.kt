package com.msi.nanogui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

class EditorEngine {
    val textState = MutableStateFlow("")
    
    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()
    
    var cutBuffer: String = ""
        private set
        
    private val patternCache = HashMap<String, Triple<Pattern?, Pattern?, Pattern?>>()
    private val colorCache = HashMap<String, Triple<Color, Color, Color>>()

    private fun loadSyntaxFromResource(context: Context, ext: String): JSONObject? {
        val extension = ext.lowercase()
        try {
            val resourceId = context.resources.getIdentifier("syntax_$extension", "raw", context.packageName)
            if (resourceId != 0) {
                val inputStream = context.resources.openRawResource(resourceId)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val jsonStr = reader.use { it.readText() }
                return JSONObject(jsonStr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun getPatternsForExtension(context: Context, ext: String): Triple<Pattern?, Pattern?, Pattern?> {
        val extension = ext.lowercase()
        val cached = patternCache[extension]
        if (cached != null) return cached

        val json = loadSyntaxFromResource(context, extension)
        var commentPattern: Pattern? = null
        var stringPattern: Pattern? = null
        var keywordPattern: Pattern? = null

        if (json != null) {
            val kwPat = json.optString("keyword_pattern", "")
            if (kwPat.isNotEmpty()) {
                keywordPattern = Pattern.compile(kwPat)
            }
            val strPat = json.optString("string_pattern", "")
            if (strPat.isNotEmpty()) {
                stringPattern = Pattern.compile(strPat)
            }
            val comPat = json.optString("comment_pattern", "")
            if (comPat.isNotEmpty()) {
                commentPattern = Pattern.compile(comPat)
            }
        } else {
            // Default logic fallback if no JSON found
            commentPattern = Pattern.compile("#.*|//.*")
            keywordPattern = Pattern.compile("\\b(if|else|for|while|return)\\b")
            stringPattern = Pattern.compile("\"[^\"]*\"|'[^']*'")
        }

        val triple = Triple(keywordPattern, stringPattern, commentPattern)
        patternCache[extension] = triple
        return triple
    }

    private fun getColorsForExtension(context: Context, ext: String): Triple<Color, Color, Color> {
        val extension = ext.lowercase()
        
        // 1. Check if XML color overrides exist
        val xmlKeywordResId = context.resources.getIdentifier("highlight_${extension}_keyword", "color", context.packageName)
        val xmlStringResId = context.resources.getIdentifier("highlight_${extension}_string", "color", context.packageName)
        val xmlCommentResId = context.resources.getIdentifier("highlight_${extension}_comment", "color", context.packageName)

        var keywordColor: Color? = null
        var stringColor: Color? = null
        var commentColor: Color? = null

        if (xmlKeywordResId != 0) {
            try {
                keywordColor = Color(context.getColor(xmlKeywordResId))
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (xmlStringResId != 0) {
            try {
                stringColor = Color(context.getColor(xmlStringResId))
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (xmlCommentResId != 0) {
            try {
                commentColor = Color(context.getColor(xmlCommentResId))
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. Load from JSON if XML does not exist
        if (keywordColor == null || stringColor == null || commentColor == null) {
            val json = loadSyntaxFromResource(context, extension)
            if (json != null) {
                val colorsObj = json.optJSONObject("default_colors")
                if (colorsObj != null) {
                    if (keywordColor == null) {
                        val hex = colorsObj.optString("keyword", "#FFFF55")
                        keywordColor = Color(android.graphics.Color.parseColor(hex))
                    }
                    if (stringColor == null) {
                        val hex = colorsObj.optString("string", "#00DD00")
                        stringColor = Color(android.graphics.Color.parseColor(hex))
                    }
                    if (commentColor == null) {
                        val hex = colorsObj.optString("comment", "#808080")
                        commentColor = Color(android.graphics.Color.parseColor(hex))
                    }
                }
            }
        }

        // 3. Fallback defaults if still null
        val finalKeyword = keywordColor ?: Color(0xFFFF9500)
        val finalString = stringColor ?: Color(0xFF00DD00)
        val finalComment = commentColor ?: Color(0xFF808080)

        return Triple(finalKeyword, finalString, finalComment)
    }

    fun buildHighlightedAnnotatedString(context: Context, text: String, extension: String): AnnotatedString {
        return buildAnnotatedString {
            append(text)

            val (keywordPattern, stringPattern, commentPattern) = getPatternsForExtension(context, extension)
            val (keywordColor, stringColor, commentColor) = getColorsForExtension(context, extension)

            if (keywordPattern != null) {
                val matcher = keywordPattern.matcher(text)
                while (matcher.find()) {
                    addStyle(SpanStyle(color = keywordColor), matcher.start(), matcher.end())
                }
            }

            if (stringPattern != null && extension != "json") {
                val matcher = stringPattern.matcher(text)
                while (matcher.find()) {
                    addStyle(SpanStyle(color = stringColor), matcher.start(), matcher.end())
                }
            }

            if (commentPattern != null) {
                val matcher = commentPattern.matcher(text)
                while (matcher.find()) {
                    addStyle(SpanStyle(color = commentColor), matcher.start(), matcher.end())
                }
            }
        }
    }

    fun pushUndo(currentText: String) {
        if (undoStack.isNotEmpty() && undoStack.last() == currentText) {
            return
        }
        undoStack.add(currentText)
        if (undoStack.size > 50) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun undo(): String? {
        if (undoStack.isEmpty()) return null
        val current = textState.value
        redoStack.add(current)
        if (redoStack.size > 50) {
            redoStack.removeAt(0)
        }
        val previous = undoStack.removeAt(undoStack.size - 1)
        textState.value = previous
        return previous
    }

    fun redo(): String? {
        if (redoStack.isEmpty()) return null
        val current = textState.value
        undoStack.add(current)
        if (undoStack.size > 50) {
            undoStack.removeAt(0)
        }
        val next = redoStack.removeAt(redoStack.size - 1)
        textState.value = next
        return next
    }

    fun cutSelection(start: Int, end: Int): String {
        val currentText = textState.value
        pushUndo(currentText)
        if (start != end) {
            val selected = currentText.substring(start, end)
            cutBuffer = selected
            val newText = currentText.substring(0, start) + currentText.substring(end)
            textState.value = newText
            return newText
        } else {
            val lines = currentText.split("\n").toMutableList()
            var charCount = 0
            var lineIndex = -1
            for (i in lines.indices) {
                val lineLength = lines[i].length + 1
                if (start >= charCount && start < charCount + lineLength) {
                    lineIndex = i
                    break
                }
                charCount += lineLength
            }
            if (lineIndex in lines.indices) {
                val cutLine = lines.removeAt(lineIndex)
                cutBuffer = cutLine + "\n"
                val newText = lines.joinToString("\n")
                textState.value = newText
                return newText
            }
        }
        return currentText
    }

    fun paste(cursorPos: Int): String {
        val currentText = textState.value
        pushUndo(currentText)
        val safeCursor = cursorPos.coerceIn(0, currentText.length)
        val newText = currentText.substring(0, safeCursor) + cutBuffer + currentText.substring(safeCursor)
        textState.value = newText
        return newText
    }

    fun searchPositions(query: String): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val text = textState.value
        val results = mutableListOf<IntRange>()
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var index = lowerText.indexOf(lowerQuery)
        while (index != -1) {
            results.add(IntRange(index, index + query.length))
            index = lowerText.indexOf(lowerQuery, index + 1)
        }
        return results
    }

    fun loadFromUri(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val content = reader.readText()
                textState.value = content
                undoStack.clear()
                redoStack.clear()
                true
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveToUri(context: Context, uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "rwt")?.use { outputStream ->
                outputStream.write(content.toByteArray())
                true
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                    true
                } ?: false
            } catch (ex: Exception) {
                ex.printStackTrace()
                false
            }
        }
    }
}
