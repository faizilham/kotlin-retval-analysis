package com.faizilham.kotlin.retval.fir.checkers.analysis

import com.faizilham.kotlin.retval.fir.checkers.analysis.UtilEffect.Var
import com.faizilham.kotlin.retval.fir.checkers.commons.Commons
import com.faizilham.kotlin.retval.fir.checkers.commons.containsAnnotation
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

data class Signature(
    val isClassMemberOrExtension: Boolean,
    val returningUtilizable: Boolean,

    val receiverSignature: Signature?,
    val paramSignature: Map<Int, Signature>,

    val paramEffect : Map<Int, UtilEffect>,
    val receiverEffect: UtilEffect,
    val fvEffect: Map<FirBasedSymbol<*>, UtilEffect>,
    val convertedReceiver: Boolean = false
) {
    fun convertReceiverToParameter() : Signature {
        val newParamEffect = mutableMapOf<Int, UtilEffect>()
        if (receiverEffect != UtilEffect.N) newParamEffect[0] = receiverEffect

        for ((i, effect) in paramEffect) {
            newParamEffect[i + 1] = effect
        }

        val newParamSign = mutableMapOf<Int, Signature>()
        if (receiverSignature != null) newParamSign[0] = receiverSignature

        for ((i, sign) in paramSignature) {
            newParamSign[i + 1] = sign
        }

        return Signature(
            isClassMemberOrExtension = false,
            returningUtilizable,

            receiverSignature = null,
            paramSignature = newParamSign,

            paramEffect = newParamEffect,
            receiverEffect = UtilEffect.N,
            fvEffect,
            convertedReceiver = true
        )
    }

    fun hasEffect() : Boolean {
        return paramEffect.isNotEmpty() || fvEffect.isNotEmpty() || receiverEffect != UtilEffect.N
    }
}

fun FirQualifiedAccessExpression.getParameterEffects() : Map<Int, UtilEffect> {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return mapOf()

    return funcSymbol.valueParameterSymbols.asSequence()
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

fun Signature.instantiateWith(other: Signature) : Signature {
    val env = collectEffectVars()

    if (env.isEmpty()) return this

    val concrete =
        if (convertedReceiver && !other.convertedReceiver){
            other.convertReceiverToParameter()
        } else {
            other
        }

    if (receiverSignature != null && concrete.receiverSignature != null) {
        receiverSignature.unifySignature(env, concrete.receiverSignature)
    }

    for ((i, targetParSign) in paramSignature) {
        val concreteParSign =  concrete.paramSignature[i] ?: continue

        targetParSign.unifySignature(env, concreteParSign)
    }

    return Signature(
        isClassMemberOrExtension,
        returningUtilizable,

        receiverSignature,
        paramSignature,

        paramEffect = paramEffect.instantiateBy(env),
        receiverEffect = receiverEffect.instantiateBy(env),
        fvEffect = fvEffect.instantiateBy(env),
        convertedReceiver
    )
}

private fun Signature.collectEffectVars() : VarEffectEnv {
    val env = mutableMapOf<UtilEffect.Var, MutableSet<UtilEffect>>()

    if (receiverEffect is UtilEffect.Var) env[receiverEffect] = mutableSetOf()

    for ((_, effect) in paramEffect) {
        if (effect is UtilEffect.Var) env[effect] = mutableSetOf()
    }

    for ((_, effect) in fvEffect) {
        if (effect is UtilEffect.Var) env[effect] = mutableSetOf()
    }

    return env
}

fun Signature.unifySignature(env: VarEffectEnv, concrete: Signature) {
    unifyEffect(env, receiverEffect, concrete.receiverEffect)

    for ((_, effect) in paramEffect) {
        if (effect is UtilEffect.Var) env[effect] = mutableSetOf()

        unifyEffect(env, receiverEffect, concrete.receiverEffect)

    }

    for ((_, effect) in fvEffect) {
        if (effect is UtilEffect.Var) env[effect] = mutableSetOf()
    }
}

fun unifyEffect(env: VarEffectEnv, target: UtilEffect, concrete: UtilEffect?) {
    if (target == concrete) return
    if (target !is Var) return // TODO: error?

    if (concrete == null) return

    env.addEffect(target, concrete)
}


typealias VarEffectEnv = MutableMap<UtilEffect.Var, MutableSet<UtilEffect>>

fun VarEffectEnv.addEffect(key: UtilEffect.Var, effect: UtilEffect) {
    if (key !in this) {
        this[key] = mutableSetOf()
    }

    this[key]?.add(effect)
}

fun <K> Map<K, UtilEffect>.instantiateBy(env: VarEffectEnv) : Map<K, UtilEffect> {
    return entries.associate {(k, eff) -> Pair(k, eff.instantiateBy(env)) }
}

fun UtilEffect.instantiateBy(env: VarEffectEnv) : UtilEffect{
    if (this !is Var) return this
    return combine(env[this])
}

fun combine(effects: Set<UtilEffect>?) : UtilEffect {
    if (effects.isNullOrEmpty()) return UtilEffect.Err("No Instantiation")

    var combinedEff : UtilEffect = UtilEffect.U

    for (effect in effects) {
        combinedEff *= effect
    }

    return combinedEff
}
