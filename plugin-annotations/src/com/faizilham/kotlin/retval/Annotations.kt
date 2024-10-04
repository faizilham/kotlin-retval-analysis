package com.faizilham.kotlin.retval

annotation class Discardable

@Target(AnnotationTarget.TYPE)
annotation class MayUse

@Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
annotation class AnyUse

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class SameUse