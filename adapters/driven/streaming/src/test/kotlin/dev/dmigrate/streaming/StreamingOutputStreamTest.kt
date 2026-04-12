package dev.dmigrate.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream

class StreamingOutputStreamTest : FunSpec({

    test("CountingOutputStream counts single-byte writes and flushes delegate") {
        val delegate = TrackingOutputStream()
        val counting = CountingOutputStream(delegate)

        counting.write('A'.code)
        counting.flush()
        counting.close()

        delegate.toByteArray().decodeToString() shouldBe "A"
        counting.count shouldBe 1L
        delegate.flushCalls shouldBe 1
        delegate.closeCalls shouldBe 1
    }

    test("NonClosingOutputStream forwards single-byte writes and flush but ignores close") {
        val delegate = TrackingOutputStream()
        val nonClosing = NonClosingOutputStream(delegate)

        nonClosing.write('B'.code)
        nonClosing.flush()
        nonClosing.close()

        delegate.toByteArray().decodeToString() shouldBe "B"
        delegate.flushCalls shouldBe 1
        delegate.closeCalls shouldBe 0
    }
})

private class TrackingOutputStream : ByteArrayOutputStream() {
    var flushCalls: Int = 0
    var closeCalls: Int = 0

    override fun flush() {
        flushCalls += 1
        super.flush()
    }

    override fun close() {
        closeCalls += 1
        super.close()
    }
}
