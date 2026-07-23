package com.geto.jido

import android.util.Log
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
 */
class MediaResolver(private val httpClient: OkHttpClient) {

    companion object {
        private const val TAG = "MediaResolver"
        // TODO: Update this after deploying the 'server' directory to Render
        private const val RENDER_SERVER_URL = "https://jido-povx.onrender.com/resolve?url="
    }

    private data class ApiConfig(
        val host: String,
        val buildRequest: (String) -> Request,
        val parseResponse: (String) -> String?
    )

    private val configs: Map<String, List<ApiConfig>> = mapOf(
        "Instagram" to listOf(
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
        "YouTube" to listOf(
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
        "TikTok" to listOf(
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
        "Pinterest" to listOf(
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
        "Spotify" to listOf(
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

    fun resolve(link: String, platform: String): String? {
        val platformConfigs = configs[platform] ?: run {
            Log.w(TAG, "No configurations for platform: $platform")
            return null
        }

        for (config in platformConfigs) {
            try {
                val requestBuilder = config.buildRequest(link).newBuilder()
                
                if (config.host.contains("rapidapi")) {
                    if (BuildConfig.RAPIDAPI_KEY.isBlank()) {
                        Log.e(TAG, "Skipping ${config.host} — RAPIDAPI_KEY is missing")
                        continue
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
                        } else {
                            Log.w(TAG, "API ${config.host} returned success but no URL found in body: $body")
                        }
                    } else {
                        val errorBody = response.body?.string().orEmpty()
                        Log.w(TAG, "API ${config.host} failed with code ${response.code}: $errorBody")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving with ${config.host}: ${e.message}")
            }
        }

        Log.e(TAG, "All API fallbacks failed for $platform link: $link")
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
