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

<!CONSUME_NOT_MEMBER_OR_EXT!>@Consume fun invalidConsume(x: Int) {}<!>

@Consume fun DummyDeferred<*>.stop() { cancel() }

fun stopWithParam(@Consume task: DummyDeferred<*>) {
    task.cancel()
}

// TODO: allow this? maybe weird because no "return is consumed" tracking?
fun identityConsume(@Consume task: DummyDeferred<*>) = task

fun simple() {
    val block = { 1 }
    val block2 = { 2 }
    val block3 = { 3 }

    val task = DummyDeferred(block)
    val task2 = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>
    val task3 = DummyDeferred(block)

    val task4 = if (1 == 2) <!UNCONSUMED_VALUE!>DummyDeferred(block2)<!> else task
    val task5 = if (3 == 4) DummyDeferred(block2) else DummyDeferred(block3)

    task.delay()
    task2.delay()
    task3.cancel()

    val x = DummyDeferred(block).stop()

    val aliasStop = ::stopWithParam

    val otherStop = aliasStop

    if (3 == 7) {
        val x1 = task4.await()
        aliasStop(task5)
    } else {
        stopWithParam(task5)
    }

    val result = task.await()
}

fun retvalue() : DummyDeferred<Int> {
    val newdef = { x: Int -> DummyDeferred { x } }

    val task = <!UNCONSUMED_VALUE!>newdef(1)<!> // task can be unused in else part

    if (3 == 7) {
        return task
    } else {
        val task2 = newdef(2)
        return task2
    }
}

fun insideLambda() {
    val block = { 1 }
    val block1 = { 2 }
    val block2 = {
        val task = DummyDeferred(block)
        val task2 = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>

        task.await()
    }

    val task3 = <!UNCONSUMED_VALUE!>DummyDeferred(block2)<!>

    val notCalled = {
        task3.await()
    }
}

fun lateInitialization() {
    val block = { 1 }

    val task : DummyDeferred<Int>;

    if (1 == 1) {
        task = DummyDeferred(block)
    } else {
        task = DummyDeferred(block)
    }

    val result = task.await()

    // TODO: add var test
}

fun paramConsumingLambda() {
    // case: parameter-consuming lambda, with explicit param and implicit it

    val block = { 1 }

    val task1 = DummyDeferred(block)
    val task1a = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>
    val task2 = DummyDeferred(block)

    val runner1: (DummyDeferred<*>) -> Unit = {
        val y = it;
        val res = y.await()
    }

    val runner2 = { x1: DummyDeferred<*> ->
        val y = x1;
        val res = y.await()
    }

    val doNothing = { x: DummyDeferred<*> -> x.delay() }

    runner1(task1)
    doNothing(task1a)
    runner2(task2)
}

fun contextConsumingLambda() {
    // case: context object (this) consuming lambda and extension functions
    val block = { 1 }

    val task2a = DummyDeferred(block)
    val task2b = DummyDeferred(block)
    val task2c = DummyDeferred(block)
    val task2d = DummyDeferred(block)
    val task2e = DummyDeferred(block)
    val task2f = DummyDeferred(block)
    val task2g = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>

    val runThis : DummyDeferred<*>.() -> Unit = { val res = this.await() }
    val runThis2 : DummyDeferred<Int>.(Int) -> Unit = { val res = await() ?: it }
    val stopper = DummyDeferred<*>::stop
    val stopper2 = DummyDeferred<*>::cancel
    val stopper3 : DummyDeferred<*>.() -> Unit = ::stopWithParam
    val asDefaultRun : Int.(DummyDeferred<Int>) -> Unit = { val res = it.await() ?: this }

    val withDoNothing : DummyDeferred<*>.() -> Unit = { this.delay() }

    task2a.runThis()
    task2b.runThis2(100)
    task2c.stopper()
    stopper2(task2d)
    task2e.stopper3()
    (9876).asDefaultRun(task2f)

    task2g.withDoNothing()
}

fun freeVarConsumingLambda() {
    // case: free variable consuming lambda
    val block = { 1 }
    val runner1: (DummyDeferred<*>) -> Unit = {
        val y = it;
        val res = y.await()
    }

    val task3 = DummyDeferred(block)
    val task3a = DummyDeferred(block)
    val task4 = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>
    val task4a = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>
    val task4b = <!UNCONSUMED_VALUE!>DummyDeferred(block)<!>

    val run3 = { val res = task3.await(); task3a.cancel() }
    val run4 = { val res = task4.await() }
    val run4a = { val res = task4a.await() }  // never called
    val doNothing4b = { task4b.delay() }

    if (1 == 1) {
        run3()
    } else {
        runner1(task3)
        stopWithParam(task3a)
    }

    if (2 == 2) {
        run4()
    }

    doNothing4b()
}

fun indirectLambda1() {
    // case: multiple lambda indirection
    val block = { 1 }
    val runner1: (DummyDeferred<*>) -> Unit = {
        val y = it;
        val res = y.await()
    }

    val task5 = DummyDeferred(block)

    val run5 = { val res = runner1(task5) }
    val runInside5 = {
        val inside = { run5() }
        inside()
    }

    runInside5()
}

fun indirectLambda2() {
    // case: another lambda indirection
    val block = { 1 }

    val runner2 = { x1: DummyDeferred<*> ->
        val y = x1;
        val res = y.await()
    }

    val indirectRun = { it : DummyDeferred<*>, n: Int ->
        val inside = {
            if (n > 1) {
                val res = it.await()
            } else {
                val res = runner2(it)
            }
        }

        if (n > 0) {
            inside()
        } else {
            it.cancel()
        }
    }

    val task6 = DummyDeferred(block)
    indirectRun(task6, 1)
}

fun edgeCaseLimitations() {
    // case (limitation): escaping must-consume value is not tracked
    val createEscaping = { i: Int ->
        val escapingTask = <!UNCONSUMED_VALUE!>DummyDeferred({ i })<!>
        val runner = { val res = escapingTask.await() }
        runner
    }

    val escapingRunner = createEscaping(1234)
    escapingRunner()
}

// TODO: using .run and .let
// x.run { val y = this; val res = y.await() }
// x.let { val y = it; val res = y.await() }
// x.let { x1 -> val y = x1; val res = y.await() }