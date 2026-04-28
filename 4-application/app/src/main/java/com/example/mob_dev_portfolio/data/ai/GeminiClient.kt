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
        appendLine("You are an assistant helping a user spot possible correlations between their symptoms, environmental factors, and recent health metrics, AND screen those patterns for red flags consistent with serious or life-threatening conditions.")
        appendLine("You never receive the user's name or exact date of birth; only an age range and (optionally) a coarse biological-sex bucket are provided.")
        appendLine("Health metric values below are already aggregated (7-day totals/averages, 24h-preceding windows) — never echo raw per-minute readings.")
        appendLine("Do not invent PII. You are not giving a diagnosis — you are surfacing *possibilities* the user should rule out with a clinician.")
        appendLine()
        appendLine("CLINICAL SCREENING — what to look for:")
        appendLine("- Patterns consistent with LIFE-THREATENING or SERIOUS conditions, including but not limited to:")
        appendLine("  cancers (unexplained weight loss, persistent fatigue, new lumps, blood in stool/urine, persistent cough, changing moles, post-menopausal bleeding),")
        appendLine("  cardiovascular events (chest pain, radiating arm/jaw pain, sudden breathlessness, syncope, palpitations with dizziness),")
        appendLine("  stroke / TIA (sudden weakness, facial droop, speech difficulty, sudden severe headache),")
        appendLine("  sepsis / serious infection (high fever with rigors, confusion, rapid breathing, mottled skin),")
        appendLine("  pulmonary embolism / DVT (unilateral calf swelling, sudden pleuritic chest pain, breathlessness),")
        appendLine("  diabetic emergencies, severe mental-health crises, meningitis, severe allergic reactions,")
        appendLine("  neurological red flags (new-onset severe headache, vision loss, seizure).")
        appendLine("- If any recent log or combination of logs/vitals looks consistent with one of these, name it explicitly as a POSSIBILITY to rule out — phrase tentatively (`*could be consistent with*`, `*worth ruling out*`).")
        appendLine("- If nothing looks concerning, say so plainly — do not invent conditions.")
        appendLine()
        appendLine("OUTPUT FORMAT — follow strictly:")
        appendLine("- Respond in GitHub-flavoured markdown.")
        appendLine("- START with one line of exactly this shape: `GUIDANCE: clear` OR `GUIDANCE: seek medical advice`.")
        appendLine("  Use `seek medical advice` if the recent logs show severe or worsening symptoms, or any pattern consistent with the red flags above; otherwise use `clear`.")
        appendLine("- Then use `## ` section headings in this order (omit a section only if it genuinely has nothing to say):")
        appendLine("  `## Patterns` — 2-4 bullets on what the data shows.")
        appendLine("  `## Possible conditions` — 1-4 bullets naming conditions that *could* explain the pattern. Always tentative. If truly nothing fits, write a single bullet: `- No specific conditions stood out from these logs.`")
        appendLine("  `## Suggestions` — 2-4 bullets of practical next steps (tracking, lifestyle, when to see a GP / call 111 / call 999).")
        appendLine("- End with one line of exactly this shape: `NHS_REFERENCE: Check https://www.nhs.uk for full symptom information on any condition mentioned above.`")
        appendLine("- Under each heading give concise bullet points starting with `- `.")
        appendLine("- Bold key terms with `**term**` and italicise tentative language with `*might*` / `*could*`.")
        appendLine("- Keep the whole response under 220 words.")
        appendLine("- Do NOT wrap anything in code fences or triple backticks.")
        appendLine("- Do NOT include tables.")
        appendLine("- The only link allowed is the `https://www.nhs.uk` reference in the NHS_REFERENCE line.")
        appendLine("- Do NOT preface with phrases like \"Sure, here is...\" — jump straight to the GUIDANCE line.")
        appendLine()
        appendLine("User age range: ${request.ageRange}")
        request.biologicalSex?.let { appendLine("Biological sex: $it") }
        appendLine()
        if (request.userContext.isNotBlank()) {
            appendLine("Additional context from the user:")
            appendLine(request.userContext.trim())
            appendLine()
        }
        // Known-diagnoses block. These are conditions the user has
        // already discussed with a clinician. We surface them so the
        // model treats linked symptoms as already-explained and frames
        // its observations around them rather than re-discovering them.
        // Symptoms the user has ticked as "reviewed & cleared" never
        // appear here OR in the log list below — they've been filtered
        // upstream in AnalysisService.
        if (request.knownDiagnoses.isNotEmpty()) {
            appendLine("Known diagnoses (already discussed with a clinician — treat as context, not as fresh findings):")
            request.knownDiagnoses.forEach { diagnosis ->
                append("- ")
                append(diagnosis.label)
                if (diagnosis.history.isEmpty()) {
                    appendLine()
                } else {
                    append(" — related logs: ")
                    appendLine(
                        diagnosis.history.joinToString(", ") { entry ->
                            "${entry.symptomName} (${entry.startIsoDate})"
                        },
                    )
                }
            }
            appendLine("When a recent symptom below is tagged `[known: <label>]` it belongs to one of these diagnoses. Mention it only if there's a notable change (severity spike, new trigger) — otherwise don't re-flag it as a fresh pattern.")
            appendLine()
        }
        // User-declared standing conditions — chronic / pre-existing
        // health issues the user told us about during onboarding or
        // via the Conditions settings screen. Surfaced alongside (but
        // distinct from) doctor-confirmed diagnoses so the model
        // doesn't conflate "doctor said you have X at this visit" with
        // "user told us they have X chronically". Linked symptoms
        // carry a parallel `[condition: <label>]` tag.
        if (request.userDeclaredConditions.isNotEmpty()) {
            appendLine("Standing health conditions the user has told us about (treat as background context, not fresh findings):")
            request.userDeclaredConditions.forEach { condition ->
                append("- ")
                append(condition.label)
                if (condition.history.isEmpty()) {
                    appendLine()
                } else {
                    append(" — related logs: ")
                    appendLine(
                        condition.history.joinToString(", ") { entry ->
                            "${entry.symptomName} (${entry.startIsoDate})"
                        },
                    )
                }
            }
            appendLine("When a recent symptom below is tagged `[condition: <label>]` it belongs to one of these. Same rule as known diagnoses: only flag if something has materially changed.")
            appendLine()
        }
        // Explicit signal when the user hasn't shared any Health Connect
        // data, so the model knows not to invent steps/sleep/vitals-based
        // observations. The healthSummary block below replaces this line
        // when present.
        if (request.healthSummary == null) {
            appendLine("Connected health data considered: NONE — the user has not shared any Health Connect readings. Do NOT reference any specific health metric (heart rate, sleep, steps, SpO₂, blood pressure, etc.); you have no data on those.")
            appendLine()
        }
        request.healthSummary?.let { summary ->
            // Be explicit that the list below is the ONLY health data the
            // model has access to. Without this guardrail the model will
            // sometimes pattern-complete on "your sleep pattern suggests
            // …" even when Sleep was disabled — the user reads that and
            // reasonably concludes we leaked their disabled metrics.
            appendLine("Connected health data considered: ${summary.includedMetrics.joinToString(", ")}")
            appendLine("IMPORTANT: treat the above list as exhaustive. Do NOT reference, infer, or mention any other health metric (e.g. heart rate, sleep, SpO₂, blood pressure) that is not in that list. If a metric isn't listed, you have no data on it.")
            if (summary.rolling7Day.isNotEmpty()) {
                appendLine("Rolling 7-day aggregates:")
                summary.rolling7Day.forEach { (label, value) ->
                    appendLine("- $label: $value")
                }
            }
            if (summary.bodyMeasurements.isNotEmpty()) {
                appendLine("Latest body measurements:")
                summary.bodyMeasurements.forEach { (label, value) ->
                    appendLine("- $label: $value")
                }
            }
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
                log.healthAggregate24h?.let { agg ->
                    if (agg.isNotEmpty()) {
                        append(" | 24h vitals: ")
                        append(agg.entries.joinToString(", ") { (k, v) -> "$k=$v" })
                    }
                }
                log.diagnosisLabel?.let { label ->
                    append(" | [known: ")
                    append(label)
                    append("]")
                }
                log.userConditionLabel?.let { label ->
                    append(" | [condition: ")
                    append(label)
                    append("]")
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
