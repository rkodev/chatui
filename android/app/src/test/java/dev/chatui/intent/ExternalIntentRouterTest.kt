package dev.chatui.intent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalIntentRouterTest {
    @Test
    fun `submit publishes a pending action`() {
        val router = ExternalIntentRouter()
        val action = ExternalIntentAction.ShareText("hello")

        router.submit(action)

        assertThat(router.pending.value).isEqualTo(action)
    }

    @Test
    fun `repeated submit of the same action keeps one pending action`() {
        val router = ExternalIntentRouter()
        val action = ExternalIntentAction.ShareText("hello")

        router.submit(action)
        router.submit(action)

        assertThat(router.pending.value).isEqualTo(action)
    }

    @Test
    fun `consume clears the pending action`() {
        val router = ExternalIntentRouter()
        val action = ExternalIntentAction.ShareText("hello")

        router.submit(action)
        router.consume(action)

        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `a consumed action is not delivered again`() {
        val router = ExternalIntentRouter()
        val action = ExternalIntentAction.ShareText("hello")

        router.submit(action)
        router.consume(action)
        router.submit(action)

        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `a different action is delivered after consumption`() {
        val router = ExternalIntentRouter()
        val first = ExternalIntentAction.ShareText("first")
        val second = ExternalIntentAction.ShareText("second")

        router.submit(first)
        router.consume(first)
        router.submit(second)

        assertThat(router.pending.value).isEqualTo(second)
    }

    @Test
    fun `consume ignores an action that is not pending`() {
        val router = ExternalIntentRouter()
        val pending = ExternalIntentAction.ShareText("pending")
        val other = ExternalIntentAction.ShareText("other")

        router.submit(pending)
        router.consume(other)

        assertThat(router.pending.value).isEqualTo(pending)
    }
}
