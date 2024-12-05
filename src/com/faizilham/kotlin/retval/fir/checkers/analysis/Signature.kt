package com.faizilham.kotlin.retval.fir.checkers.analysis

import com.faizilham.kotlin.retval.fir.checkers.analysis.FVEffectSign.FVEMap
import com.faizilham.kotlin.retval.fir.checkers.analysis.FVEffectSign.FVEVar
import com.faizilham.kotlin.retval.fir.checkers.analysis.UtilEffect.Var
import com.faizilham.kotlin.retval.fir.checkers.commons.Commons
import com.faizilham.kotlin.retval.fir.checkers.commons.containsAnnotation
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol

class Signature(
    val isClassMemberOrExtension: Boolean,
    val paramSignature: Map<Int, Signature>,

    val paramEffect : Map<Int, UtilEffect>,
    val receiverEffect: UtilEffect,
    val fvEffect: FVEffectSign,
    val convertedReceiver: Boolean = false
) {
    val effectVars : Set<Var>

    init {
        val vars = mutableSetOf<Var>()

        if (receiverEffect is Var) vars.add(receiverEffect)

        for ((_, effect) in paramEffect) {
            if (effect is Var) vars.add(effect)
        }

        if (fvEffect is FVEMap) {
            for ((_, effect) in fvEffect.map) {
                if (effect is Var) vars.add(effect)
            }
        }

        effectVars = vars
    }

    fun convertReceiverToParameter() : Signature {
        val newParamEffect = mutableMapOf<Int, UtilEffect>()
        if (receiverEffect != UtilEffect.N) newParamEffect[0] = receiverEffect

        for ((i, effect) in paramEffect) {
            newParamEffect[i + 1] = effect
        }

        val newParamSign = mutableMapOf<Int, Signature>()
        for ((i, sign) in paramSignature) {
            newParamSign[i + 1] = sign
        }

        return Signature(
            isClassMemberOrExtension = false,
            paramSignature = newParamSign,

            paramEffect = newParamEffect,
            receiverEffect = UtilEffect.N,
            fvEffect,
            convertedReceiver = true
        )
    }

    fun hasEffect() : Boolean {
        return  paramEffect.isNotEmpty() ||
                receiverEffect != UtilEffect.N ||
                (fvEffect is FVEVar) ||
                (fvEffect is FVEMap && fvEffect.map.isNotEmpty())
    }

    fun isParametric() : Boolean {
        return effectVars.isNotEmpty() || (fvEffect is FVEVar)
    }

    override fun toString() : String {
        return toString(" ")
    }

    fun toString(separator: String): String {
        val items = mutableListOf<String>()

        if (receiverEffect != UtilEffect.N) {
            items.add("(THIS: $receiverEffect)")
        }

        for ((i, effect) in paramEffect) {
            items.add("($i: $effect)")
        }

        if (fvEffect is FVEVar) {
            items.add("(FV: ${fvEffect})")
        } else if (fvEffect is FVEMap){
            for ((v, effect) in fvEffect.map) {
                items.add("(${(v as? FirPropertySymbol)?.name ?: v}: $effect)")
            }
        }

        val paramSignStr = paramSignature.entries.joinToString(separator = " ") { (i, s) ->
            "($i: ${s.toString(" ")})"
        }

        val strs = mutableListOf<String>(
            if (isClassMemberOrExtension) "[S Ext]" else "[S]"
        )

        if(paramSignStr.isNotEmpty()) {
            strs.push("PS={$paramSignStr}")
        }

        if (items.isNotEmpty()) {
            strs.push("EFF={${items.joinToString(separator = " ")}}")
        } else {
            strs.push("<noeffect>")
        }

        return strs.joinToString(separator)
    }
}


/* Utilization Effect */
sealed interface UtilEffect {
    data object U : UtilEffect
    data object N : UtilEffect
    data object I : UtilEffect
    data class Var(val name: String): UtilEffect {
        override fun toString(): String {
            return "\$$name"
        }
    }
    data class Err(val message: String): UtilEffect

    operator fun plus(other: UtilEffect): UtilEffect {
        if (this is Err) return this
        if (other is Err) return other

        if (this == other) return this
        if (this == I || other == I) return I
        if (this == U || other == U) return U

        if (this is Var) return this
        if (other is Var) return other

        return N
    }

    operator fun times(other: UtilEffect): UtilEffect {
        if (this is Err) return this
        if (other is Err) return other

        if (this == other) return this
        if (this == I || other == I) return I
        if (this == N || other == N) return N

        if (this is Var) return this
        if (other is Var) return other

        return U
    }
}

