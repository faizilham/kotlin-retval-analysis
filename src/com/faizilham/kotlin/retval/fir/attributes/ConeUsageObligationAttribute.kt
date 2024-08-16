package com.faizilham.kotlin.retval.fir.attributes

import org.jetbrains.kotlin.fir.types.ConeAttribute
import org.jetbrains.kotlin.fir.types.ConeAttributes
import kotlin.reflect.KClass

/*
 * ConeAttribute for Usage Obligation, based on:
 *      https://github.com/JetBrains/kotlin/blob/master/plugins/fir-plugin-prototype/src/org/jetbrains/kotlin/fir/plugin/types/ConeNumberSignAttribute.kt
 *
 */

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

    /*
     * Usage Obligations, from lowest to highest:
     *   AnyUse: "generic" / variable obligation,
     *   MayUse: discardable values,
     *   null  : implicitly MustUse.
     */
    enum class UsageObligation {
        AnyUse {
            override fun combine(other: UsageObligation?): UsageObligation? = when(other) {
                MayUse -> MayUse
                AnyUse -> AnyUse
                null   -> null
            }
        },

        MayUse {
            override fun combine(other: UsageObligation?): UsageObligation? = when(other) {
                MayUse -> MayUse
                AnyUse -> MayUse
                null   -> null
            }
        };

        abstract fun combine(other: UsageObligation?): UsageObligation?
    }

    fun isMayUse() : Boolean {
        return usage == UsageObligation.MayUse
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