package com.example

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// Request structures
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

data class GeminiContent(
    val parts: List<GeminiPart>
)

data class GeminiPart(
    val text: String
)

data class GeminiGenerationConfig(
    val responseMimeType: String? = null,
    val temperature: Float? = null
)

// Response structures
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

data class GeminiCandidate(
    val content: GeminiContent? = null
)

// Parsed result
data class AnalysisResult(
    val is_code: Boolean,
    val detected_language: String,
    val suggested_extension: String
)

interface GeminiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

object GeminiAnalyzer {
    private const val TAG = "GeminiAnalyzer"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val service: GeminiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiService::class.java)
    }

    suspend fun analyzeFile(filename: String, content: String): AnalysisResult? = withContext(Dispatchers.IO) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.d(TAG, "Gemini API key is not configured, skipping background analysis.")
            return@withContext null
        }

        // Take a reasonable slice of the file to keep latency extremely fast and save tokens
        val contentSample = content.take(4000)

        val prompt = """
            Analyze this file to determine if it contains program/code source code, or if it is just a plain text/prose document.
            File name: $filename
            
            File Content Sample:
            $contentSample
            
            Based on the filename and the content sample, provide the classification and the most suitable syntax highlighting extension.
            Supported syntax highlighting extensions are: "py", "java", "xml", "json", "sh", "kt", "js", "ts", "cpp", "html", or "txt" (for plain text).
            Map other languages to the closest supported extension if possible (e.g., C -> "cpp", Kotlin -> "kt", Shell/Bash -> "sh").
            If it is clearly plain text/prose and not any programming code, set is_code to false and suggested_extension to "txt".
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = prompt)))
            ),
            generationConfig = GeminiGenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.1f
            ),
            systemInstruction = GeminiContent(
                parts = listOf(
                    GeminiPart(
                        text = "You are an automated file analyzer and syntax highlighting classifier. You MUST respond with a JSON object containing strictly these fields:\n" +
                               "{\n" +
                               "  \"is_code\": true/false,\n" +
                               "  \"detected_language\": \"language_name\",\n" +
                               "  \"suggested_extension\": \"py\"/\"java\"/\"xml\"/\"json\"/\"sh\"/\"kt\"/\"js\"/\"ts\"/\"cpp\"/\"html\"/\"txt\"\n" +
                               "}"
                    )
                )
            )
        )

        try {
            val response = service.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                Log.d(TAG, "Gemini response raw: $jsonText")
                val adapter = moshi.adapter(AnalysisResult::class.java)
                val result = adapter.fromJson(jsonText)
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing file with Gemini", e)
        }
        null
    }
}
