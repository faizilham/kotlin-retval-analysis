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
fun mycancel(x: DummyDeferred<*>) {
    x.cancel()
}

@UEffect([UE(THIS, "a"), UE(FV, "f")])
fun<A, B> A.let1(
    @UEffect([UE(0, "a"), UE(FV, "f")]) f: (A) -> B
): B {
    return f(this)
}

@UEffect([UE(0, "a"), UE(FV, "f")])
fun <A, B> with1(
    receiver: A,
    @UEffect([UE(THIS, "a"), UE(FV, "f")]) block: A.() -> B
): B {
    return receiver.block()
}

fun Int.abc() { }

fun withAbc(x: Int, f: Int.() -> Unit) {}

fun simple() {
    val task1 = DummyDeferred { 1 }

    var result = task1.let1(::myawait)

    result = with1(task1, DummyDeferred<Int>::await)

    withAbc(1, Int::abc)

//    val awaitAlias : (DummyDeferred<Int>) -> Int? = ::myawait
//
//    result = awaitAlias(task1)
//
//    mycancel(task1)

    task1.cancel() // TODO: remove for true test
}