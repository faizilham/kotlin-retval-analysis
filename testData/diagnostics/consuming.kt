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

fun stopWithParam(@Consume task: DummyDeferred<*>) {
    task.cancel()
}


fun simple() {
    val block = { 1 }
    val block2 = { 2 }
    val block3 = { 3 }

    val task = DummyDeferred(block)
    val task2 = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>
    val task3 = DummyDeferred(block)

    val task4 = if (1 == 2) <!UNCONSUMED_VALUE!>DummyDeferred(block2)<!> else task
    val task5 = if (3 == 4) DummyDeferred(block2) else DummyDeferred(block3)

    task.delay()
    task2.delay()
    task3.cancel()

    val x = DummyDeferred(block).stop()

    val aliasStop = ::stopWithParam

    val otherStop = aliasStop

    if (3 == 7) {
        val x1 = task4.await()
        aliasStop(task5)
    } else {
        stopWithParam(task5)
    }

    val result = task.await()
}
