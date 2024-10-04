package foo.baz

import com.faizilham.kotlin.retval.Discardable
import com.faizilham.kotlin.retval.MayUse
import com.faizilham.kotlin.retval.AnyUse
import com.faizilham.kotlin.retval.SameUse

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract


fun normal() = 1

@Discardable
fun ignored() = 1

fun <R> noContractRun(block: () -> R): R {
    return block()
}

@OptIn(ExperimentalContracts::class)
inline fun <R> myRun(@SameUse block: () -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return block()
}

@OptIn(ExperimentalContracts::class)
inline fun adder(block: () -> Int): Int {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return block() + 1
}

@OptIn(ExperimentalContracts::class)
inline fun <R> myRun2(block: () -> R): R {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return block()
}

@OptIn(ExperimentalContracts::class)
inline fun applyIf(cond: Boolean, @SameUse left: () -> Int, @SameUse right: () -> Int): Int {
    contract {
        callsInPlace(left, InvocationKind.AT_MOST_ONCE)
        callsInPlace(right, InvocationKind.AT_MOST_ONCE)
    }
    return if (cond) left() else right()
}

fun simpleProp() {
    val indirectIgnored = { ignored() }

    myRun { ignored() }
    myRun(::ignored)
    myRun(indirectIgnored)

    myRun { myRun { myRun { ignored() } } }

    myRun {
        if (1 == 2) {
            ignored()
        } else {
            indirectIgnored()
        }
    }

    <!UNUSED_RETURN_VALUE!>myRun { normal() }<!>
    <!UNUSED_RETURN_VALUE!>myRun(::normal)<!>
    <!UNUSED_RETURN_VALUE!>myRun { myRun { normal() } }<!>

    applyIf(1 == 2, indirectIgnored, { ignored() })
    <!UNUSED_RETURN_VALUE!>applyIf(1 == 2, { normal() }, { ignored() })<!>
}

fun forFir() {
    val ignored2 = { ignored() }

    val z = myRun(ignored2)
    val z0 = myRun { normal() }
    val z1 = myRun2 { 1 + 2 }
    val z2 = run { 4 + 5 }
    val z3 = noContractRun { 1 + 3 }
}

fun forFir2() {
    val ignored2 = { ignored() }

    val y = applyIf(3 == 1, ignored2, { ignored() })
    val y2 = applyIf(1 == 2, ::normal, { ignored() })

    val x = adder { normal() }
}