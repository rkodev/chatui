package dev.chatui.core.database.attachment

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidImageProcessorTest {
    private val processor = AndroidImageProcessor()

    @Test
    fun `downscales the longest edge and re-encodes the image`() = runTest {
        val source = Bitmap.createBitmap(3000, 1000, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, output)
        source.recycle()

        val processed = processor.process(output.toByteArray(), "image/png")

        assertThat(processed.width).isEqualTo(2048)
        assertThat(processed.height).isEqualTo(683)
        assertThat(processed.mimeType).isEqualTo("image/webp")
        assertThat(processed.bytes).isNotEmpty()
    }
}
