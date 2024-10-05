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

<!CONSUME_NOT_MEMBER_OR_EXT!>@Consume fun invalidConsume(x: Int) {}<!>

@Consume fun DummyDeferred<*>.stop() { cancel() }

fun simple() {
    val block = { 1 }

    val task = DummyDeferred(block)
    val task2 = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>  // TODO: Warn
    val task3 = DummyDeferred(block)

    task.delay()
    task2.delay()
    task3.cancel()

    val x = DummyDeferred(block).stop()

    val result = task.await()
}