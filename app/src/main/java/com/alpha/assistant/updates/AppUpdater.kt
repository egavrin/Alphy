package com.alpha.assistant.updates

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.alpha.assistant.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal sealed interface UpdateState {
    data class Idle(@StringRes val error: Int? = null) : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: AppRelease, @StringRes val error: Int? = null) : UpdateState
    data class Downloading(val release: AppRelease, val percent: Int) : UpdateState
    data class Ready(
        val release: AppRelease,
        val file: File,
        val handoffPending: Boolean = false,
        @StringRes val error: Int? = null,
    ) : UpdateState
}

internal class AppUpdater(
    private val app: Application,
    private val scope: CoroutineScope,
    httpClient: OkHttpClient,
) {
    private val releases = GitHubReleases(httpClient)
    private val preferences = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val directory = File(app.cacheDir, "app-updates")
    private val cleanup = scope.launch(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() } }
    private val packageManager = app.packageManager
    val installedVersion: String = packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle())
    val state = mutableState.asStateFlow()
    private var downloadJob: Job? = null

    val canInstall: Boolean get() = packageManager.canRequestPackageInstalls()

    fun check(manual: Boolean = false) {
        val previous = state.value
        if (previous == UpdateState.Checking || previous is UpdateState.Downloading || previous is UpdateState.Ready) return
        val now = System.currentTimeMillis()
        val last = preferences.getLong("last_check", 0)
        if (!manual && last != 0L && now >= last && now - last < TimeUnit.DAYS.toMillis(1)) return
        preferences.edit().putLong("last_check", now).apply()
        mutableState.value = UpdateState.Checking
        scope.launch {
            mutableState.value = try {
                releases.latestAfter(installedVersion)?.let { UpdateState.Available(it) } ?: UpdateState.Current
            } catch (_: IOException) {
                failedCheck(previous)
            } catch (_: JSONException) {
                failedCheck(previous)
            }
        }
    }

    private fun failedCheck(previous: UpdateState): UpdateState =
        if (previous is UpdateState.Available) previous.copy(error = R.string.update_check_failed)
        else UpdateState.Idle(R.string.update_check_failed)

    fun download() {
        val release = (state.value as? UpdateState.Available)?.release ?: return
        val file = File(directory, "${UUID.randomUUID()}.apk")
        mutableState.value = UpdateState.Downloading(release, 0)
        downloadJob = scope.launch {
            var result: UpdateState = UpdateState.Available(release)
            try {
                cleanup.join()
                withContext(Dispatchers.IO) {
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache")
                }
                releases.download(release, file) { mutableState.value = UpdateState.Downloading(release, it) }
                withContext(Dispatchers.IO) { verifyApk(release, file) }
                result = UpdateState.Ready(release, file)
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvalidUpdate) {
                result = UpdateState.Available(release, R.string.update_invalid_apk)
            } catch (_: IOException) {
                result = UpdateState.Available(release, R.string.update_download_failed)
            } finally {
                if (result !is UpdateState.Ready) file.delete()
                mutableState.value = result
            }
        }
    }

    fun cancelDownload() {
        if (state.value is UpdateState.Downloading) downloadJob?.cancel()
    }

    fun discard() {
        val ready = state.value as? UpdateState.Ready ?: return
        if (ready.handoffPending) return
        ready.file.delete()
        mutableState.value = UpdateState.Available(ready.release)
    }

    fun handoff(launch: (Intent) -> Unit) {
        val ready = state.value as? UpdateState.Ready ?: return
        if (ready.handoffPending) return
        if (!ready.file.isFile) {
            mutableState.value = UpdateState.Available(ready.release, R.string.update_download_missing)
            return
        }
        try {
            val intent = if (canInstall) {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.app-update", ready.file)
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", app.packageName, null))
            }
            mutableState.value = ready.copy(handoffPending = true, error = null)
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            mutableState.value = ready.copy(error = R.string.update_installer_failed)
        } catch (_: SecurityException) {
            mutableState.value = ready.copy(error = R.string.update_installer_failed)
        } catch (_: IllegalArgumentException) {
            mutableState.value = ready.copy(error = R.string.update_installer_failed)
        }
    }

    fun handoffReturned() {
        val ready = state.value as? UpdateState.Ready ?: return
        mutableState.value = ready.copy(handoffPending = false)
    }

    fun close() {
        (state.value as? UpdateState.Ready)?.file?.delete()
    }

    private fun verifyApk(release: AppRelease, file: File) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed = packageManager.getPackageInfo(app.packageName, flags)
        val candidate = packageManager.getPackageArchiveInfo(file.path, flags) ?: throw InvalidUpdate()
        val currentSigners = installed.signingInfo?.apkContentsSigners.orEmpty().toSet()
        val candidateSigners = candidate.signingInfo?.apkContentsSigners.orEmpty().toSet()
        val minSdk = candidate.applicationInfo?.minSdkVersion ?: throw InvalidUpdate()
        if (candidate.packageName != installed.packageName ||
            candidate.versionName != release.versionName ||
            candidate.longVersionCode <= installed.longVersionCode ||
            minSdk > Build.VERSION.SDK_INT ||
            currentSigners.isEmpty() ||
            currentSigners != candidateSigners
        ) throw InvalidUpdate()
    }
}

internal class AppUpdateProvider : FileProvider()
