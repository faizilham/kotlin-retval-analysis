package foo.bar

import com.faizilham.kotlin.retval.Discardable

fun normal() : Int {
    return 1
}

fun normal1(i : Int) = i + 1

fun normal2(i : Int) : Int {
    val inc = { i + 1 }

    if (i == 2) return inc()
    else return inc() + 1
}


@Discardable
fun ignored() = 1

@Discardable
fun ignored1(i : Int) = if (i == 2) i + 1 else i + 2

fun other() {
    <!UNUSED_RETURN_VALUE!>normal()<!>
    <!UNUSED_RETURN_VALUE!>1 + 2<!>
    ignored()

    val x = if (normal() == 1) 1 + 1 else 2 + 2

    println(x.inc().toString())

    <!UNUSED_RETURN_VALUE!>normal()<!>
}

fun testflow() {
    var x = normal()
    x = ignored()

    <!UNUSED_RETURN_VALUE!>normal()<!>
    <!UNUSED_RETURN_VALUE!>normal1(ignored1(1))<!>
    ignored()
    ignored1(normal1(1))

    <!UNUSED_RETURN_VALUE!>1 + 2<!>
    print(1 + 2)
    2

    if (1 + 1 == 2) {
        <!UNUSED_RETURN_VALUE!>normal()<!>
    } else {
        ignored()
    }
}

fun test() {
    <!UNUSED_RETURN_VALUE!>normal()<!>
    ignored()
    <!UNUSED_RETURN_VALUE!>1 + 2<!>
    println("test")
    println(normal())
    println(1 + 2)

    other()

    <!UNUSED_RETURN_VALUE!>arrayOf(1, 2, 3)<!>
    <!UNUSED_RETURN_VALUE!>listOf(1, 2, 3)<!>

    val x = normal()

    if (true) {
        val x = normal()

        <!UNUSED_RETURN_VALUE!>normal()<!>
        <!UNUSED_RETURN_VALUE!>1 + 2<!>
        println(normal())

        other()
    }
}
