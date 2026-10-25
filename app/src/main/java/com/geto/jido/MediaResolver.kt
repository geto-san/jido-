package com.geto.jido

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Handles resolving source links (YouTube, TikTok, etc.) to direct media URLs
 * using various RapidAPI endpoints and Cobalt with fallback logic.
 *
 * Performance note: [resolve] races every fallback endpoint for a platform
 * concurrently and returns as soon as the first one succeeds, instead of
 * trying them one at a time. A dead or slow endpoint used to block the whole
 * pipeline for up to its full timeout before the next fallback even started;
 * now a fast endpoint wins immediately regardless of its position in the
 * list, and the stragglers are cancelled once we have a winner.
 */
class MediaResolver(private val httpClient: OkHttpClient) {

    companion object {
        private const val TAG = "MediaResolver"
        // Legacy Python service. Kept as the last-resort fallback because it
        // still answers unauthenticated GET /resolve?url=, so it keeps working
        // for users who have no backend token configured.
        private const val RENDER_SERVER_URL = "https://jido-povx.onrender.com/resolve?url="

        /**
         * Base URL of the Java backend, from local.properties. Empty when unset.
         * Read once here rather than per-call so a missing value is a single
         * cheap check on the hot path instead of a property read per resolve.
         */
        private val backendUrl: String = BuildConfig.JIDO_BACKEND_URL.trimEnd('/')
        private val backendToken: String = BuildConfig.JIDO_AUTH_TOKEN
    }

    /** True when a backend URL and token are both configured. */
    private val backendAvailable: Boolean
        get() = backendUrl.isNotBlank() && backendToken.isNotBlank()

    /**
     * Calls the Java backend's POST /api/v1/resolve, which returns
     * `{"url": "...", "platform": ..., "title": ...}`.
     *
     * The old Render fallback is a GET that echoes a bare URL, so this is a
     * separate request builder rather than another entry in [configs]: the
     * method, path, body shape, and auth all differ.
     */
    private fun jidoBackend(): ApiConfig? {
        if (!backendAvailable) {
            Log.d(TAG, "Jido backend not configured (url or token blank), skipping")
            return null
        }
        return ApiConfig(
            host = "jido-backend",
            buildRequest = { link ->
                val body = JSONObject().put("url", link).toString()
                    .toRequestBody("application/json".toMediaType())
                Request.Builder()
                    .url("$backendUrl/api/v1/resolve")
                    .post(body)
                    .header("Authorization", "Bearer $backendToken")
                    .header("Accept", "application/json")
                    .build()
            },
            parseResponse = { body ->
                JSONObject(body).optString("url").takeIf { it.isValidUrl() }
            }
        )
    }

    /** Backend first (our own infra), then the existing third-party fallbacks. */
    private fun withBackend(vararg fallbacks: ApiConfig): List<ApiConfig> {
        val backend = jidoBackend()
        return if (backend == null) fallbacks.toList() else listOf(backend) + fallbacks
    }

    private data class ApiConfig(
        val host: String,
        val buildRequest: (String) -> Request,
        val parseResponse: (String) -> String?
    )

