package com.example.mob_dev_portfolio.data.ai

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Thin abstraction over the Gemini `generateContent` endpoint.
 *
 * A dedicated interface buys us two things:
 *   1. The ViewModel depends only on this shape, so unit tests can swap in
 *      a fake without standing up MockWebServer.
 *   2. The real implementation is free to swap transports (OkHttp, Ktor,
 *      the official SDK if we adopt it later) without rippling changes
 *      through the ViewModel layer.
 */
interface GeminiClient {
    suspend fun analyze(request: AnalysisRequest): AnalysisResult
}

/**
 * Wire-level Gemini adapter.
 *
 * Endpoint shape (v1beta generateContent):
 * ```
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={KEY}
 * Body: { "contents": [ { "parts": [ { "text": "<prompt>" } ] } ] }
 * ```
 *
 * We send the full sanitized payload as a single prompt string rather than
 * as structured tool input because:
 *   - the UI only needs a human-readable summary back,
 *   - tool-calling / structured output adds complexity that isn't needed
 *     for a cost-effective "give me a health correlation summary" call,
 *   - the prompt is authored here so the instructions about privacy and
 *     disclaimers are version-controlled alongside the client.
 *
 * The API key is injected rather than read directly from BuildConfig so
 * tests can point at MockWebServer with a fake key, and so a collaborator
 * without the key sees a [AnalysisResult.ApiError] with a clear message
 * instead of a cryptic 400.
 */
class HttpGeminiClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GeminiClient {

    override suspend fun analyze(request: AnalysisRequest): AnalysisResult =
        withContext(ioDispatcher) {
            if (apiKey.isBlank()) {
                return@withContext AnalysisResult.ApiError(
                    "Gemini API key not configured. Add GEMINI_API_KEY to local.properties and rebuild.",
                )
            }
            val prompt = buildPrompt(request)
            val bodyJson = buildBodyJson(prompt)
            val url = "$baseUrl/models/$model:generateContent?key=${urlEncode(apiKey)}"
            val httpRequest = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            try {
                client.newCall(httpRequest).execute().use { response: Response ->
                    if (!response.isSuccessful) {
                        return@withContext AnalysisResult.ApiError(
                            "Gemini error (${response.code}). Please try again.",
                        )
                    }
                    val rawBody = response.body?.string()
                        ?: return@withContext AnalysisResult.ApiError("Empty response from Gemini.")
                    parseSummary(rawBody)
                }
            } catch (_: UnknownHostException) {
                AnalysisResult.NoNetwork
            } catch (_: SocketTimeoutException) {
                AnalysisResult.Timeout
            } catch (error: IOException) {
                Log.w(TAG, "Gemini IO failure", error)
                AnalysisResult.NoNetwork
            } catch (error: JSONException) {
                Log.w(TAG, "Gemini parse failure", error)
                AnalysisResult.ApiError("Couldn't read the AI response.")
            } catch (error: Exception) {
                Log.w(TAG, "Gemini unexpected failure", error)
                AnalysisResult.ApiError("Unexpected error: ${error.message ?: "unknown"}.")
            }
        }

    private fun parseSummary(rawBody: String): AnalysisResult {
        val json = JSONObject(rawBody)
        val candidates = json.optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) {
            return AnalysisResult.ApiError("Gemini returned no candidates.")
        }
        val parts = candidates
            .optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: JSONArray()
        val combined = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val piece = part.optString("text", "")
                if (piece.isNotEmpty()) append(piece)
            }
        }.trim()
        return if (combined.isEmpty()) {
            AnalysisResult.ApiError("Gemini returned an empty response.")
        } else {
            AnalysisResult.Success(combined)
        }
    }

    private fun buildBodyJson(prompt: String): JSONObject {
        val part = JSONObject().put("text", prompt)
        val parts = JSONArray().put(part)
        val content = JSONObject().put("parts", parts)
        val contents = JSONArray().put(content)
        return JSONObject().put("contents", contents)
    }

    /**
     * The prompt is assembled as plain text from already-sanitized inputs. No
     * name, no DOB, no latitude/longitude — only the age bucket, the user's
     * free-text context, and anonymised symptom rows reach this method.
     */
    /**
     * The prompt is tuned for the in-app markdown renderer
     * ([com.example.mob_dev_portfolio.ui.analysis.MarkdownContent]) which
     * understands a deliberately small grammar: `##`/`###` headings, `- `
     * bullets, and `**bold**` / `*italic*` inline markers. We spell out the
     * output contract here so the user sees a tidy, scannable card rather
     * than a wall of prose with raw asterisks in it.
     */
    private fun buildPrompt(request: AnalysisRequest): String = buildString {
        appendLine("You are an assistant helping a user spot possible correlations between their symptoms and environmental factors.")
        appendLine("You never receive the user's name or exact date of birth; only an age range is provided.")
        appendLine("Do not invent PII. Avoid medical diagnoses.")
        appendLine()
        appendLine("OUTPUT FORMAT — follow strictly:")
        appendLine("- Respond in GitHub-flavoured markdown.")
        appendLine("- Use `## ` for up to three short section headings (e.g. `## Patterns`, `## Possible triggers`, `## Suggestions`).")
        appendLine("- Under each heading give 2-4 concise bullet points starting with `- `.")
        appendLine("- Bold key terms with `**term**` and italicise tentative language with `*might*` / `*could*`.")
        appendLine("- Keep the whole response under 180 words.")
        appendLine("- Do NOT wrap anything in code fences or triple backticks.")
        appendLine("- Do NOT include tables or links.")
        appendLine("- Do NOT preface with phrases like \"Sure, here is...\" — jump straight to the first heading.")
        appendLine()
        appendLine("User age range: ${request.ageRange}")
        appendLine()
        if (request.userContext.isNotBlank()) {
            appendLine("Additional context from the user:")
            appendLine(request.userContext.trim())
            appendLine()
        }
        appendLine("Recent symptom logs (most recent first):")
        if (request.logs.isEmpty()) {
            appendLine("- (no logs available)")
        } else {
            request.logs.forEach { log ->
                append("- ")
                append(log.startIsoDate)
                append(" | ")
                append(log.symptomName)
                append(" | severity ")
                append(log.severity)
                log.weatherDescription?.let { append(" | weather: $it") }
                log.temperatureCelsius?.let { append(" | temp: ${"%.1f".format(it)}°C") }
                log.humidityPercent?.let { append(" | humidity: $it%") }
                log.pressureHpa?.let { append(" | pressure: ${"%.1f".format(it)} hPa") }
                log.airQualityIndex?.let { append(" | AQI: $it") }
                if (log.contextTags.isNotEmpty()) {
                    append(" | tags: ${log.contextTags.joinToString(",")}")
                }
                if (log.notes.isNotBlank()) {
                    append(" | notes: ${log.notes.replace('\n', ' ')}")
                }
                appendLine()
            }
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "GeminiClient"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
