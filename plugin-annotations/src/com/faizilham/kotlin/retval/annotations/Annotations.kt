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

const val THIS = -1 // represents context object / this
const val FV = -2   // represents set of FV, only valid with variables

// utilValue: U | N | I, other strings will be used as variable name
@Target()
annotation class UE(val target: Int, val utilValue: String)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class UEffect(val effects: Array<UE>)

@Target(AnnotationTarget.TYPE)
annotation class MayUse

@Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
annotation class AnyUse
