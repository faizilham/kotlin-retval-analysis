package com.faizilham.kotlin.retval.fir.checkers.analysis

import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.utils.SmartSet

interface Lattice<T: Lattice<T>> {
    fun join(other: T): T
    fun meet(other: T): T
}

class DefaultMapLat<K, V: Lattice<V>> private constructor (val defaultVal: V, private val _map: MutableMap<K, V>)
    : MutableMap<K, V> by _map, PathInfo<DefaultMapLat<K, V>>
{
    constructor(defaultVal: V) : this(defaultVal, mutableMapOf())

    fun joinVal(key: K, withVal: V) {
        this[key] = getWithDefault(key).join(withVal)
    }

    fun meetVal(key: K, withVal: V) {
        this[key] = getWithDefault(key).meet(withVal)
    }

    override fun merge(other: DefaultMapLat<K, V>): DefaultMapLat<K, V> {
        val combinedKeys = keys + other.keys

        val combined = DefaultMapLat<K, V>(defaultVal)

        for (key in combinedKeys) {
            val left = this[key] ?: defaultVal
            val right = other[key] ?: defaultVal

            combined[key] = left.join(right)
        }

        return combined
    }

    override fun copy(): DefaultMapLat<K, V> {
        return DefaultMapLat(defaultVal, _map.toMutableMap())
    }

    fun getWithDefault(key: K?) = this[key] ?: defaultVal
}

class SetLat<V> private constructor(private val _set: Set<V>)
    : Set<V> by _set, Lattice<SetLat<V>>
{
    constructor() : this(SmartSet.create())

    constructor(value: V) : this() {
        (_set as SmartSet<V>).add(value)
    }

    companion object {
        fun<V> from(values: SmartSet<V>): SetLat<V> {
            return SetLat(values)
        }

        fun<V> from(values: Collection<V>): SetLat<V> {
            return SetLat(SmartSet.create(values))
        }
    }

    fun joinWith(value : V): SetLat<V> {
        val newSet = SmartSet.create(_set)
        newSet.add(value)

        return SetLat(newSet)
    }

    override fun join(other: SetLat<V>): SetLat<V> {
        return SetLat(_set.union(other._set))
    }

    override fun meet(other: SetLat<V>): SetLat<V> {
        return SetLat(_set.intersect(other._set).toMutableSet())
    }
}

/* Path Info */
interface PathInfo<T: PathInfo<T>> {
    fun copy(): T
    fun merge(other: T): T
}

fun<T: PathInfo<T>> List<T>.mergeAll(mustCopy : Boolean = false) : T? {
    val info = when(size) {
        0 -> null
        1 -> if (mustCopy) get(0).copy() else get(0)
        2 -> get(0).merge(get(1))
        else -> {
            var result = first()

            asSequence().drop(1).forEach {
                result = result.merge(it)
            }

            return result
        }
    }

    return info
}

/* Function Info */

data class FunctionInfo(
    val isLambda: Boolean,
    val isClassMemberOrExtension: Boolean,
    val returningConsumable : Boolean,
    val returnIsConsumed : Boolean = false,
    val consumingThis : Boolean = false,
    val consumedParameters : Set<Int> = setOf(),
    val consumedFreeVariables : Set<FirBasedSymbol<*>> = setOf()
) {
    fun convertThisToFirstParameter() : FunctionInfo {
        val mappedParameters = consumedParameters.map { it + 1 }.toMutableSet()
        if (consumingThis) mappedParameters.add(0)

        return FunctionInfo(
            isLambda,
            isClassMemberOrExtension = false,
            returningConsumable,
            returnIsConsumed,
            consumingThis,
            consumedParameters = mappedParameters,
            consumedFreeVariables
        )
    }

    fun hasNoEffect() : Boolean {
        return  (!returningConsumable || returnIsConsumed) &&
                !consumingThis &&
                consumedParameters.isEmpty() &&
                consumedFreeVariables.isEmpty()
    }
}
