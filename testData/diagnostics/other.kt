package foo.bar

import org.faizilham.kotlin.retval.Discardable

fun normal() = 1

@Discardable
fun ignored() = 1

fun test() {
    // val s = MyClass().foo()
    // s.< ! UNRESOLVED_REFERENCE ! >incas< ! >() // should be an error, delete space surrounding !
    normal()
    ignored()
}
