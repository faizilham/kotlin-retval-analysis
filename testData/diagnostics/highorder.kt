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

fun <T> doNothing(x: T) { }

fun simple() {
    var result : Int? = null
    val task1 = DummyDeferred { 1 }
    result = task1.let1(::myawait)

    val task2 = DummyDeferred { 1 }
    result = with1(task2, DummyDeferred<Int>::await)

    val awaitAlias : (DummyDeferred<Int>) -> Int? = ::myawait

    val awaiter = { it: DummyDeferred<Int> -> it.let1(awaitAlias) }

    val task3 = DummyDeferred { 1 }
    result = task3.let1(awaiter)
}

fun withFV() {
    val task1 = DummyDeferred { 1 }
    val task2 = DummyDeferred { 1 }
    val task3 = <!UNCONSUMED_VALUE!>DummyDeferred { 1 }<!>

    val canceler = { it: DummyDeferred<Int> -> it.cancel(); task2.cancel() }
    task1.let1(canceler)

    task3.let1 { doNothing(it) }

    val task4 = DummyDeferred { 1 }
    val task5 = DummyDeferred { 1 }
    task4.let1 { it.cancel(); task5.cancel() }
}


// letUtilize :: (D[t], (D[t]) -> t & <U> + f) -> t & <U, N> + f
@UEffect([UE(THIS, "U"), UE(FV, "f")])
fun <T, R> DummyDeferred<T>.letUtilize(
    @UEffect([UE(0, "U"), UE(FV, "f")]) block: (DummyDeferred<T>) -> R
): R {
    return block(this)
}

fun effectMismatch() {
    val task1 = DummyDeferred { 1 }
    val task2 = DummyDeferred { 1 }
    task1.letUtilize { it.cancel(); task2.cancel() }

    val task3 = <!UNCONSUMED_VALUE!>DummyDeferred { 1 }<!>

    <!MISMATCH_UTIL_EFFECT!>task3.letUtilize(::doNothing)<!>
}