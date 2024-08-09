package foo.baz

import com.faizilham.kotlin.retval.Discardable

fun normal() : Int {
    return 1
}

@Discardable
fun ignored() = 1

fun test() {
    <!UNUSED_RETURN_VALUE!>run { normal() }<!>
    <!UNUSED_RETURN_VALUE!>run { 1 + 2 }<!>
    run { print(normal()) }
    run {
        <!UNUSED_RETURN_VALUE!>normal()<!>
        ignored()
        print(normal())
    }

//    run { ignored() }
//    run { run { ignored() } }
}
