package com.example.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
)

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val APK_FILE_NAME = "voxlnk-update.apk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches the latest GitHub release and returns [UpdateInfo] if a newer build
     * exists, or null when already up-to-date / unable to check.
     *
     * Requires [BuildConfig.GITHUB_REPO] to be set (injected from CI as "owner/repo").
     * Local / debug builds where the env var was not provided will have an empty
     * slug and the check is silently skipped.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val repoSlug = BuildConfig.GITHUB_REPO
        if (repoSlug.isBlank()) {
            Log.d(TAG, "GITHUB_REPO not set — skipping update check")
            return@withContext null
        }

        val url = "https://api.github.com/repos/$repoSlug/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Update check HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                parseReleaseJson(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    private fun parseReleaseJson(json: String): UpdateInfo? {
        // Tag format: "v1.0.<run_number>" — extract the last numeric segment as versionCode.
        val tagName = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
            .find(json)?.groupValues?.get(1) ?: return null

        val remoteVersionCode = tagName
            .removePrefix("v")
            .split(".")
            .lastOrNull()
            ?.toIntOrNull() ?: return null

        if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
            Log.d(TAG, "Already up-to-date (local=${BuildConfig.VERSION_CODE}, remote=$remoteVersionCode)")
            return null
        }

        // Pick the first .apk asset
        val apkUrl = Regex(""""browser_download_url"\s*:\s*"([^"]+\.apk)"""")
            .find(json)?.groupValues?.get(1) ?: run {
            Log.w(TAG, "No APK asset found in release $tagName")
            return null
        }

        Log.i(TAG, "Update available: $tagName ($apkUrl)")
        return UpdateInfo(tagName, remoteVersionCode, apkUrl)
    }

    /**
     * Downloads the APK to the app cache dir, reporting download percentage via
     * [onProgress] (0–100). Returns the downloaded [File].
     */
    suspend fun downloadApk(info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(info.apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
                val body = response.body ?: error("Empty response body")
                val contentLength = body.contentLength()
                val outFile = File(context.cacheDir, APK_FILE_NAME)

                var written = 0L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(8_192)
                        var bytes: Int
                        while (input.read(buf).also { bytes = it } != -1) {
                            output.write(buf, 0, bytes)
                            written += bytes
                            if (contentLength > 0) {
                                onProgress((written * 100 / contentLength).toInt())
                            }
                        }
                    }
                }
                outFile
            }
        }

    /**
     * Launches the Android system package installer for the given APK file.
     * The caller must have the REQUEST_INSTALL_PACKAGES permission declared and
     * the user must have "Install unknown apps" enabled for this app.
     */
    fun installApk(apkFile: File) {
        val authority = "${context.packageName}.update.provider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
