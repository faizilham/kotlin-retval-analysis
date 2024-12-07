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

// effect: U | N | I, empty -> N, other strings will be used as variable name
@Target()
annotation class UE(val target: Int, val effect: String)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class UEffect(val effects: Array<UE>)

// value: "0" -> NU, "1" -> UT, "" -> Top, "0|1" -> Top, otherwise variable name
@Target(AnnotationTarget.TYPE)
annotation class Util(val value: String)

@Target(AnnotationTarget.TYPE)
annotation class MayUse

@Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
annotation class AnyUse
