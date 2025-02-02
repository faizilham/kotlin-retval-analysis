package com.faizilham.kotlin.retval.fir.checkers.analysis

import com.faizilham.kotlin.retval.fir.checkers.analysis.FVEffect.FVEMap
import com.faizilham.kotlin.retval.fir.checkers.analysis.FVEffect.FVEVar
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
    val fvEffect: FVEffect,

    val contextUtilAnnotation: UtilAnnotation?,
    val paramUtilAnnotations: Map<Int, UtilAnnotation>,
    val returnUtilAnnotation: UtilAnnotation,

    val convertedReceiver: Boolean = false
) {
    val effectVars : Set<Var>
    val utilVars: Set<UtilAnnotation.Var>

    init {
        val effVars = mutableSetOf<Var>()

        if (receiverEffect is Var) effVars.add(receiverEffect)

        for ((_, effect) in paramEffect) {
            if (effect is Var) effVars.add(effect)
        }

        if (fvEffect is FVEMap) {
            for ((_, effect) in fvEffect.map) {
                if (effect is Var) effVars.add(effect)
            }
        }

        effectVars = effVars

        val utilVars = mutableSetOf<UtilAnnotation.Var>()

        if (contextUtilAnnotation is UtilAnnotation.Var) {
            utilVars.add(contextUtilAnnotation)
        }

        if (returnUtilAnnotation is UtilAnnotation.Var) {
            utilVars.add(returnUtilAnnotation)
        }

        for ((_, util) in paramUtilAnnotations) {
            if (util is UtilAnnotation.Var) utilVars.add(util)
        }

        this.utilVars = utilVars
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

            contextUtilAnnotation = null, //TODO: fix
            paramUtilAnnotations = mapOf(),
            returnUtilAnnotation = UtilAnnotation.Val(UtilLattice.Top),

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
        return effectVars.isNotEmpty() || (fvEffect is FVEVar) || utilVars.isNotEmpty()
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

        if (contextUtilAnnotation != null) {
            strs.add("(THIS_U: $contextUtilAnnotation)")
        }

        for ((i, util) in paramUtilAnnotations) {
            strs.add("(${i}_U: $util)")
        }

        strs.add("Ret_U: $returnUtilAnnotation")

        if (items.isNotEmpty()) {
            strs.push("EFF={${items.joinToString(separator = " ")}}")
        } else {
            strs.push("<noeffect>")
        }

        return strs.joinToString(separator)
    }
}

/* Util Lattice */
sealed class UtilLattice(private val value: Int): Lattice<UtilLattice> {
    data object Top: UtilLattice(2)
    data object NU: UtilLattice(1)
    data object UT: UtilLattice(1)
    data object Bot: UtilLattice(0)

    fun leq(other: UtilLattice) = value < other.value || this == other

    fun geq(other: UtilLattice) = value > other.value || this == other

    override fun join(other: UtilLattice): UtilLattice{
        if (this.value == other.value && this != other) return Top

        return if (this.geq(other)) this else other
    }

    override fun meet(other: UtilLattice): UtilLattice {
        if (this.value == other.value && this != other) return Top

        return if (this.geq(other)) other else this
    }
}

sealed interface UtilAnnotation {
    data class Val(val value: UtilLattice): UtilAnnotation {
        override fun toString(): String {
            return "@$value"
        }
    }

    data class Var(val name: String, val fromId: Int, val fromLambda: Boolean = false): UtilAnnotation {
        override fun toString(): String {
            return "~$name"
        }
    }
}

/* Utilization Effect */
sealed interface UtilEffect {
    data object U : UtilEffect
    data object N : UtilEffect
    data object I : UtilEffect
    data object X : UtilEffect
    data class Var(val name: String): UtilEffect {
        override fun toString(): String {
            return "\$$name"
        }
    }

    operator fun times(other: UtilEffect): UtilEffect {
        if (this == other) return this
        if (this == X || other == X) return X
        if (this == N || other == N) return N

        if (this is Var) return this
        if (other is Var) return other

        return U
    }
}

/* FV Effect Signature */
sealed interface FVEffect {
    // FVEMap should only be constructable from inference
    data class FVEMap(val map: Map<FirBasedSymbol<*>, UtilEffect> = mapOf()): FVEffect

    // Parametric FV Effect in annotated source codes
    data class FVEVar(val name: String): FVEffect {
        override fun toString(): String {
            return "\$$name"
        }
    }
}

/* Instantiation and Unification */

class SignatureInstanceException(message: String) : Exception(message)

fun Signature.instantiateWith(arguments: List<Signature?>, contextUtil: UtilAnnotation?, paramUtils: List<UtilAnnotation?>) : Pair<Signature, InstantiationEnv> {
    val env = InstantiationEnv()
    effectVars.forEach { env.eff[it] = mutableSetOf() }

    for ((i, paramSign) in paramSignature) {
        val argSign = arguments.getOrNull(i) ?: continue
        unify(env, paramSign, argSign)
    }

    if (contextUtilAnnotation != null && contextUtil != null) {
        unify(env, contextUtilAnnotation, contextUtil)
    }

    for ((i, paramUtilAnno) in paramUtilAnnotations) {
        val paramUtil = paramUtils.getOrNull(i) ?: continue
        unify(env, paramUtilAnno, paramUtil)
    }

    return Pair(instantiateBy(env), env)
}

