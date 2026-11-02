package dev.chatui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ShellManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `manifest registers the hilt application`() {
        assertThat(manifest).contains("android:name=\".ChatUiApplication\"")
    }

    @Test
    fun `manifest enables predictive back`() {
        assertThat(manifest).contains("android:enableOnBackInvokedCallback=\"true\"")
    }

    @Test
    fun `manifest uses the splash screen theme`() {
        assertThat(manifest).contains("android:theme=\"@style/Theme.ChatUI.Starting\"")
    }
}
