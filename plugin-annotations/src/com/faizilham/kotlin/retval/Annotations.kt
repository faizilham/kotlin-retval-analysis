package com.faizilham.kotlin.retval

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Discardable

@Target(AnnotationTarget.TYPE)
annotation class MayUse

@Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
annotation class AnyUse

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class SameUse