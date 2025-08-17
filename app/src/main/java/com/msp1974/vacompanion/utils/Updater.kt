package com.msp1974.vacompanion.utils

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.media.MediaScannerConnection.OnScanCompletedListener
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import kotlin.math.max


data class LatestRelease(
    var version: String,
    var downloadURL: String
)

class Version(inputVersion: String) : Comparable<Version> {

    var version: String
        private set

    override fun compareTo(other: Version) =
        (split() to other.split()).let {(thisParts, thatParts)->
            val length = max(thisParts.size, thatParts.size)
            for (i in 0 until length) {
                val thisPart = if (i < thisParts.size) thisParts[i].toInt() else 0
                val thatPart = if (i < thatParts.size) thatParts[i].toInt() else 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            0
        }

    init {
        require(inputVersion.matches("[0-9]+(\\.[0-9]+)*".toRegex())) { "Invalid version format" }
        version = inputVersion
    }

    private fun Version.split() = version.split(".").toTypedArray()
}


class Updater(val activity: Activity) {
    private val log = Logger()
    var latestRelease: LatestRelease = LatestRelease("0.0.0", "")

    fun getLatestRelease(forceUpdate: Boolean = true): LatestRelease {
        if (latestRelease.version == "0.0.0" || forceUpdate) {
            val data = githubApiGET(APPConfig.GITHUB_API_URL)
            latestRelease.version =
                data.getOrDefault("name", "0.0.0").toString().replace("v", "").replace("\"", "")
            try {
                val assets = data.getOrDefault("assets", null)
                if (assets != null) {
                    for (asset in assets as List<JsonObject>) {
                        if (asset.getOrDefault(
                                "content_type",
                                ""
                            ).toString()
                                .replace("\"", "") == "application/vnd.android.package-archive"
                        ) {
                            latestRelease.downloadURL =
                                asset.getOrDefault("browser_download_url", "").toString()
                                    .replace("\"", "")
                        }
                    }
                }
            } catch (e: Exception) {
                log.e(e.message.toString())
            }
        }
        return latestRelease
    }

    fun isUpdateAvailable(): Boolean {
        val latestRelease = getLatestRelease()
        if (latestRelease.version != "0.0.0") {
            val installed =  activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.toString()
            return Version(latestRelease.version) > Version(installed)
        }
        return false
    }

    fun requestDownload(callback: (uri: String) -> Unit) {
        try {
            if (latestRelease.downloadURL != "") {
                val request =
                    DownloadManager.Request(latestRelease.downloadURL.toUri())
                val downloadManager =
                    activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "vaca.apk")
                if (file.exists()) {
                    log.d("File exists: $file")
                    file.delete()
                }
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "vaca.apk")

                val downloadId = downloadManager.enqueue(request)
                waitForDownloadToComplete(downloadId, callback)
            }
        } catch (e: Exception) {
            log.e(e.message.toString())
        }
    }

    private fun waitForDownloadToComplete(id: Long, callback: (uri: String) -> Unit) {
        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor: Cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
        if (cursor.moveToNext()) {
            val colIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (colIdx >= 0) {
                val status = cursor.getInt(colIdx)
                when (status) {
                    DownloadManager.STATUS_FAILED -> {
                        log.e("APK download failed")
                        cursor.close()
                        callback("")
                        return
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uriColIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val localUri = cursor.getString(uriColIdx)
                        cursor.close()
                        log.d("APK download completed")
                        callback(getContentURIFromFile(localUri).toString())
                        return
                    }
                }
            }
        }
        cursor.close()
        Handler(Looper.getMainLooper()).postDelayed({
            waitForDownloadToComplete(id, callback)
        }, 1000)


    }

    private fun githubApiGET(url: String): JsonObject {
        val client = OkHttpClient()

        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AuthUtils.Companion.log.e("Unexpected code $response")
                return buildJsonObject { put("unexpected_code", response.code() ) }
            }
            try {
                val response = response.body()?.string()
                return JsonObject(Json.parseToJsonElement(response.toString()).jsonObject)
            } catch (e: Exception) {
                AuthUtils.Companion.log.e(e.message.toString())
                return buildJsonObject { put("error",e.message.toString() ) }
            }
        }
    }

    private fun getContentURIFromFile(file: String): Uri {
        val f = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val uri = FileProvider.getUriForFile(activity.applicationContext, activity.packageName + ".provider", File(f, "vaca.apk"))
        return uri
    }
}
