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

fun<T> myawait(@Consume x: DummyDeferred<T>): T? {
    return x.await()
}

@UEffect([UE(1, "U")])
fun<T> mycancel(x: DummyDeferred<T>) {
    x.cancel()
}

@UEffect([UE(THIS, "a"), UE(-2, "f")])
fun<A, B> A.let1(
    @UEffect([UE(0, "a"), UE(FV, "f")]) f: (A) -> B
): B {
    return f(this)
}

fun simple() {
    val task1 = DummyDeferred { 1 }

    val result = task1.let1(::myawait)

    mycancel(task1)

    task1.cancel() // TODO: remove for true test
}