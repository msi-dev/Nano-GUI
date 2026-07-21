package com.msi.nanogui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.*

class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pieceTable = PieceTable()
        private set

    var cursorLine = 0
    var cursorCol = 0

    // Theme Colors (default values, can be overridden)
    private var bgColor = Color.BLACK
    private var textColor = Color.WHITE
    private var keywordColor = Color.parseColor("#FFFF9500")
    private var stringColor = Color.parseColor("#FF00DD00")
    private var commentColor = Color.parseColor("#FF808080")
    private var gutterBgColor = Color.parseColor("#FF111111")
    private var gutterTextColor = Color.parseColor("#FF666666")
    private var cursorColor = Color.WHITE

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 42f // default size, adjustable
    }

    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 36f
        color = gutterTextColor
        textAlign = Paint.Align.RIGHT
    }

    private val lineHighlightPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val cursorPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var lineHeight = 60f
    private val textPaddingTop = 40f
    private val textPaddingLeft = 24f
    private val gutterWidth = 120f

    private val highlightCache = HashMap<Int, List<Token>>()
    private var lastVisibleFirstLine = -1
    private var lastVisibleLastLine = -1

    private var cursorVisible = true
    private var cursorBlinkJob: Job? = null
    private var fileExtension = "txt"

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        startCursorBlinking()
    }

    fun setFileExtension(ext: String) {
        fileExtension = ext
        highlightCache.clear()
        invalidate()
    }

    fun setEditorColors(
        bg: Int,
        text: Int,
        keyword: Int,
        string: Int,
        comment: Int,
        gutterBg: Int,
        gutterText: Int,
        cursor: Int,
        lineHighlight: Int
    ) {
        bgColor = bg
        textColor = text
        keywordColor = keyword
        stringColor = string
        commentColor = comment
        gutterBgColor = gutterBg
        gutterTextColor = gutterText
        cursorColor = cursor
        cursorPaint.color = cursor
        gutterPaint.color = gutterText
        lineHighlightPaint.color = lineHighlight
        invalidate()
    }

    fun setEditorFontSize(sp: Float) {
        val px = sp * resources.displayMetrics.scaledDensity
        textPaint.textSize = px
        gutterPaint.textSize = px * 0.85f
        lineHeight = px * 1.5f
        invalidate()
    }

    fun loadFromUri(uri: android.net.Uri): Boolean {
        highlightCache.clear()
        val success = pieceTable.loadFromUri(context, uri)
        if (success) {
            cursorLine = 0
            cursorCol = 0
            scrollTo(0, 0)
            invalidate()
        }
        return success
    }

    fun loadFromText(text: String) {
        highlightCache.clear()
        pieceTable.loadFromText(text)
        cursorLine = 0
        cursorCol = 0
        scrollTo(0, 0)
        invalidate()
    }

    private fun startCursorBlinking() {
        cursorBlinkJob?.cancel()
        cursorBlinkJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(500)
                cursorVisible = !cursorVisible
                invalidate()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cursorBlinkJob?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)

        val totalLines = pieceTable.totalLines
        val viewHeight = height
        val firstLine = Math.max(0, ((scrollY - textPaddingTop) / lineHeight).toInt())
        val lastLine = Math.min(totalLines - 1, ((scrollY + viewHeight - textPaddingTop) / lineHeight).toInt() + 1)

        // Draw Gutter Background
        canvas.drawRect(0f, 0f, gutterWidth, viewHeight.toFloat(), Paint().apply { color = gutterBgColor })

        // Trigger lazy syntax highlighting on a background thread for visible lines
        val tokenizer = Tokenizer(context, fileExtension)
        updateHighlighting(firstLine, lastLine, tokenizer)

        for (lineIdx in firstLine..lastLine) {
            val yPos = lineIdx * lineHeight + textPaddingTop + textPaint.textSize - scrollY

            // Draw line number in Gutter
            canvas.drawText((lineIdx + 1).toString(), gutterWidth - 16f, yPos, gutterPaint)

            // Draw current line highlight
            if (lineIdx == cursorLine) {
                val highlightTop = lineIdx * lineHeight + textPaddingTop - scrollY
                canvas.drawRect(gutterWidth, highlightTop, width.toFloat(), highlightTop + lineHeight, lineHighlightPaint)
            }

            // Draw line text
            try {
                val lineText = pieceTable.getLineString(lineIdx)
                val styles = highlightCache[lineIdx] ?: emptyList()
                drawStyledText(canvas, lineText, styles, gutterWidth + textPaddingLeft - scrollX, yPos)
            } catch (e: Exception) {
                e.printStackTrace()
                // Defensive try-catch wrapper to prevent crashes if indices mismatch
            }
        }

        // Draw cursor
        if (cursorVisible && cursorLine in firstLine..lastLine) {
            try {
                val lineText = pieceTable.getLineString(cursorLine)
                val beforeCursorText = lineText.substring(0, Math.min(cursorCol, lineText.length))
                val cursorX = gutterWidth + textPaddingLeft + textPaint.measureText(beforeCursorText) - scrollX
                if (cursorX >= gutterWidth) {
                    val cursorYTop = cursorLine * lineHeight + textPaddingTop - scrollY + (lineHeight - textPaint.textSize) / 2
                    val cursorYBottom = cursorYTop + textPaint.textSize
                    canvas.drawLine(cursorX, cursorYTop, cursorX, cursorYBottom, cursorPaint)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun drawStyledText(
        canvas: Canvas,
        lineText: String,
        tokens: List<Token>,
        startX: Float,
        yPos: Float
    ) {
        if (lineText.isEmpty()) return
        val sortedTokens = tokens.sortedBy { it.start }
        var lastIdx = 0
        var currentX = startX

        val paint = Paint(textPaint)

        for (token in sortedTokens) {
            val tokenStart = token.start.coerceIn(0, lineText.length)
            val tokenEnd = token.end.coerceIn(0, lineText.length)
            if (tokenStart >= tokenEnd) continue

            // Draw plain text segment before token
            if (tokenStart > lastIdx) {
                val plainSegment = lineText.substring(lastIdx, tokenStart)
                paint.color = textColor
                canvas.drawText(plainSegment, currentX, yPos, paint)
                currentX += paint.measureText(plainSegment)
            }

            // Draw styled token
            val tokenSegment = lineText.substring(tokenStart, tokenEnd)
            paint.color = when (token.type) {
                TokenType.KEYWORD -> keywordColor
                TokenType.STRING -> stringColor
                TokenType.COMMENT -> commentColor
                TokenType.PLAIN -> textColor
            }
            canvas.drawText(tokenSegment, currentX, yPos, paint)
            currentX += paint.measureText(tokenSegment)
            lastIdx = tokenEnd
        }

        // Draw remaining plain text
        if (lastIdx < lineText.length) {
            val remainingSegment = lineText.substring(lastIdx)
            paint.color = textColor
            canvas.drawText(remainingSegment, currentX, yPos, paint)
        }
    }

    private fun updateHighlighting(firstLine: Int, lastLine: Int, tokenizer: Tokenizer) {
        if (firstLine == lastVisibleFirstLine && lastLine == lastVisibleLastLine) return
        lastVisibleFirstLine = firstLine
        lastVisibleLastLine = lastLine

        val linesToTokenize = ArrayList<Pair<Int, String>>()
        for (lineIdx in firstLine..lastLine) {
            if (lineIdx >= pieceTable.totalLines) break
            if (!highlightCache.containsKey(lineIdx)) {
                linesToTokenize.add(Pair(lineIdx, pieceTable.getLineString(lineIdx)))
            }
        }

        if (linesToTokenize.isEmpty()) return

        CoroutineScope(Dispatchers.Default).launch {
            val tokensMap = linesToTokenize.map { (idx, lineText) ->
                Pair(idx, tokenizer.tokenize(lineText))
            }
            withContext(Dispatchers.Main) {
                for ((idx, tokens) in tokensMap) {
                    highlightCache[idx] = tokens
                }
                invalidate()
            }
        }
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isScrolling = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isScrolling = false
                handleTap(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = lastTouchX - event.x
                val deltaY = lastTouchY - event.y
                if (!isScrolling && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
                    isScrolling = true
                }
                if (isScrolling) {
                    scrollBy(deltaX.toInt(), deltaY.toInt())
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isScrolling = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun scrollBy(x: Int, y: Int) {
        val maxScrollY = Math.max(0, (pieceTable.totalLines * lineHeight).toInt() - height + 200)
        val newScrollY = (scrollY + y).coerceIn(0, maxScrollY)

        val newScrollX = (scrollX + x).coerceIn(0, 2000)
        scrollTo(newScrollX, newScrollY)
    }

    private fun handleTap(x: Float, y: Float) {
        val tappedLine = ((y + scrollY - textPaddingTop) / lineHeight).toInt()
        val lineIdx = tappedLine.coerceIn(0, pieceTable.totalLines - 1)

        val lineText = pieceTable.getLineString(lineIdx)
        val textX = x + scrollX - gutterWidth - textPaddingLeft

        var bestCol = 0
        var bestDiff = Float.MAX_VALUE
        for (col in 0..lineText.length) {
            val sub = lineText.substring(0, col)
            val w = textPaint.measureText(sub)
            val diff = Math.abs(w - textX)
            if (diff < bestDiff) {
                bestDiff = diff
                bestCol = col
            }
        }

        cursorLine = lineIdx
        cursorCol = bestCol
        cursorVisible = true
        invalidate()

        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        imm.updateSelection(this, getCursorIndex(), getCursorIndex(), -1, -1)
    }

    var onTextChangedListener: ((String) -> Unit)? = null

    fun setPieceTable(pt: PieceTable) {
        this.pieceTable = pt
        highlightCache.clear()
        invalidate()
    }

    var cutBuffer = ""
        private set

    fun cut() {
        val lineText = pieceTable.getLineString(cursorLine)
        cutBuffer = lineText + "\n"

        val startByte = pieceTable.getLineStartOffset(cursorLine)
        val nextStartByte = pieceTable.getLineStartOffset(cursorLine + 1)
        val length = nextStartByte - startByte
        if (length > 0) {
            pieceTable.pushUndo()
            pieceTable.deleteRange(startByte, length)
            pieceTable.scanLinesBackground {
                cursorLine = cursorLine.coerceAtMost(pieceTable.totalLines - 1)
                cursorCol = 0
                highlightCache.clear()
                invalidate()
                onTextChangedListener?.invoke(pieceTable.getAllText())
            }
        }
    }

    fun paste() {
        if (cutBuffer.isNotEmpty()) {
            val (newLine, newCol) = pieceTable.insertAtCursor(cursorLine, cursorCol, cutBuffer)
            cursorLine = newLine
            cursorCol = newCol
            highlightCache.clear()
            invalidate()
            onTextChangedListener?.invoke(pieceTable.getAllText())
        }
    }

    fun undo() {
        if (pieceTable.undo()) {
            cursorLine = cursorLine.coerceIn(0, pieceTable.totalLines - 1)
            cursorCol = cursorCol.coerceIn(0, pieceTable.getLineString(cursorLine).length)
            highlightCache.clear()
            invalidate()
            onTextChangedListener?.invoke(pieceTable.getAllText())
        }
    }

    fun redo() {
        if (pieceTable.redo()) {
            cursorLine = cursorLine.coerceIn(0, pieceTable.totalLines - 1)
            cursorCol = cursorCol.coerceIn(0, pieceTable.getLineString(cursorLine).length)
            highlightCache.clear()
            invalidate()
            onTextChangedListener?.invoke(pieceTable.getAllText())
        }
    }

    fun getCursorIndex(): Int {
        return pieceTable.getCursorByteOffset(cursorLine, cursorCol)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val inserted = text?.toString() ?: ""
                val (newLine, newCol) = pieceTable.insertAtCursor(cursorLine, cursorCol, inserted)
                cursorLine = newLine
                cursorCol = newCol
                cursorVisible = true
                highlightCache.clear()
                invalidate()
                onTextChangedListener?.invoke(pieceTable.getAllText())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val (newLine, newCol) = pieceTable.deleteAtCursor(cursorLine, cursorCol, beforeLength, afterLength)
                cursorLine = newLine
                cursorCol = newCol
                cursorVisible = true
                highlightCache.clear()
                invalidate()
                onTextChangedListener?.invoke(pieceTable.getAllText())
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                        val (newLine, newCol) = pieceTable.deleteAtCursor(cursorLine, cursorCol, 1, 0)
                        cursorLine = newLine
                        cursorCol = newCol
                        cursorVisible = true
                        highlightCache.clear()
                        invalidate()
                        onTextChangedListener?.invoke(pieceTable.getAllText())
                        return true
                    } else if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                        val (newLine, newCol) = pieceTable.insertAtCursor(cursorLine, cursorCol, "\n")
                        cursorLine = newLine
                        cursorCol = newCol
                        cursorVisible = true
                        highlightCache.clear()
                        invalidate()
                        onTextChangedListener?.invoke(pieceTable.getAllText())
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}
