package com.gitgo.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import java.io.File

// FragmentActivity нужен для BiometricPrompt.
class MainActivity : FragmentActivity() {
    private val vm: MainVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GitGoTheme(vm.theme) { Root(vm) } }
    }

    override fun onStart() { super.onStart(); vm.onStarted() }
    override fun onStop() { super.onStop(); vm.onStopped() }
}

@Composable
fun Root(vm: MainVm) {
    var splash by rememberSaveable { mutableStateOf(true) }
    when {
        splash -> SplashScreen { splash = false }
        vm.locked -> LockScreen(vm)
        !vm.onboarded -> Onboarding { vm.finishOnboarding() }
        else -> when (val s = vm.screen) {
            Screen.Home -> HomeScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            Screen.Vault -> VaultScreen(vm)
            is Screen.Repo -> RepoScreen(vm, s.dir) { vm.screen = Screen.Home }
        }
    }
}

/** Терминал открывается в отдельном окне (отдельная задача в списке недавних). */
fun openTerminal(ctx: Context, dir: File?) {
    val i = Intent(ctx, TerminalActivity::class.java)
    dir?.let { i.putExtra("dir", it.absolutePath) }
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    ctx.startActivity(i)
}
