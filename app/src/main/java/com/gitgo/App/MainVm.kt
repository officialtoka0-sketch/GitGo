package com.gitgo.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Vault : Screen
    data class Repo(val dir: File) : Screen
}

class MainVm(app: Application) : AndroidViewModel(app) {
    private val g = app as GitGoApp
    val prefs: Prefs get() = g.prefs
    val ops = GitOps(app, g.prefs)

    var screen: Screen by mutableStateOf(Screen.Home)
    var theme by mutableStateOf(runCatching { ThemeKind.valueOf(g.prefs.themeName) }.getOrDefault(ThemeKind.DARK))
    var localRepos by mutableStateOf(listOf<File>())
    var remoteRepos by mutableStateOf(listOf<RemoteRepo>())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loggedIn by mutableStateOf(g.prefs.hasToken)
    var locked by mutableStateOf(g.prefs.vaultEnabled)
    private var stoppedAt = 0L
    var login by mutableStateOf(g.prefs.login)
    var onboarded by mutableStateOf(g.prefs.onboarded)

    fun unlock(activity: FragmentActivity) {
        Vault.unlock(activity, prefs) { err ->
            if (err == null) {
                locked = false; error = null
                loadRemote()
                WidgetRefresh.launch(getApplication(), prefs.token)
            } else error = err
        }
    }

    /** Уходим из приложения: обновляем виджеты, пока токен в памяти, и запоминаем время для автоблокировки. */
    fun onStopped() {
        if (locked) return
        WidgetRefresh.launch(getApplication(), prefs.token)
        if (prefs.vaultEnabled) stoppedAt = SystemClock.elapsedRealtime()
    }

    /** Возвращаемся: если вне приложения были дольше минуты и нет активной передачи — блокируем. */
    fun onStarted() {
        if (!prefs.vaultEnabled || locked) return
        val running = TransferState.current.value?.state == Progress.State.RUNNING
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 60_000 && !running) {
            prefs.sessionToken = null
            locked = true
        }
        stoppedAt = 0
    }

    fun finishOnboarding() { prefs.onboarded = true; onboarded = true }

    fun updateTheme(k: ThemeKind) { theme = k; prefs.themeName = k.name }

    fun refreshLocal() {
        viewModelScope.launch { localRepos = withContext(Dispatchers.IO) { ops.repos() } }
    }

    fun signIn(token: String) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val me = withContext(Dispatchers.IO) { GitHubApi(token).me() }
                prefs.token = token; prefs.login = me.login; prefs.userId = me.id
                loggedIn = true; login = me.login
                loadRemote()
            } catch (e: Exception) {
                error = tr(R.string.err_signin, e.message ?: "")
            } finally {
                loading = false
            }
        }
    }

    fun signOut() {
        prefs.clearAuth()
        prefs.pinnedRepo = ""
        val app = getApplication<Application>()
        WidgetStore.sp(app).edit().clear().apply()
        WidgetRefresh.updateAll(app)
        loggedIn = false; login = ""; remoteRepos = emptyList(); locked = false; error = null
    }

    fun loadRemote() {
        if (prefs.token.isBlank()) return
        viewModelScope.launch {
            loading = true; error = null
            try {
                remoteRepos = withContext(Dispatchers.IO) { GitHubApi(prefs.token).listRepos() }
            } catch (e: Exception) {
                error = tr(R.string.err_repos, e.message ?: "")
            } finally {
                loading = false
            }
        }
    }

    fun clone(r: RemoteRepo) {
        JobQueue.submit(getApplication(), tr(R.string.op_cloning, r.name)) { ctl ->
            ops.cloneRepo(r.cloneUrl, r.name, r.defaultBranch, ctl)
            tr(R.string.job_cloned, r.name)
        }
    }

    fun createRepo(name: String, priv: Boolean, desc: String) {
        JobQueue.submit(getApplication(), tr(R.string.job_creating, name)) { ctl ->
            val r = GitHubApi(prefs.token).createRepo(name, priv, desc)
            ops.cloneRepo(r.cloneUrl, r.name, r.defaultBranch, ctl)
            loadRemote()
            tr(R.string.job_created, r.name)
        }
    }

    fun deleteLocal(dir: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dir.deleteTree() }
            refreshLocal()
        }
    }
}