/* FV Effect Signature */

sealed interface FVEffectSign {
    // FVEMap should only be constructable from inference
    data class FVEMap(val map: Map<FirBasedSymbol<*>, UtilEffect> = mapOf()): FVEffectSign

    // Parametric FV Effect in annotated source codes
    data class FVEVar(val name: String): FVEffectSign {
        override fun toString(): String {
            return "\$$name"
        }
    }
}

/* Instantiation and Unification */
fun Signature.instantiateWith(arguments: List<Signature?>) : Signature {
    if (effectVars.isEmpty() && fvEffect !is FVEVar) return this

    val env : VarEffectEnv = mutableMapOf()
    effectVars.forEach { it -> env[it] = mutableSetOf() }

    val fvEnv : FVEffectEnv = mutableMapOf()

    for ((i, paramSign) in paramSignature) {
        val argSign =  arguments.getOrNull(i) ?: continue
        unifySignature(env, fvEnv, paramSign, argSign)
    }

    return Signature(
        isClassMemberOrExtension,
        paramSignature,

        paramEffect = paramEffect.instantiateBy(env),
        receiverEffect = receiverEffect.instantiateBy(env),
        fvEffect = fvEffect.instantiateBy(env, fvEnv),
        convertedReceiver
    )
}

fun unifySignature(env: VarEffectEnv, fvEnv: FVEffectEnv, target: Signature, concrete: Signature) {

    unifyEffect(env, target.receiverEffect, concrete.receiverEffect)

    for ((i, effect) in target.paramEffect) {
        if (effect !is Var) continue
        unifyEffect(env, effect, concrete.paramEffect[i])
    }

    unifyFVSign(env, fvEnv, target.fvEffect, concrete.fvEffect)
}

fun unifyFVSign(env: VarEffectEnv, fvEnv: FVEffectEnv, target: FVEffectSign, concrete: FVEffectSign) {
    if (concrete !is FVEMap) return // TODO: error?

    when (target) {
        is FVEMap -> {
            for ((v, effect) in target.map) {
                if (effect !is Var) continue
                unifyEffect(env, effect, concrete.map[v])
            }
        }

        is FVEVar -> {
            fvEnv[target] = concrete
            //TODO: error if already exist?
        }
    }
}

fun unifyEffect(env: VarEffectEnv, target: UtilEffect, concrete: UtilEffect?) {
    if (target == concrete) return
    if (target !is Var) return // TODO: error?

    if (concrete == null) return

    env.addEffect(target, concrete)
}


typealias VarEffectEnv = MutableMap<Var, MutableSet<UtilEffect>>

fun VarEffectEnv.addEffect(key: Var, effect: UtilEffect) {
    if (key !in this) {
        this[key] = mutableSetOf()
    }

    this[key]?.add(effect)
}

typealias FVEffectEnv = MutableMap<FVEVar, FVEMap>

fun FVEffectSign.instantiateBy(env: VarEffectEnv, fvEnv: FVEffectEnv) : FVEffectSign {
    if (this is FVEMap) return this.instantiateBy(env)

    return fvEnv[this]?.instantiateBy(env) ?: this
}

fun FVEMap.instantiateBy(env: VarEffectEnv): FVEMap {
    return FVEMap(map.instantiateBy(env))
}

fun <K> Map<K, UtilEffect>.instantiateBy(env: VarEffectEnv) : Map<K, UtilEffect> {
    return entries.associate {(key, eff) -> Pair(key, eff.instantiateBy(env)) }
}

fun UtilEffect.instantiateBy(env: VarEffectEnv) : UtilEffect{
    if (this !is Var) return this
    return combine(env[this]) ?: this
}

fun combine(effects: Set<UtilEffect>?) : UtilEffect? {
    if (effects.isNullOrEmpty()) return null

    var combinedEff : UtilEffect = UtilEffect.U

    for (effect in effects) {
        combinedEff *= effect
    }

    return combinedEff
}

/* Helper */

fun FirFunctionSymbol<*>.getParameterEffects() : Map<Int, UtilEffect> {
    return valueParameterSymbols.asSequence()
        .withIndex()
        .mapNotNull { (idx, fir) ->
            if (fir.containsAnnotation(Commons.Annotations.Consume)) {
                Pair(idx, UtilEffect.U)
            } else {
                null
            }
        }
        .associate { it }
}