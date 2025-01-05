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
fun<A, B> (@Util("u") A).let1(
    @UEffect([UE(0, "a"), UE(FV, "f")]) f: (@Util("u") A) -> @Util("b") B
): @Util("b") B {
    return f(this)
}

@UEffect([UE(THIS, "a"), UE(FV, "f")])
fun<A, B> (@Util("u") A).let2(
    @UEffect([UE(THIS, "a"), UE(FV, "f")]) f: (@Util("u") A).() -> B
): B {
    return f(this)
}

@UEffect([UE(THIS, "a"), UE(FV, "f")])
fun<A, B> (@Util("u") A).let3(
    @UEffect([UE(THIS, "a"), UE(FV, "f")]) f: (@Util("u") A).(A) -> B
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

    1.let2 { }
    1.let3 { }
}


// letUtilize :: (D[t], (D[t]) -> t & <U> + f) -> t & <U, N> + f

@UEffect([UE(THIS, "U"), UE(FV, "f")])
fun <T, R> (@Util("u") DummyDeferred<T>).letUtilize(
    @UEffect([UE(0, "U"), UE(FV, "f")]) block: (@Util("u") DummyDeferred<T>) -> R
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

@UEffect([UE(0, "U")])
fun <T> identity(x: @Util("u") T) : @Util("u") T = x

fun <T> requireUtilized(d : @Util("1") DummyDeferred<T>) {
    // do nothing
}

fun testUtilPrereq() {
    val task1 = DummyDeferred { 1 }
    val task2 = DummyDeferred { 1 }

    var res = task1.await()

    requireUtilized(task1)

    val task2a = identity(task2)

    <!MISMATCH_UTIL_EFFECT!>requireUtilized(task2a)<!>

    res = task2a.await()
}

@MustConsume
class DummyFile private constructor () {
    companion object {
        fun open(path: String) : @Util("0") DummyFile = DummyFile()
    }

    @Util("0")
    fun write(text: String) {}

    @Consume
    fun close() {}
}

fun writeEmpty(file: @Util("0") DummyFile) {
    file.write("")
}

fun testDummyFile() {
    val file1 = DummyFile.open("test")
    val file2 = <!UNCONSUMED_VALUE!>DummyFile.open("test2")<!>
    val writer = { it : DummyFile -> writeEmpty(it) }

    file1.write("1")
    file2.write("2")

    file1.let1(::writeEmpty)
    file1.let1({ if (1 == 2) it.write("test") else writer(it) })
    file1.let1 { writeEmpty(it) }
    file1.let1(writer)

    file1.close()

    <!MISMATCH_UTIL_EFFECT!>file1.write("3")<!>
    <!MISMATCH_UTIL_EFFECT!>file1.let1(::writeEmpty)<!>
    <!MISMATCH_UTIL_EFFECT!>file1.let1 { writeEmpty(it) }<!>
    <!MISMATCH_UTIL_EFFECT!>file1.let1(writer)<!>
    <!MISMATCH_UTIL_EFFECT!>file1.let1({ if (1 == 2) it.write("test") else writer(it) })<!>

    file2.let1(::writeEmpty)
    file2.let1(writer)
    file2.let1 { writeEmpty(it) }
    file2.let1({ if (1 == 2) it.write("test") else writer(it) })
}