fun unify(env: InstantiationEnv, target: Signature, concrete: Signature) {
    unify(env, target.receiverEffect, concrete.receiverEffect)

    if (target.contextUtilAnnotation != null && concrete.contextUtilAnnotation != null) {
        unify(env, target.contextUtilAnnotation, concrete.contextUtilAnnotation)
    }

    for ((i, effect) in target.paramEffect) {
        unify(env, effect, concrete.paramEffect[i] ?: UtilEffect.N)
    }

    for ((i, paramUtilAnno) in target.paramUtilAnnotations) {
        val concreteUtilAnno = concrete.paramUtilAnnotations[i] ?: continue
        unify(env, paramUtilAnno, concreteUtilAnno)
    }

    unify(env, target.fvEffect, concrete.fvEffect)

    unify(env, target.returnUtilAnnotation, concrete.returnUtilAnnotation)
}

fun unify(env: InstantiationEnv, target: FVEffect, concrete: FVEffect) {
    if (concrete !is FVEMap) return

    when (target) {
        is FVEMap -> {
            for ((v, effect) in target.map) {
                unify(env, effect, concrete.map[v] ?: UtilEffect.N)
            }
        }

        is FVEVar -> {
            if (target in env.fv && env.fv[target] != concrete) {
                throw SignatureInstanceException("Mismatch FV var $target")
            }

            env.fv[target] = concrete
        }
    }
}

fun unify(env: InstantiationEnv, target: UtilEffect, concrete: UtilEffect) {
    if (target == concrete) return
    if (target !is Var) throw SignatureInstanceException("Mismatch effect: got $concrete, expected $target")

    env.addEffect(target, concrete)
}

fun unify(env: InstantiationEnv, target: UtilAnnotation, concrete: UtilAnnotation) {
    if (target is UtilAnnotation.Val) {
        if (concrete is UtilAnnotation.Val && !concrete.value.leq(target.value)) {
            throw SignatureInstanceException("Mismatch utilization: got $concrete, expected $target")
        } else if (concrete is UtilAnnotation.Var) {
            unify(env, concrete, target)
        }
    } else if (target is UtilAnnotation.Var && concrete is UtilAnnotation.Val) {
        env.addUtil(target, concrete)
    }
}

fun Signature.instantiateBy(env: InstantiationEnv) : Signature {
    return Signature(
        isClassMemberOrExtension,
        paramSignature = paramSignature.entries.associate { (i, sign) -> Pair(i, sign.instantiateBy(env)) },

        paramEffect = paramEffect.instantiateEff(env),
        receiverEffect = receiverEffect.instantiateBy(env),
        fvEffect = fvEffect.instantiateBy(env),

        contextUtilAnnotation = contextUtilAnnotation.instantiateBy(env),
        paramUtilAnnotations = paramUtilAnnotations.instantiateUtil(env),
        returnUtilAnnotation = returnUtilAnnotation.instantiateBy(env) ?: UtilAnnotation.Val(UtilLattice.Top),

        convertedReceiver,
    )
}

fun FVEffect.instantiateBy(env: InstantiationEnv) : FVEffect {
    if (this is FVEMap) return this.instantiateBy(env)

    return env.fv[this]?.instantiateBy(env) ?: FVEMap()
}

fun FVEMap.instantiateBy(env: InstantiationEnv): FVEMap {
    return FVEMap(map.instantiateEff(env))
}

fun <K> Map<K, UtilEffect>.instantiateEff(env: InstantiationEnv) : Map<K, UtilEffect> {
    return entries.associate {(key, eff) -> Pair(key, eff.instantiateBy(env)) }
}

fun Map<Int, UtilAnnotation>.instantiateUtil(env: InstantiationEnv) : Map<Int, UtilAnnotation> {
    return entries.associate {(key, eff) -> Pair(key, eff.instantiateBy(env) ?: UtilAnnotation.Val(UtilLattice.Top)) }
}

fun UtilEffect.instantiateBy(env: InstantiationEnv) : UtilEffect{
    if (this !is Var) return this

    val effects = env.eff[this]
    if (effects.isNullOrEmpty()) return UtilEffect.N

    if (effects.size == 1) {
        return effects.first()
    }

    throw SignatureInstanceException("Multiple instantiation of effect variable $this")
}

fun UtilAnnotation?.instantiateBy(env: InstantiationEnv) : UtilAnnotation? {
    if (this == null) return null
    if (this is UtilAnnotation.Var) {
        return env.util[this]
    }

    return this
}

/* Env */

data class InstantiationEnv(
    val eff: MutableMap<Var, MutableSet<UtilEffect>> = mutableMapOf(),
    val fv: MutableMap<FVEVar, FVEMap> = mutableMapOf(),
    val util: MutableMap<UtilAnnotation.Var, UtilAnnotation.Val> = mutableMapOf()
) {
    fun addEffect(key: Var, effect: UtilEffect) {
        if (key !in eff) {
            eff[key] = mutableSetOf()
        }

        eff[key]?.add(effect)
    }

    fun addUtil(target: UtilAnnotation.Var, concrete: UtilAnnotation.Val) {
        if (target !in util) {
            util[target] = concrete
            return
        }

        if (!concrete.value.leq(util[target]!!.value))
            throw SignatureInstanceException("Incompatible utilization instantiation for variable $target, prev: ${util[target]}, new: ${concrete}")
    }
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