    private val configs: Map<String, List<ApiConfig>> = mapOf(
        "Instagram" to withBackend(
            ApiConfig(
                host = "api.cobalt.tools",
                buildRequest = { link ->
                    val jsonBody = JSONObject().put("url", link)
                    val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
                    Request.Builder()
                        .url("https://api.cobalt.tools/")
                        .post(body)
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                },
                parseResponse = { body -> parseStandardJson(body) }
            ),
            ApiConfig(
                host = "instagram120.p.rapidapi.com",
                buildRequest = { link ->
                    val shortcode = Regex("""(?:p|reels|reel)/([^/?#&]+)""").find(link)?.groupValues?.get(1)
                        ?: throw IllegalArgumentException("Could not extract shortcode from $link")
                    val jsonBody = JSONObject().put("shortcode", shortcode).toString()
                    val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                    Request.Builder()
                        .url("https://instagram120.p.rapidapi.com/api/instagram/mediaByShortcode")
                        .post(body)
                        .build()
                },
                parseResponse = { body -> parseStandardJson(body) }
            ),
            renderFallback()
        ),
        "YouTube" to withBackend(
            ApiConfig(
                host = "youtube-v2.p.rapidapi.com",
                buildRequest = { link ->
                    val videoId = Regex("""(?:v=|v/|youtu\.be/|embed/|shorts/)([^/?#&]+)""").find(link)?.groupValues?.get(1)
                        ?: throw IllegalArgumentException("Could not extract video ID from $link")
                    Request.Builder()
                        .url("https://youtube-v2.p.rapidapi.com/video/details?video_id=${videoId}")
                        .get()
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                },
                parseResponse = { body ->
                    val json = JSONObject(body)
                    val mp4Urls = json.optJSONArray("mp4_urls")
                    val result = if (mp4Urls != null && mp4Urls.length() > 0) {
                        mp4Urls.optJSONObject(0)?.optString("url")
                    } else {
                        parseStandardJson(body)
                    }
                    if (result.isValidUrl()) result else null
                }
            ),
            ApiConfig(
                host = "api.cobalt.tools",
                buildRequest = { link ->
                    val jsonBody = JSONObject().put("url", link)
                    val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
                    Request.Builder()
                        .url("https://api.cobalt.tools/")
                        .post(body)
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                },
                parseResponse = { body -> parseStandardJson(body) }
            ),
            ApiConfig(
                host = "youtube-info-download-api.p.rapidapi.com",
                buildRequest = { link ->
                    val encodedUrl = URLEncoder.encode(link, "UTF-8")
                    Request.Builder()
                        .url("https://youtube-info-download-api.p.rapidapi.com/ajax/download.php?format=mp3&add_info=0&url=${encodedUrl}&audio_quality=128")
                        .get()
                        .build()
                },
                parseResponse = { body ->
                    val json = JSONObject(body)
                    if (json.optBoolean("success")) {
                        var downloadUrl = json.optString("url").takeIf { it.isNotBlank() }
                        if (downloadUrl == null) {
                            val progressUrl = json.optString("progress_url")
                            if (progressUrl.isNotBlank()) {
                                var i = 0
                                while (downloadUrl == null && i < 10) {
                                    Thread.sleep(2000)
                                    Log.d(TAG, "Polling YouTube progress (attempt ${i + 1})...")
                                    try {
                                        val pollRequest = Request.Builder()
                                            .url(progressUrl)
                                            .addHeader("x-rapidapi-key", BuildConfig.RAPIDAPI_KEY)
                                            .addHeader("x-rapidapi-host", "youtube-info-download-api.p.rapidapi.com")
                                            .build()
                                        httpClient.newCall(pollRequest).execute().use { resp ->
                                            if (resp.isSuccessful) {
                                                val pollBody = resp.body?.string().orEmpty()
                                                val pollJson = JSONObject(pollBody)
                                                val pUrl = pollJson.optString("url")
                                                if (pUrl.isValidUrl()) {
                                                    downloadUrl = pUrl
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Polling attempt ${i+1} failed: ${e.message}")
                                    }
                                    i++
                                }
                            }
                        }
                        if (downloadUrl.isValidUrl()) downloadUrl else null
                    } else null
                }
            ),
            renderFallback()
        ),
        "TikTok" to withBackend(
            ApiConfig(
                host = "www.tikwm.com",
                buildRequest = { link ->
                    val encodedUrl = URLEncoder.encode(link, "UTF-8")
                    Request.Builder()
                        .url("https://www.tikwm.com/api/?url=${encodedUrl}")
                        .get()
                        .build()
                },
                parseResponse = { body ->
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data")
                    val result = data?.optString("play") ?: data?.optString("hdplay") ?: parseStandardJson(body)
                    if (result.isValidUrl()) result else null
                }
            ),
            ApiConfig(
                host = "api.cobalt.tools",
                buildRequest = { link ->
                    val jsonBody = JSONObject().put("url", link)
                    val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
                    Request.Builder()
                        .url("https://api.cobalt.tools/")
                        .post(body)
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                },
                parseResponse = { body -> parseStandardJson(body) }
            ),
            renderFallback()
        ),
        "Pinterest" to withBackend(
            ApiConfig(
                host = "api.cobalt.tools",
                buildRequest = { link ->
                    val jsonBody = JSONObject().put("url", link)
                    val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
                    Request.Builder()
                        .url("https://api.cobalt.tools/")
                        .post(body)
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                },
                parseResponse = { body -> parseStandardJson(body) }
            ),
            renderFallback()
        ),
        "Spotify" to withBackend(
            ApiConfig(
                host = "api.cobalt.tools",
                buildRequest = { link ->
                    val jsonBody = JSONObject().put("url", link)
                    val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
                    Request.Builder()
                        .url("https://api.cobalt.tools/")
                        .post(body)
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                },
                parseResponse = { body -> parseStandardJson(body) }
            ),
            renderFallback()
        )
    )

    private fun renderFallback() = ApiConfig(
        host = "render-fallback",
        buildRequest = { link ->
            val encodedUrl = URLEncoder.encode(link, "UTF-8")
            Request.Builder().url(RENDER_SERVER_URL + encodedUrl).get().build()
        },
        parseResponse = { body -> parseStandardJson(body) }
    )

    /**
     * Races every configured fallback for [platform] concurrently and returns the
     * first successfully-resolved direct URL, cancelling the rest as soon as we
     * have a winner. A slow or dead endpoint no longer blocks faster ones behind
     * it in the fallback list — previously this loop tried them strictly in
     * order, so a hung first choice could eat its full timeout before the next
     * fallback even started.
     */
    suspend fun resolve(link: String, platform: String): String? = coroutineScope {
        val platformConfigs = configs[platform] ?: run {
            Log.w(TAG, "No configurations for platform: $platform")
            return@coroutineScope null
        }

        // Each fallback runs on its own coroutine and pushes its result (or null)
        // onto a shared channel as soon as it finishes — buffered so a slow
        // straggler never blocks the ones that already finished from reporting in.
        val resultChannel = Channel<String?>(capacity = platformConfigs.size)
        val jobs = platformConfigs.map { config ->
            launch(Dispatchers.IO) {
                val result = runCatching { tryConfig(config, link, platform) }.getOrNull()
                resultChannel.trySend(result)
            }
        }

        var winner: String? = null
        var received = 0
        while (winner == null && received < platformConfigs.size) {
            val result = resultChannel.receive()
            received++
            if (!result.isNullOrBlank()) winner = result
        }

        // Once we have a winner (or have exhausted every fallback), stop any
        // still-in-flight requests instead of letting them run to completion.
        jobs.forEach { it.cancel() }

        if (winner == null) Log.e(TAG, "All API fallbacks failed for $platform link: $link")
        winner
    }

    /** Runs one API fallback attempt; returns the parsed direct URL, or null on failure. */
    private fun tryConfig(config: ApiConfig, link: String, platform: String): String? {
        val requestBuilder = config.buildRequest(link).newBuilder()

        if (config.host.contains("rapidapi")) {
            if (BuildConfig.RAPIDAPI_KEY.isBlank()) {
                Log.e(TAG, "Skipping ${config.host} — RAPIDAPI_KEY is missing")
                return null
            }
            requestBuilder
                .addHeader("x-rapidapi-key", BuildConfig.RAPIDAPI_KEY)
                .addHeader("x-rapidapi-host", config.host)
                .addHeader("Content-Type", "application/json")
        }

        val request = requestBuilder.build()
        Log.d(TAG, "Trying $platform API: ${config.host}")

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val directUrl = config.parseResponse(body)
                if (!directUrl.isNullOrBlank()) {
                    Log.i(TAG, "Successfully resolved $platform link via ${config.host}")
                    return directUrl
                }
                Log.w(TAG, "API ${config.host} returned success but no URL found in body: $body")
            } else {
                val errorBody = response.body?.string().orEmpty()
                Log.w(TAG, "API ${config.host} failed with code ${response.code}: $errorBody")
            }
        }
        return null
    }

    private fun String?.isValidUrl(): Boolean = !this.isNullOrBlank() && this != "null" && (this.startsWith("http://") || this.startsWith("https://"))

    private fun parseStandardJson(responseBody: String): String? = try {
        val result = if (responseBody.trim().startsWith("[")) {
            val jsonArray = JSONArray(responseBody)
            jsonArray.optJSONObject(0)
                ?.optJSONArray("urls")
                ?.optJSONObject(0)
                ?.optString("url")
        } else {
            val json = JSONObject(responseBody)
            // Top level fields
            json.optString("url").ifBlank { null }
                ?: json.optString("download_url").ifBlank { null }
                ?: json.optString("link").ifBlank { null }
                ?: json.optString("direct_link").ifBlank { null }
                // data object
                ?: json.optJSONObject("data")?.optString("url")?.ifBlank { null }
                ?: json.optJSONObject("data")?.optString("link")?.ifBlank { null }
                ?: json.optJSONObject("data")?.optString("download_url")?.ifBlank { null }
                ?: json.optJSONObject("data")?.optString("hdplay")?.ifBlank { null }
                ?: json.optJSONObject("data")?.optString("play")?.ifBlank { null }
                // result object
                ?: json.optJSONObject("result")?.optString("url")?.ifBlank { null }
                ?: json.optJSONObject("result")?.optString("link")?.ifBlank { null }
                // arrays
                ?: json.optJSONArray("medias")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
                ?: json.optJSONArray("items")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
                ?: json.optJSONArray("urls")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
                ?: json.optJSONArray("formats")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
                // cobalt picker object
                ?: json.optJSONArray("picker")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
                // nested links object
                ?: json.optJSONObject("links")?.optJSONArray("mp4")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
                ?: json.optJSONObject("links")?.optJSONArray("mp3")?.optJSONObject(0)?.optString("url")?.ifBlank { null }
        }
        if (result.isValidUrl()) result else null
    } catch (ignored: Exception) {
        null
    }
}
