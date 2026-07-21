package com.msi.nanogui

import android.util.Log

object NativeEditorHelper {
    private var isLibLoaded = false

    init {
        try {
            System.loadLibrary("nanogui-native")
            isLibLoaded = true
            Log.d("NativeEditorHelper", "Successfully loaded nanogui-native C++ library")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeEditorHelper", "Failed to load nanogui-native library: ${e.message}")
        }
    }

    /**
     * Analyzes the text and returns an IntArray of size 3:
     * index 0: Line count
     * index 1: Word count
     * index 2: Character count
     */
    fun analyze(text: String): IntArray {
        return if (isLibLoaded) {
            try {
                analyzeText(text)
            } catch (e: Throwable) {
                fallbackAnalyze(text)
            }
        } else {
            fallbackAnalyze(text)
        }
    }

    private external fun analyzeText(text: String): IntArray

    private fun fallbackAnalyze(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf(0, 0, 0)
        val lines = text.split("\n").size
        val words = if (text.isBlank()) 0 else text.trim().split("\\s+".toRegex()).size
        val chars = text.length
        return intArrayOf(lines, words, chars)
    }
}
