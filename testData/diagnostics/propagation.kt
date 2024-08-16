package foo.baz

import com.faizilham.kotlin.retval.*

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

fun normal() : Int {
    return 1
}

@Discardable
fun ignored() = 1

fun ignored2() : @MayUse Int = 1

fun fromIgnored1() : Int = ignored2()

fun fromIgnored2() : @AnyUse Int = ignored2()

//@OptIn(ExperimentalContracts::class)
//inline fun <@AnyUse R> myRun(block: () -> R): R {
//    contract {
//        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
//        // problem : < ! UNUSED_RETURN_VALUE ! >callsInPlace(block, InvocationKind.EXACTLY_ONCE)
//    }
//    return block()
//}

inline fun <@AnyUse R> myRun(block: () -> R): R = block()
inline fun <R> myRun1(block: () -> @AnyUse R): @AnyUse R = block()
//fun <@AnyUse R> myRun2(block: () -> R): R = block()


fun test() {
    <!UNUSED_RETURN_VALUE!>run { normal() }<!>
    <!UNUSED_RETURN_VALUE!>run { 1 + 2 }<!>
    run { print(normal()) }
    run {
        <!UNUSED_RETURN_VALUE!>normal()<!>
        ignored()
        print(normal())
    }

    ignored2()

    <!UNUSED_RETURN_VALUE!>myRun { normal() }<!>
    myRun { ignored2() }

    <!UNUSED_RETURN_VALUE!>myRun { myRun { normal() } }<!>
//    myRun { myRun { ignored2() } } // FIX

    <!UNUSED_RETURN_VALUE!>myRun1 { normal() }<!>
    myRun1 { ignored2() }

//    myRun1 { myRun1 { ignored2() } } // FIX

    <!UNUSED_RETURN_VALUE!>fromIgnored1()<!>

//    run { ignored() }                // FIX
//    run { run { ignored() } }        // FIX
}
