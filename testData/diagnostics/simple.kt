package foo.bar

import com.faizilham.kotlin.retval.Discardable

data class Pair(val x:Int, val y: Int)

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

    println(if (normal() == 1) 1 else 2)

    val y = { x + 1 }()

    (<!UNUSED_RETURN_VALUE!>{ x + 1 }()<!>)

    <!UNUSED_RETURN_VALUE!>normal()<!>
}

fun normalOrNull(x: Int) = if (x > 0) 1 else null

@Discardable
fun ignoredOrNull(x: Int) = normalOrNull(x)

fun normalBool(x: Int) = x == 1

@Discardable
fun ignoredBool(x: Int) = x == 2

fun binaries() {
    val l = listOf(1, 2, 3)
    val c = normalOrNull(4) ?: ignoredOrNull(1) ?: 1

    <!UNUSED_RETURN_VALUE!>normalOrNull(4)<!> ?: ignoredOrNull(1) ?: 1
    ignoredOrNull(4) ?: ignoredOrNull(1) ?: 1

    var b = (1 == normal()) == (ignored() == 1)
    <!UNUSED_RETURN_VALUE!>3 == 2<!>
    <!UNUSED_RETURN_VALUE!>(1 == normal()) == (ignored() == 1)<!>

    b = normalBool(2) && ignoredBool(2) || true
    <!UNUSED_RETURN_VALUE!>normalBool(2)<!> && ignoredBool(2) || true
    <!UNUSED_RETURN_VALUE!>normalBool(2)<!> && <!UNUSED_RETURN_VALUE!>normalBool(2)<!> || true
    ignoredBool(2) && <!UNUSED_RETURN_VALUE!>normalBool(2)<!> || true
    ignoredBool(2) && ignoredBool(2) || true

}

fun indirectRefs() {
    // only the simplest cases (direct assignment), var is not yet supported
    val indirect = ::ignored
    val localNormal = ::normal

    indirect()
    <!UNUSED_RETURN_VALUE!>localNormal()<!>

    val indirect2 = indirect
    val indirect3 = localNormal

    indirect2()
    <!UNUSED_RETURN_VALUE!>indirect3()<!>

    val dlambda = { ignored() }
    val nlambda = { normal() }

    dlambda()
    <!UNUSED_RETURN_VALUE!>nlambda()<!>

    val doubleLamb = { x: Int ->
        val indirect = { x: Int ->
            if (x == 2) dlambda() else indirect2()
        }

        indirect(x)
    }

    doubleLamb(1)

    val litLambda = { 1 }
    litLambda()             // TODO: should warn, literals not yet handled
}

fun looping() : Int {
    var i = 0
    while (i < 5) {
        if (i == 3) {
            return normal()
        }

        <!UNUSED_RETURN_VALUE!>normal()<!>
        i = i + 1
    }

    return normal() + 1
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
    println(x)

    if (true) {
        val x = normal()
        println(x)

        <!UNUSED_RETURN_VALUE!>normal()<!>
        <!UNUSED_RETURN_VALUE!>1 + 2<!>
        println(normal())

        other()
    }
}
