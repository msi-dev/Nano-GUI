package com.msi.nanogui

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Piece(
    val sourceBuffer: Int, // 0 for original file, 1 for add buffer
    val startOffset: Int,
    val length: Int
)

class AddBuffer {
    private val blocks = ArrayList<ByteArray>()
    private val BLOCK_SIZE = 4096
    private var currentBlock = ByteArray(BLOCK_SIZE)
    private var currentOffset = 0
    private var totalBytes = 0

    init {
        blocks.add(currentBlock)
    }

    @Synchronized
    fun append(bytes: ByteArray): Int {
        val startOffset = totalBytes
        var bytesWritten = 0
        while (bytesWritten < bytes.size) {
            val spaceLeft = BLOCK_SIZE - currentOffset
            if (spaceLeft == 0) {
                currentBlock = ByteArray(BLOCK_SIZE)
                blocks.add(currentBlock)
                currentOffset = 0
            }
            val toWrite = minOf(bytes.size - bytesWritten, BLOCK_SIZE - currentOffset)
            System.arraycopy(bytes, bytesWritten, currentBlock, currentOffset, toWrite)
            currentOffset += toWrite
            bytesWritten += toWrite
            totalBytes += toWrite
        }
        return startOffset
    }

    @Synchronized
    fun getByte(offset: Int): Byte {
        var remaining = offset
        for (block in blocks) {
            if (remaining < BLOCK_SIZE) {
                return block[remaining]
            }
            remaining -= BLOCK_SIZE
        }
        throw IndexOutOfBoundsException("Offset $offset out of bounds for AddBuffer")
    }

    @Synchronized
    fun getBytes(offset: Int, length: Int): ByteArray {
        val result = ByteArray(length)
        var remainingOffset = offset
        var resultOffset = 0
        var remainingLength = length

        var blockIdx = remainingOffset / BLOCK_SIZE
        var blockOffset = remainingOffset % BLOCK_SIZE

        while (remainingLength > 0 && blockIdx < blocks.size) {
            val block = blocks[blockIdx]
            val toCopy = minOf(remainingLength, BLOCK_SIZE - blockOffset)
            System.arraycopy(block, blockOffset, result, resultOffset, toCopy)
            resultOffset += toCopy
            remainingLength -= toCopy
            blockIdx++
            blockOffset = 0
        }
        return result
    }

    fun size(): Int = totalBytes
}

class PieceTable {
    private var originalBuffer: MappedByteBuffer? = null
    private var originalSize: Int = 0
    private var addBuffer = AddBuffer()
    private val pieces = ArrayList<Piece>()

    // Lazy Line Index Cache (Line index -> Start Byte Offset)
    private val lineOffsetCache = object : LruCache<Int, Int>(20000) {}

    var totalLines: Int = 1
        private set

    // Undo / Redo Stacks storing list of Piece snapshots
    private val undoStack = ArrayList<List<Piece>>()
    private val redoStack = ArrayList<List<Piece>>()

    init {
        lineOffsetCache.put(0, 0)
    }

    @Synchronized
    fun loadFromUri(context: Context, uri: Uri): Boolean {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
            val fileDescriptor = pfd.fileDescriptor
            val fis = FileInputStream(fileDescriptor)
            val channel = fis.channel
            val size = channel.size().toInt()

            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())

            pieces.clear()
            originalBuffer = buffer
            originalSize = size
            addBuffer = AddBuffer()

            if (size > 0) {
                pieces.add(Piece(0, 0, size))
            }

            lineOffsetCache.evictAll()
            lineOffsetCache.put(0, 0)
            undoStack.clear()
            redoStack.clear()

