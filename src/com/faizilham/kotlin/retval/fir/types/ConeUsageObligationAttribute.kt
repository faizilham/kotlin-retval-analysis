package com.faizilham.kotlin.retval.fir.types

import org.jetbrains.kotlin.fir.types.ConeAttribute
import org.jetbrains.kotlin.fir.types.ConeAttributes
import kotlin.reflect.KClass

class ConeUsageObligationAttribute private constructor(val usage: UsageObligation)
    : ConeAttribute<ConeUsageObligationAttribute>() {

    companion object {
        private val MayUse = ConeUsageObligationAttribute(UsageObligation.MayUse)
        private val AnyUse = ConeUsageObligationAttribute(UsageObligation.AnyUse)

        fun fromUsageObligation(usage: UsageObligation?): ConeUsageObligationAttribute? {
            return when (usage) {
                UsageObligation.MayUse -> MayUse
                UsageObligation.AnyUse -> AnyUse
                null -> null
            }
        }
    }

    fun isMayUse() : Boolean {
        return (usage == UsageObligation.MayUse) ?: false
    }

    enum class UsageObligation {

        MayUse {
            override fun combine(other: UsageObligation?): UsageObligation? = when(other) {
                MayUse -> MayUse
                AnyUse -> MayUse
                null   -> null
            }
        },
        AnyUse {
            override fun combine(other: UsageObligation?): UsageObligation? = when(other) {
                MayUse -> MayUse
                AnyUse -> AnyUse
                null   -> null
            }
        };

        abstract fun combine(other: UsageObligation?): UsageObligation?
    }

    private fun combine(other: ConeUsageObligationAttribute?): ConeUsageObligationAttribute? {
        return fromUsageObligation(usage.combine(other?.usage))
    }

    override fun union(other: ConeUsageObligationAttribute?): ConeUsageObligationAttribute? {
        return combine(other)
    }

    override fun intersect(other: ConeUsageObligationAttribute?): ConeUsageObligationAttribute? {
        return combine(other)
    }

    override fun add(other: ConeUsageObligationAttribute?): ConeUsageObligationAttribute? {
        return combine(other)
    }

    override fun isSubtypeOf(other: ConeUsageObligationAttribute?): Boolean {
        return true
    }

    override fun toString(): String {
        return "@${usage.name}"
    }

    override val keepInInferredDeclarationType: Boolean
        get() = true
    override val key: KClass<out ConeUsageObligationAttribute>
        get() = ConeUsageObligationAttribute::class
}

val ConeAttributes.usageObligation: ConeUsageObligationAttribute?
    by ConeAttributes.attributeAccessor<ConeUsageObligationAttribute>()