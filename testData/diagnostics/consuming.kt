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

    if (3 == 7) {
        val x1 = task4.await()
        val x2 = task5.await()
    } else {
        task5.cancel()
    }

    val result = task.await()
}

fun retvalue() : DummyDeferred<Int> {
    val newdef = { x: Int -> DummyDeferred { x } }

    val task = <!UNCONSUMED_VALUE!>newdef(1)<!> // task can be unused in else part

    if (3 == 7) {
        return task
    } else {
        val task2 = newdef(2)
        return task2
    }
}

fun insideNoCrossover() {
    val block = { 1 }
    val block2 = {
        val task = DummyDeferred(block)
        val task2 = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>

        task.await()
    }

    val task3 = <!UNCONSUMED_VALUE!>DummyDeferred(block2)<!>

    val notCalled = {
        task3.await()   // TODO: this is correct, but study what actually happened here
    }
}