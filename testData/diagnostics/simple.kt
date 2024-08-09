package foo.bar

import com.faizilham.kotlin.retval.Discardable

fun normal() : Int {
    return 1
}

@Discardable
fun ignored() = 1

fun other() {
    <!UNUSED_RETURN_VALUE!>normal()<!>
    <!UNUSED_RETURN_VALUE!>1 + 2<!>
    ignored()
    println("other")
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
