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
        
    private var syntaxColorsJson: JSONObject? = null

    fun loadSyntaxColors(context: Context) {
        try {
            val resourceId = context.resources.getIdentifier("syntax_colors", "raw", context.packageName)
            if (resourceId != 0) {
                val inputStream = context.resources.openRawResource(resourceId)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val jsonStr = reader.use { it.readText() }
                syntaxColorsJson = JSONObject(jsonStr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSyntaxColors(extension: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val json = syntaxColorsJson ?: return result
        try {
            val extensions = json.optJSONObject("extensions")
            var colorObj = extensions?.optJSONObject(extension.lowercase())
            if (colorObj == null) {
                colorObj = json.optJSONObject("default")
            }
            if (colorObj != null) {
                val keys = colorObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val hex = colorObj.getString(key)
                    result[key] = android.graphics.Color.parseColor(hex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun buildHighlightedAnnotatedString(text: String, extension: String): AnnotatedString {
        val colors = getSyntaxColors(extension)
        val keywordColor = colors["keyword"]?.let { Color(it) } ?: Color(0xFFFF9500)
        val stringColor = colors["string"]?.let { Color(it) } ?: Color(0xFF00DD00)
        val commentColor = colors["comment"]?.let { Color(it) } ?: Color(0xFF808080)

        return buildAnnotatedString {
            append(text)

            val commentPattern: Pattern?
            var stringPattern: Pattern? = Pattern.compile("\"[^\"]*\"|'[^']*'")
            val keywordPattern: Pattern?

            when (extension.lowercase()) {
                "py" -> {
                    commentPattern = Pattern.compile("#.*")
                    keywordPattern = Pattern.compile("\\b(def|class|if|else|elif|for|while|try|except|import|from|as|return|in|is|and|or|not|None|True|False|lambda|pass|break|continue)\\b")
                }
                "java" -> {
                    commentPattern = Pattern.compile("//.*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/")
                    keywordPattern = Pattern.compile("\\b(public|private|protected|class|interface|enum|void|int|double|float|long|boolean|char|byte|short|if|else|for|while|do|switch|case|break|continue|return|try|catch|finally|throw|throws|import|package|new|this|super|static|final|abstract|synchronized|volatile|transient)\\b")
                }
                "kt", "kts" -> {
                    commentPattern = Pattern.compile("//.*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/")
                    keywordPattern = Pattern.compile("\\b(package|import|class|interface|object|fun|val|var|typealias|if|else|when|for|while|do|return|break|continue|throw|try|catch|finally|this|super|constructor|init|null|true|false|as|is|in|companion|private|protected|public|internal|open|abstract|override|final|data|sealed|enum|annotation|inline|infix|operator|tailrec|external|suspend)\\b")
                }
                "js", "ts", "jsx", "tsx" -> {
                    commentPattern = Pattern.compile("//.*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/")
                    keywordPattern = Pattern.compile("\\b(const|let|var|function|class|if|else|for|while|do|switch|case|default|break|continue|return|try|catch|finally|throw|import|export|from|as|new|this|super|static|async|await|yield|null|undefined|true|false|typeof|instanceof|in|of|interface|type|namespace|enum|public|private|protected|readonly|any|string|number|boolean|void|never|unknown)\\b")
                }
                "cpp", "c", "h", "hpp" -> {
                    commentPattern = Pattern.compile("//.*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/")
                    keywordPattern = Pattern.compile("\\b(auto|const|double|float|int|long|short|char|void|signed|unsigned|struct|union|class|enum|typedef|public|private|protected|if|else|for|while|do|switch|case|default|break|continue|return|try|catch|throw|namespace|using|friend|virtual|override|inline|static|extern|volatile|mutable|template|typename|operator|new|delete|this|nullptr|true|false)\\b")
                }
                "html", "htm" -> {
                    commentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
                    keywordPattern = Pattern.compile("</?[a-zA-Z0-9:_.-]+|(\\s[a-zA-Z0-9:_.-]+=)")
                }
                "xml" -> {
                    commentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
                    keywordPattern = Pattern.compile("</?[a-zA-Z0-9:_.-]+|(\\s[a-zA-Z0-9:_.-]+=)")
                }
                "json" -> {
                    commentPattern = null
                    keywordPattern = Pattern.compile("\"[^\"]*\"\\s*:")
                }
                "sh" -> {
                    commentPattern = Pattern.compile("#.*")
                    keywordPattern = Pattern.compile("\\b(if|then|else|elif|fi|case|esac|for|while|in|do|done|exit|return|echo|local|function)\\b")
                }
                "md" -> {
                    commentPattern = Pattern.compile("<!--[\\s\\S]*?-->|`[^`]+`|```[\\s\\S]*?```")
                    keywordPattern = Pattern.compile("(?m)(^#{1,6}\\s+.+|\\*\\*[^\\*]+\\*\\*|\\*[^\\*]+\\*)")
                    stringPattern = Pattern.compile("\\[[^\\]]+\\]\\([^\\)]+\\)")
                }
                "yml", "yaml" -> {
                    commentPattern = Pattern.compile("#.*")
                    keywordPattern = Pattern.compile("\\b[a-zA-Z0-9_-]+\\b(?=\\s*:)")
                    stringPattern = Pattern.compile("\"[^\"]*\"|'[^']*'")
                }
                "conf" -> {
                    commentPattern = Pattern.compile(";.*|#.*")
                    keywordPattern = Pattern.compile("\\[[^\\]]+\\]|\\b[a-zA-Z0-9_.-]+\\b(?=\\s*=)")
                    stringPattern = Pattern.compile("\"[^\"]*\"|'[^']*'")
                }
                "css" -> {
                    commentPattern = Pattern.compile("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/")
                    keywordPattern = Pattern.compile("(\\b[a-zA-Z0-9_-]+\\b(?=\\s*:)|^[ \\t]*[.#a-zA-Z0-9_-]+(?=\\s*\\{))")
                    stringPattern = Pattern.compile("\"[^\"]*\"|'[^']*'")
                }
                "bat" -> {
                    commentPattern = Pattern.compile("(?im)^[ \\t]*(?:rem|REM)\\b.*|::.*")
                    keywordPattern = Pattern.compile("(?i)\\b(echo|set|if|else|for|in|do|goto|call|exit|pause|rem)\\b")
                    stringPattern = Pattern.compile("\"[^\"]*\"|'[^']*'")
                }
                "csv" -> {
                    commentPattern = null
                    keywordPattern = null
                    stringPattern = null
                }
                else -> {
                    commentPattern = Pattern.compile("#.*|//.*")
                    keywordPattern = Pattern.compile("\\b(if|else|for|while|return)\\b")
                }
            }

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
