package dev.chatui.core.common

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Test

class OutcomeTest {
    @Test
    fun `outcomeOf captures success`() {
        val outcome = outcomeOf { 42 }

        assertThat(outcome).isEqualTo(Outcome.Success(42))
        assertThat(outcome.getOrNull()).isEqualTo(42)
        assertThat(outcome.errorOrNull()).isNull()
    }

    @Test
    fun `outcomeOf captures failure`() {
        val outcome = outcomeOf<Int> { error("boom") }

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(outcome.getOrNull()).isNull()
        assertThat(outcome.errorOrNull()?.message).isEqualTo("boom")
    }

    @Test
    fun `outcomeOf rethrows cancellation`() {
        val failure = runCatching {
            outcomeOf<Int> { throw CancellationException("cancelled") }
        }

        assertThat(failure.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `map transforms success and preserves failure`() {
        val success = Outcome.Success(2).map { it * 3 }
        val failure: Outcome<Int> = Outcome.Failure(AppError(AppErrorCode.IO, "disk"))
        val mappedFailure = failure.map { it * 3 }

        assertThat(success).isEqualTo(Outcome.Success(6))
        assertThat(mappedFailure).isEqualTo(Outcome.Failure(AppError(AppErrorCode.IO, "disk")))
    }
}
