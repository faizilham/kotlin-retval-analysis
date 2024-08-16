package foo.baz

import com.faizilham.kotlin.retval.Discardable
import com.faizilham.kotlin.retval.MayUse
import com.faizilham.kotlin.retval.AnyUse

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

@OptIn(ExperimentalContracts::class)
inline fun <@AnyUse R> myRun(block: () -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return block()
}

@OptIn(ExperimentalContracts::class)
inline fun <R> myRun1(block: () -> @AnyUse R): @AnyUse R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return block()
}

//inline fun <@AnyUse R> myRun(block: () -> R): R = block()
//inline fun <R> myRun1(block: () -> @AnyUse R): @AnyUse R = block()
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


    // with other control flows

    val c = true;
    myRun {
        if (c) {
            ignored2()
        } else {
            ignored2()
        }
    }


    <!UNUSED_RETURN_VALUE!>myRun {
        if (c) {
            <!UNUSED_RETURN_VALUE!>normal()<!> // FIX?, since it's a returned expression
        } else {
            ignored2()
        }
    }<!>

    val x = myRun { normal() }

    // semantically unclear cases
    // not sure if the followings are desirable

    // 1. operations make ignored arguments into MustUse
    <!UNUSED_RETURN_VALUE!>myRun { ignored2() + ignored2() }<!>

    // 2. MayUse variable
    val y : @MayUse Int = 3
    myRun { y }
}
