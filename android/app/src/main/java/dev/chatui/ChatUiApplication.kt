package dev.chatui

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Application entry point that owns the Hilt singleton component. */
@HiltAndroidApp
class ChatUiApplication : Application()
