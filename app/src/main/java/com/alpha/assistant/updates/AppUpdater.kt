package com.alpha.assistant.updates

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.PersistableBundle
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal sealed interface UpdateState {
    data class Idle(@StringRes val error: Int? = null) : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: AppRelease, @StringRes val error: Int? = null) : UpdateState
    data class Downloading(val release: AppRelease, val percent: Int, val waiting: Boolean = false) : UpdateState
    data class Ready(
        val release: AppRelease,
        val file: File,
        val handoffPending: Boolean = false,
        @StringRes val error: Int? = null,
    ) : UpdateState
}

internal class AppUpdater(
    private val app: Application,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    internal interface Source {
        fun updateFixture(): AppUpdater?
    }

    private data class Transfer(val id: String, val release: AppRelease, val ready: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val releases = GitHubReleases(httpClient)
    private val preferences = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, "app-updates")
    private val packageManager = app.packageManager
    private val scheduler = app.getSystemService(JobScheduler::class.java).forNamespace("app-updates")
    private val writer = Mutex()
    val installedVersion: String = packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
    private var transfer = readTransfer()
    private var attempt: Any? = null
    private val mutableState = MutableStateFlow<UpdateState>(
        transfer?.let { UpdateState.Downloading(it.release, 0, waiting = true) } ?: UpdateState.Idle(),
    )
    val state = mutableState.asStateFlow()
    private val cleanup = transfer?.let(::file).let { keep ->
        scope.launch(Dispatchers.IO) {
            directory.listFiles()?.filter { it != keep }?.forEach { it.delete() }
        }
    }

    init {
        val saved = transfer
        if (saved != null) scope.launch { restore(saved) }
    }

    val canInstall: Boolean get() = packageManager.canRequestPackageInstalls()

    fun check(manual: Boolean = false) {
        val previous = state.value
        if (previous is UpdateState.Downloading && previous.waiting && transfer?.ready == false &&
            scheduler.getPendingJob(JOB_ID) == null
        ) {
            mutableState.value = UpdateState.Available(previous.release, R.string.update_download_failed)
            if (!manual) return
        }
        if (state.value == UpdateState.Checking || state.value is UpdateState.Downloading || state.value is UpdateState.Ready) return
        val now = System.currentTimeMillis()
        val last = preferences.getLong("last_check", 0)
        if (!manual && last != 0L && now >= last && now - last < TimeUnit.DAYS.toMillis(1)) return
        preferences.edit().putLong("last_check", now).apply()
        val available = state.value as? UpdateState.Available
        mutableState.value = UpdateState.Checking
        scope.launch {
            mutableState.value = try {
                releases.latestAfter(installedVersion)?.let { UpdateState.Available(it) } ?: UpdateState.Current
            } catch (_: IOException) {
                failedCheck(available)
            } catch (_: JSONException) {
                failedCheck(available)
            }
        }
    }

    private fun failedCheck(previous: UpdateState.Available?): UpdateState =
        previous?.copy(error = R.string.update_check_failed) ?: UpdateState.Idle(R.string.update_check_failed)

    fun download() {
        val release = (state.value as? UpdateState.Available)?.release ?: return
        val previous = transfer
        val saved = previous?.takeIf {
            !it.ready && it.release.tag == release.tag && it.release.digest == release.digest && it.release.size == release.size
        } ?: Transfer(UUID.randomUUID().toString(), release)
        if (!save(saved)) {
            mutableState.value = UpdateState.Available(release, R.string.update_download_failed)
            return
        }
        if (previous != null && previous.id != saved.id) deleteFile(previous)
        val job = JobInfo.Builder(JOB_ID, ComponentName(app, AppUpdateJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes((release.size - file(saved).length()).coerceAtLeast(0), 0)
            .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .setExtras(PersistableBundle().apply { putString(TRANSFER_ID, saved.id) })
            .build()
        mutableState.value = try {
            if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) waiting(saved)
            else UpdateState.Available(release, R.string.update_download_failed)
        } catch (_: IllegalStateException) {
            UpdateState.Available(release, R.string.update_download_failed)
        }
    }

    suspend fun runTransfer(id: String): Boolean = writer.withLock {
        val saved = transfer?.takeIf { it.id == id && !it.ready } ?: return@withLock false
        if (!saved.release.isNewerThan(installedVersion)) {
            forget(saved, UpdateState.Current)
            return@withLock false
        }
        val token = Any()
        attempt = token
        val release = saved.release
        val file = file(saved)
        mutableState.value = UpdateState.Downloading(release, progress(saved))
        try {
            cleanup.join()
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update directory")
            }
            var retry = true
            while (true) {
                try {
                    releases.download(release, file) { percent ->
                        scope.launch {
                            if (attempt === token) mutableState.value = UpdateState.Downloading(release, percent)
                        }
                    }
                    break
                } catch (error: RetryUpdate) {
                    if (!retry) throw error
                    retry = false
                    mutableState.value = waiting(saved)
                    delay(1_000)
                }
            }
            validate(saved)
            if (transfer?.id == id) {
                if (!save(saved.copy(ready = true))) throw IOException("Cannot save update status")
                mutableState.value = UpdateState.Ready(release, file)
            }
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: RetryUpdate) {
            pause(id)
        } catch (_: InvalidUpdate) {
            forget(saved, UpdateState.Available(release, R.string.update_invalid_apk))
            false
        } catch (_: IOException) {
            if (transfer?.id == id) mutableState.value = UpdateState.Available(release, R.string.update_download_failed)
            false
        } finally {
            if (attempt === token) attempt = null
        }
    }

    fun pause(id: String): Boolean {
        val saved = transfer?.takeIf { it.id == id && !it.ready } ?: return false
        attempt = null
        mutableState.value = waiting(saved)
        return true
    }

    fun cancelDownload() {
        val saved = transfer?.takeUnless { it.ready } ?: return
        if (forget(saved, UpdateState.Available(saved.release))) scheduler.cancel(JOB_ID)
    }

    fun discard() {
        val ready = state.value as? UpdateState.Ready ?: return
        if (ready.handoffPending) return
        transfer?.let { forget(it, UpdateState.Available(ready.release)) }
    }

    private suspend fun restore(saved: Transfer) {
        if (!saved.release.isNewerThan(installedVersion)) {
            if (forget(saved, UpdateState.Current)) scheduler.cancel(JOB_ID)
        } else if (saved.ready) {
            try {
                validate(saved)
                if (transfer == saved) mutableState.value = UpdateState.Ready(saved.release, file(saved))
            } catch (_: IOException) {
                forget(saved, UpdateState.Available(saved.release, R.string.update_invalid_apk))
            }
        } else if (attempt == null && transfer == saved) {
            mutableState.value = if (scheduler.getPendingJob(JOB_ID) != null) waiting(saved)
            else UpdateState.Available(saved.release, R.string.update_download_failed)
        }
    }

    private suspend fun validate(saved: Transfer) {
        val file = file(saved)
        releases.verifyDigest(saved.release, file)
        withContext(Dispatchers.IO) { verifyApk(saved.release, file) }
    }

    private fun file(saved: Transfer) = File(directory, "${saved.id}.apk")

    private fun progress(saved: Transfer) =
        (file(saved).length() * 100.0 / saved.release.size).toInt().coerceIn(0, 100)

    private fun waiting(saved: Transfer) = UpdateState.Downloading(saved.release, progress(saved), waiting = true)

    private fun deleteFile(saved: Transfer) {
        scope.launch { writer.withLock { withContext(Dispatchers.IO) { file(saved).delete() } } }
    }

    private fun forget(saved: Transfer, next: UpdateState): Boolean {
        if (transfer?.id != saved.id || !save(null)) return false
        attempt = null
        mutableState.value = next
        deleteFile(saved)
        return true
    }

    private fun save(saved: Transfer?): Boolean {
        val value = saved?.let {
            JSONObject().put("id", it.id).put("tag", it.release.tag).put("notes", it.release.notes)
                .put("size", it.release.size).put("digest", it.release.digest).put("ready", it.ready).toString()
        }
        if (!preferences.edit().putString("transfer", value).commit()) return false
        transfer = saved
        return true
    }

    private fun readTransfer(): Transfer? {
        val raw = preferences.getString("transfer", null) ?: return null
        return try {
            val data = JSONObject(raw)
            val id = data.getString("id")
            if (UUID.fromString(id).toString() != id) return null
            val release = AppRelease.restore(data.getString("tag"), data.getString("notes"),
                data.getLong("size"), data.getString("digest")) ?: return null
            Transfer(id, release, data.getBoolean("ready"))
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun handoff(launch: (Intent) -> Unit) {
        val ready = state.value as? UpdateState.Ready ?: return
        if (ready.handoffPending) return
        if (!ready.file.isFile) {
            transfer?.let { forget(it, UpdateState.Available(ready.release, R.string.update_download_missing)) }
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

    companion object {
        const val JOB_ID = 1
        const val TRANSFER_ID = "transfer"

        private var instance: AppUpdater? = null

        fun get(app: Application): AppUpdater =
            (app as? Source)?.updateFixture() ?: instance ?: AppUpdater(app).also { instance = it }
    }
}

internal class AppUpdateProvider : FileProvider()