            pfd.close()
            scanLinesBackground {}
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            try { pfd?.close() } catch (ex: Exception) {}
            return false
        }
    }

    @Synchronized
    fun loadFromText(text: String) {
        pieces.clear()
        originalBuffer = null
        originalSize = 0
        addBuffer = AddBuffer()

        val bytes = text.toByteArray(Charsets.UTF_8)
        val offset = addBuffer.append(bytes)
        if (bytes.isNotEmpty()) {
            pieces.add(Piece(1, offset, bytes.size))
        }

        lineOffsetCache.evictAll()
        lineOffsetCache.put(0, 0)
        undoStack.clear()
        redoStack.clear()

        scanLinesBackground {}
    }

    @Synchronized
    fun getTotalBytes(): Int {
        var total = 0
        for (p in pieces) {
            total += p.length
        }
        return total
    }

    @Synchronized
    fun getByteAt(byteOffset: Int): Byte {
        var remaining = byteOffset
        for (piece in pieces) {
            if (remaining < piece.length) {
                return if (piece.sourceBuffer == 0) {
                    originalBuffer?.get(piece.startOffset + remaining) ?: 0
                } else {
                    addBuffer.getByte(piece.startOffset + remaining)
                }
            }
            remaining -= piece.length
        }
        throw IndexOutOfBoundsException("Byte offset $byteOffset out of bounds")
    }

    @Synchronized
    fun getBytes(byteOffset: Int, length: Int): ByteArray {
        val result = ByteArray(length)
        if (length <= 0) return result
        var remainingOffset = byteOffset
        var resultOffset = 0
        var remainingLength = length

        for (piece in pieces) {
            if (remainingLength <= 0) break

            if (remainingOffset < piece.length) {
                val toCopy = minOf(remainingLength, piece.length - remainingOffset)
                if (piece.sourceBuffer == 0) {
                    val oldPos = originalBuffer?.position()
                    originalBuffer?.position(piece.startOffset + remainingOffset)
                    originalBuffer?.get(result, resultOffset, toCopy)
                    if (oldPos != null) originalBuffer?.position(oldPos)
                } else {
                    val bytes = addBuffer.getBytes(piece.startOffset + remainingOffset, toCopy)
                    System.arraycopy(bytes, 0, result, resultOffset, toCopy)
                }
                resultOffset += toCopy
                remainingLength -= toCopy
                remainingOffset = 0
            } else {
                remainingOffset -= piece.length
            }
        }
        return result
    }

    @Synchronized
    fun getString(byteOffset: Int, length: Int): String {
        if (length <= 0) return ""
        val bytes = getBytes(byteOffset, length)
        return String(bytes, Charsets.UTF_8)
    }

    @Synchronized
    fun getLineStartOffset(lineNumber: Int): Int {
        if (lineNumber <= 0) return 0

        // Find nearest cached line <= lineNumber
        var nearestLine = lineNumber
        var nearestOffset: Int? = null
        while (nearestLine >= 0) {
            nearestOffset = lineOffsetCache.get(nearestLine)
            if (nearestOffset != null) {
                break
            }
            nearestLine--
        }

        if (nearestOffset == null) {
            nearestOffset = 0
            nearestLine = 0
        }

        var currentOffset = nearestOffset
        var currentLine = nearestLine
        val totalBytes = getTotalBytes()

        // Scan forward iteratively to avoid StackOverflow
        while (currentLine < lineNumber && currentOffset < totalBytes) {
            val b = getByteAt(currentOffset)
            if (b == 10.toByte()) { // '\n'
                currentLine++
                lineOffsetCache.put(currentLine, currentOffset + 1)
            }
            currentOffset++
        }

        return if (currentLine == lineNumber) {
            currentOffset
        } else {
            totalBytes
        }
    }

    @Synchronized
    fun getLineString(lineNumber: Int): String {
        val start = getLineStartOffset(lineNumber)
        val nextStart = getLineStartOffset(lineNumber + 1)
        val totalBytes = getTotalBytes()
        if (start >= totalBytes) return ""

        var length = nextStart - start
        if (length <= 0) return ""

        // Exclude newline from returned line text
        if (start + length <= totalBytes) {
            val lastByte = getByteAt(start + length - 1)
            if (lastByte == 10.toByte()) {
                length--
            }
        }
        return getString(start, length)
    }

    @Synchronized
    fun getLineAndColumnForByteOffset(byteOffset: Int): Pair<Int, Int> {
        if (byteOffset <= 0) return Pair(0, 0)
        val totalBytes = getTotalBytes()
        val targetOffset = minOf(byteOffset, totalBytes)

        // Find nearest cached line <= targetOffset
        var nearestLine = 0
        var lastLineOffset = 0
        var searchLine = 0
        while (true) {
            val offset = lineOffsetCache.get(searchLine) ?: break
            if (offset <= targetOffset) {
                nearestLine = searchLine
                lastLineOffset = offset
            } else {
                break
            }
            searchLine++
        }

        var line = nearestLine
        var offset = lastLineOffset

        while (offset < targetOffset && offset < totalBytes) {
            val b = getByteAt(offset)
            if (b == 10.toByte()) {
                line++
                lineOffsetCache.put(line, offset + 1)
            }
            offset++
        }

        val lineStart = getLineStartOffset(line)
        val colBytes = targetOffset - lineStart
        val lineText = getLineString(line)
        var bytesCount = 0
        var colChars = 0
        while (colChars < lineText.length) {
            val charBytes = lineText[colChars].toString().toByteArray(Charsets.UTF_8).size
            if (bytesCount + charBytes > colBytes) {
                break
            }
            bytesCount += charBytes
            colChars++
        }

        return Pair(line, colChars)
    }

    fun getColumnByteOffset(line: Int, column: Int): Int {
        val lineText = getLineString(line)
        val safeColumn = column.coerceIn(0, lineText.length)
        val sub = lineText.substring(0, safeColumn)
        return sub.toByteArray(Charsets.UTF_8).size
    }

    fun getCursorByteOffset(line: Int, column: Int): Int {
        return getLineStartOffset(line) + getColumnByteOffset(line, column)
    }

    @Synchronized
    fun insert(logicalOffset: Int, text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        val addOffset = addBuffer.append(bytes)
        val newPiece = Piece(sourceBuffer = 1, startOffset = addOffset, length = bytes.size)

        if (pieces.isEmpty()) {
            pieces.add(newPiece)
            return
        }

        var currentLogical = 0
        for (i in pieces.indices) {
            val piece = pieces[i]
            if (logicalOffset >= currentLogical && logicalOffset <= currentLogical + piece.length) {
                val relativeOffset = logicalOffset - currentLogical
                if (relativeOffset == 0) {
                    pieces.add(i, newPiece)
                } else if (relativeOffset == piece.length) {
                    pieces.add(i + 1, newPiece)
                } else {
                    val left = Piece(piece.sourceBuffer, piece.startOffset, relativeOffset)
                    val right = Piece(piece.sourceBuffer, piece.startOffset + relativeOffset, piece.length - relativeOffset)
                    pieces[i] = left
                    pieces.add(i + 1, newPiece)
                    pieces.add(i + 2, right)
                }
                return
            }
            currentLogical += piece.length
        }
        pieces.add(newPiece)
    }

    @Synchronized
    fun insertAtCursor(line: Int, column: Int, text: String): Pair<Int, Int> {
        pushUndo()
        val byteOffset = getCursorByteOffset(line, column)
        insert(byteOffset, text)

        lineOffsetCache.evictAll()
        lineOffsetCache.put(0, 0)

        // Rescan lines
        val count = scanLinesSync()
        totalLines = count

        val insertedLines = text.split("\n")
        return if (insertedLines.size > 1) {
            val newLine = line + insertedLines.size - 1
            val newCol = insertedLines.last().length
            Pair(newLine, newCol)
        } else {
            Pair(line, column + text.length)
        }
    }

    @Synchronized
    fun deleteRange(byteOffset: Int, byteLength: Int) {
        if (byteLength <= 0) return

        var remainingOffset = byteOffset
        var remainingLength = byteLength

        var i = 0
        while (remainingLength > 0 && i < pieces.size) {
            val piece = pieces[i]
            if (remainingOffset < piece.length) {
                val toDelete = minOf(remainingLength, piece.length - remainingOffset)
                if (remainingOffset == 0) {
                    if (toDelete == piece.length) {
                        pieces.removeAt(i)
                    } else {
                        pieces[i] = Piece(piece.sourceBuffer, piece.startOffset + toDelete, piece.length - toDelete)
                        i++
                    }
                } else if (remainingOffset + toDelete == piece.length) {
                    pieces[i] = Piece(piece.sourceBuffer, piece.startOffset, remainingOffset)
                    i++
                } else {
                    val left = Piece(piece.sourceBuffer, piece.startOffset, remainingOffset)
                    val right = Piece(piece.sourceBuffer, piece.startOffset + remainingOffset + toDelete, piece.length - remainingOffset - toDelete)
                    pieces[i] = left
                    pieces.add(i + 1, right)
                    i += 2
                }
                remainingLength -= toDelete
                remainingOffset = 0
            } else {
                remainingOffset -= piece.length
                i++
            }
        }
    }

    @Synchronized
    fun deleteAtCursor(line: Int, column: Int, beforeLength: Int, afterLength: Int): Pair<Int, Int> {
        pushUndo()
        val cursorByteOffset = getCursorByteOffset(line, column)

        var deleteStartByte = cursorByteOffset
        var newCursorLine = line
        var newCursorCol = column

        if (beforeLength > 0) {
            var charsFound = 0
            var tempOffset = cursorByteOffset
            while (charsFound < beforeLength && tempOffset > 0) {
                tempOffset--
                val b = getByteAt(tempOffset).toInt()
                if ((b and 0xC0) != 0x80) {
                    charsFound++
                }
            }
            deleteStartByte = tempOffset
            val (l, c) = getLineAndColumnForByteOffset(deleteStartByte)
            newCursorLine = l
            newCursorCol = c
        }

        var deleteEndByte = cursorByteOffset
        if (afterLength > 0) {
            var charsFound = 0
            var tempOffset = cursorByteOffset
            val totalBytes = getTotalBytes()
            while (charsFound < afterLength && tempOffset < totalBytes) {
                tempOffset++
                val b = getByteAt(tempOffset).toInt()
                if ((b and 0xC0) != 0x80) {
                    charsFound++
                }
            }
            deleteEndByte = tempOffset
        }

        val bytesToDelete = deleteEndByte - deleteStartByte
        if (bytesToDelete > 0) {
            deleteRange(deleteStartByte, bytesToDelete)

            lineOffsetCache.evictAll()
            lineOffsetCache.put(0, 0)

            val count = scanLinesSync()
            totalLines = count
        }

        return Pair(newCursorLine, newCursorCol)
    }

    @Synchronized
    fun scanLinesSync(): Int {
        var lines = 1
        var currentOffset = 0
        val total = getTotalBytes()
        while (currentOffset < total) {
            val b = getByteAt(currentOffset)
            if (b == 10.toByte()) {
                lines++
                if (lines % 100 == 0) {
                    lineOffsetCache.put(lines - 1, currentOffset + 1)
                }
            }
            currentOffset++
        }
        return lines
    }

    fun scanLinesBackground(onComplete: (Int) -> Unit) {
        val localPieces = synchronized(this) { ArrayList(pieces) }
        val localBuffer = synchronized(this) { originalBuffer?.duplicate() }

        CoroutineScope(Dispatchers.Default).launch {
            var lines = 1
            for (piece in localPieces) {
                if (piece.sourceBuffer == 0 && localBuffer != null) {
                    val start = piece.startOffset
                    val length = piece.length
                    for (i in 0 until length) {
                        val b = localBuffer.get(start + i)
                        if (b == 10.toByte()) {
                            lines++
                        }
                    }
                } else {
                    val start = piece.startOffset
                    val length = piece.length
                    val bytes = addBuffer.getBytes(start, length)
                    for (b in bytes) {
                        if (b == 10.toByte()) {
                            lines++
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                synchronized(this@PieceTable) {
                    totalLines = lines
                }
                onComplete(lines)
            }
        }
    }

    @Synchronized
    fun pushUndo() {
        val snapshot = ArrayList(pieces)
        if (undoStack.isNotEmpty() && undoStack.last() == snapshot) {
            return
        }
        undoStack.add(snapshot)
        if (undoStack.size > 50) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    @Synchronized
    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val current = ArrayList(pieces)
        redoStack.add(current)

        val previous = undoStack.removeAt(undoStack.size - 1)
        pieces.clear()
        pieces.addAll(previous)

        lineOffsetCache.evictAll()
        lineOffsetCache.put(0, 0)
        val count = scanLinesSync()
        totalLines = count
        return true
    }

    @Synchronized
    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val current = ArrayList(pieces)
        undoStack.add(current)

        val next = redoStack.removeAt(redoStack.size - 1)
        pieces.clear()
        pieces.addAll(next)

        lineOffsetCache.evictAll()
        lineOffsetCache.put(0, 0)
        val count = scanLinesSync()
        totalLines = count
        return true
    }

    @Synchronized
    fun saveToFile(context: Context, destinationFile: File): Boolean {
        val tempFile = File.createTempFile("save_temp", ".tmp", context.cacheDir)
        try {
            FileOutputStream(tempFile).buffered().use { output ->
                val localPieces = ArrayList(pieces)
                val buffer = originalBuffer?.duplicate()
                for (piece in localPieces) {
                    if (piece.sourceBuffer == 0 && buffer != null) {
                        val start = piece.startOffset
                        val length = piece.length
                        val chunk = ByteArray(minOf(4096, length))
                        var offset = 0
                        while (offset < length) {
                            val toRead = minOf(chunk.size, length - offset)
                            buffer.position(start + offset)
                            buffer.get(chunk, 0, toRead)
                            output.write(chunk, 0, toRead)
                            offset += toRead
                        }
                    } else {
                        val start = piece.startOffset
                        val length = piece.length
                        var offset = 0
                        while (offset < length) {
                            val toRead = minOf(4096, length - offset)
                            val bytes = addBuffer.getBytes(start + offset, toRead)
                            output.write(bytes)
                            offset += toRead
                        }
                    }
                }
            }

            val totalBytes = getTotalBytes()
            if (tempFile.length() != totalBytes.toLong()) {
                throw IOException("File size mismatch after write")
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    destinationFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } else {
                if (!tempFile.renameTo(destinationFile)) {
                    tempFile.copyTo(destinationFile, overwrite = true)
                    tempFile.delete()
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile.delete()
            return false
        }
    }

    @Synchronized
    fun saveToUri(context: Context, uri: Uri): Boolean {
        val tempFile = File.createTempFile("save_temp_uri", ".tmp", context.cacheDir)
        try {
            if (!saveToFile(context, tempFile)) return false

            context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                tempFile.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
            } ?: context.contentResolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
            } ?: return false

            tempFile.delete()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile.delete()
            return false
        }
    }

    fun getAllText(): String {
        return getString(0, getTotalBytes())
    }
}
