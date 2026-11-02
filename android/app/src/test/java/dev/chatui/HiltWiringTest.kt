package dev.chatui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HiltWiringTest {
    @Test
    fun `hilt application component is generated`() {
        val component = Class.forName(
            "dev.chatui.DaggerChatUiApplication_HiltComponents_SingletonC",
        )

        assertThat(component).isNotNull()
    }

    @Test
    fun `hilt application base class is generated`() {
        val base = Class.forName("dev.chatui.Hilt_ChatUiApplication")

        assertThat(base).isNotNull()
    }
}
