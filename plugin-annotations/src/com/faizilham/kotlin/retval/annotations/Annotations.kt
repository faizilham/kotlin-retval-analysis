package com.faizilham.kotlin.retval.annotations

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Discardable

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class SameUse

// Consuming values

@Target(AnnotationTarget.CLASS)
annotation class MustConsume

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class Consume

const val THIS = -1

// Pre-req:
// - funcParam is valid parameter or receiver index, and is of a function type
// - insideParam is a valid parameter or receiver index in funcParam, and a must-consume type
annotation class ConsumeBy(val funcParam: Int, val insideParam: Int)

// Pre-req:
// - insideParam is a valid parameter or receiver index, and a must-consume type
// - all constraints are valid ConsumeBy
annotation class ConsumeRule(val target: Int, val constraints: Array<ConsumeBy>)

// Pre-req:
// - all rules are valid ConsumeRule
// - no duplicate ConsumeRule by target
@Target(AnnotationTarget.FUNCTION)
annotation class ConsumeRules(val rules: Array<ConsumeRule>)

//

@Target(AnnotationTarget.TYPE)
annotation class MayUse

@Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
annotation class AnyUse
