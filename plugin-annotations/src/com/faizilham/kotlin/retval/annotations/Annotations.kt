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

//

@Target(AnnotationTarget.TYPE)
annotation class MayUse

@Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
annotation class AnyUse
