package foo.bar

import com.faizilham.kotlin.retval.annotations.*

@MustConsume
class DummyDeferred<T>(val block : () -> T) {
    private var result : T? = null
    private var finished = false

    @Consume
    fun await() : T? {
        if (!finished) {
            result = block()
            finished = true
        }

        return result
    }

    @Consume
    fun cancel() {
        finished = true
    }

    fun delay() {
        // do nothing
    }
}
