package com.gitgo.app

import androidx.annotation.StringRes

/** Строка из ресурсов по текущему языку приложения (en / ru / zh). Работает и вне Compose. */
fun tr(@StringRes id: Int, vararg args: Any): String = GitGoApp.instance.getString(id, *args)
