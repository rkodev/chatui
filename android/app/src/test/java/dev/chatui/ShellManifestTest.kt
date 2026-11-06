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

    @Test
    fun `manifest accepts plain text shares on a single top activity`() {
        assertThat(manifest).contains("android:launchMode=\"singleTop\"")
        assertThat(manifest).contains("android.intent.action.SEND")
        assertThat(manifest).contains("android.intent.category.DEFAULT")
        assertThat(manifest).contains("android:mimeType=\"text/plain\"")
    }

    @Test
    fun `manifest registers the chatui deep link filter`() {
        assertThat(manifest).contains("android.intent.action.VIEW")
        assertThat(manifest).contains("android.intent.category.BROWSABLE")
        assertThat(manifest).contains("android:scheme=\"chatui\"")
        assertThat(manifest).contains("android:host=\"chat\"")
        assertThat(manifest).contains("android:pathPrefix=\"/\"")
    }

    @Test
    fun `manifest references the network security config`() {
        assertThat(manifest)
            .contains("android:networkSecurityConfig=\"@xml/network_security_config\"")
    }

    @Test
    fun `network security config enables base cleartext as the platform enabler`() {
        val config = File("src/main/res/xml/network_security_config.xml").readText()

        assertThat(config).contains("<base-config cleartextTrafficPermitted=\"true\" />")
        assertThat(config).contains("cannot express")
    }
}
