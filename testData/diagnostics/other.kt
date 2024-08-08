package foo.bar

import org.faizilham.kotlin.retval.Discardable

fun normal() = 1

@Discardable
fun ignored() = 1

fun test() {
    <!UNUSED_RETURN_VALUE!>normal()<!>
    ignored()
    1 + 2
    println("test")
    println(normal())
    println(1 + 2)

    if (true) {
        <!UNUSED_RETURN_VALUE!>normal()<!>
        println(normal())
    }
}
