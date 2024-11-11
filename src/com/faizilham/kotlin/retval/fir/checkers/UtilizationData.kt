package com.faizilham.kotlin.retval.fir.checkers

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

class UtilizationData {
    val pathInfos : MutableMap<CFGNode<*>, PathInfo> = mutableMapOf()
    private val valueSources : MutableMap<CFGNode<*>, ValueSource> = mutableMapOf()
    private val lastFirValueNode : MutableMap<FirElement, CFGNode<*>> = mutableMapOf()
    private val consumingLambdas : MutableMap<FirDeclaration, FunctionInfo> = mutableMapOf()
    private val variableOwner : MutableMap<FirBasedSymbol<*>, ControlFlowGraph> = mutableMapOf()

    // value sources

    fun addValueSource(node: CFGNode<*>, source: ValueSource) {
        valueSources[node] = source
        lastFirValueNode[node.fir] = node
    }

    fun getValueSource(node: CFGNode<*>) = valueSources[node]

    fun getValueSource(fir: FirElement) : ValueSource? {
        val node = lastFirValueNode[fir] ?: return null
        return valueSources[node]
    }

    fun removeValueSource(node: CFGNode<*>) = valueSources.remove(node)

    // lambda info

    fun addLambdaInfo(func: FirDeclaration, info: FunctionInfo) {
        consumingLambdas[func] = info
    }

    fun getLambdaInfo(func: FirDeclaration) = consumingLambdas[func]

    fun getLambdas() = consumingLambdas

    // owners
    fun setVarOwner(variable: FirBasedSymbol<*>, owner: ControlFlowGraph) {
        variableOwner[variable] = owner
    }

    fun getVarOwner(variable: FirBasedSymbol<*>) = variableOwner[variable]
}

data class FunctionInfo(
    val isLambda: Boolean,
    val isClassMemberOrExtension: Boolean,
    val returningConsumable : Boolean,
    val returnIsConsumed : Boolean = false,
    val consumingThis : Boolean = false,
    val consumedParameters : Set<Int> = setOf(),
    val consumedFreeVariables : Set<FirBasedSymbol<*>> = setOf()
)

fun FunctionInfo.convertThisToFirstParameter() : FunctionInfo {
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

sealed class VarValue() {
    abstract val funcRef : FunctionInfo?
    abstract val utilVal : ValueSource?

    class FuncValue(private val _funcRef: FunctionInfo) : VarValue() {
        override val funcRef: FunctionInfo
            get() = _funcRef

        override val utilVal: ValueSource?
            get() = null

        override fun equals(other: Any?): Boolean {
            return other is FuncValue && _funcRef == other._funcRef
        }

        override fun hashCode(): Int {
            return _funcRef.hashCode()
        }
    }

    class UtilValue(private val _utilVal: ValueSource) : VarValue() {
        override val funcRef: FunctionInfo?
            get() = null

        override val utilVal: ValueSource
            get() = _utilVal

        override fun equals(other: Any?): Boolean {
            return other is UtilValue && _utilVal == other._utilVal
        }

        override fun hashCode(): Int {
            return _utilVal.hashCode()
        }
    }
}

fun ValueSource.toVarValue() = VarValue.UtilValue(this)
fun FunctionInfo.toVarValue() = VarValue.FuncValue(this)

sealed class ValueSource {
    sealed class DirectSource : ValueSource()
    sealed class CreationSource(val node: CFGNode<*>) : DirectSource() {}
    sealed class NonLocalSource: DirectSource() {}

    // creation sources
    class FuncCall(node: CFGNode<*>) : CreationSource(node) {
        override fun equals(other: Any?): Boolean {
            return other is FuncCall && node == other.node
        }

        override fun hashCode(): Int {
            return node.hashCode()
        }

    }

    class Choices(node: CFGNode<*>, val sources: List<CreationSource>) : CreationSource(node) {
        override fun equals(other: Any?): Boolean {
            return other is Choices && node == other.node
        }

        override fun hashCode(): Int {
            return node.hashCode()
        }
    }

    // non-local
    class ValueParameter(val fir: FirValueParameter, val index: Int) : NonLocalSource() {
        override fun equals(other: Any?): Boolean {
            return other is ValueParameter && fir == other.fir
        }

        override fun hashCode(): Int {
            return fir.hashCode()
        }
    }

    class ThisReference(val node: CFGNode<*>) : NonLocalSource() {
        override fun equals(other: Any?): Boolean {
            return other is ThisReference && node.owner == other.node.owner
        }

        override fun hashCode(): Int {
            return node.owner.hashCode()
        }
    }

    class FreeVariable(val symbol: FirBasedSymbol<*>) : NonLocalSource() {
        override fun equals(other: Any?): Boolean {
            return other is FreeVariable && symbol == other.symbol
        }

        override fun hashCode(): Int {
            return symbol.hashCode()
        }
    }

    // indirect sources
    class LocalVar(val symbol: FirBasedSymbol<*>) : ValueSource() {
        override fun equals(other: Any?): Boolean {
            return other is LocalVar && symbol == other.symbol
        }

        override fun hashCode(): Int {
            return symbol.hashCode()
        }
    }


}

class PathInfo(
    private val knownVariables : MutableMap<FirBasedSymbol<*>, VarValue> = mutableMapOf(),
    private val unutilizedCreation : MutableSet<ValueSource.CreationSource> = mutableSetOf(),
    private val nonLocalUtilizations : MutableMap<ValueSource.NonLocalSource, NonLocalUtilLattice> = mutableMapOf()
) {
    fun copy() : PathInfo {
        return PathInfo(
            knownVariables.toMutableMap(),
            unutilizedCreation.toMutableSet(),
            nonLocalUtilizations.toMutableMap()
        )
    }

    // variables
    fun setVarValue(variable: FirBasedSymbol<*>, valueSource: ValueSource) {
        knownVariables[variable] = valueSource.toVarValue()
    }

    fun setVarValue(variable: FirBasedSymbol<*>, funcRef: FunctionInfo) {
        knownVariables[variable] = funcRef.toVarValue()
    }

    fun getVarValue(variable: FirBasedSymbol<*>) = knownVariables[variable]

    // unutilized values

    fun addUnutilizedCreation(src: ValueSource.CreationSource) {
        unutilizedCreation.add(src)
    }

    fun removeUnutilizedCreation(src: ValueSource.CreationSource) : Boolean {
        return unutilizedCreation.remove(src)
    }

    fun setNonLocalUtilization(src: ValueSource.NonLocalSource, utilLattice: NonLocalUtilLattice) {
        nonLocalUtilizations[src] = utilLattice
    }

    fun resetUtilization() {
        unutilizedCreation.clear()
        nonLocalUtilizations.clear()
    }

    fun getUnutilizedCreations() = unutilizedCreation

    fun getNonLocalUtilizations() = nonLocalUtilizations

    fun merge(other: PathInfo) : PathInfo {
        val unutilized = unutilizedCreation.union(other.unutilizedCreation).toMutableSet()

        val merged = PathInfo(mutableMapOf(), unutilized)

        val commonVars = knownVariables.keys.intersect(other.knownVariables.keys)

        for (variable in commonVars) {
            val nodeValue = knownVariables[variable] ?: continue
            if (nodeValue == other.knownVariables[variable]) {
                merged.knownVariables[variable] = nodeValue
            }
        }

        val nonLocalSources = nonLocalUtilizations.keys.union(other.nonLocalUtilizations.keys)

        for (nonLocal in nonLocalSources) {
            val utilization1 = nonLocalUtilizations[nonLocal] ?: NonLocalUtilLattice.Unchanged
            val utilization2 = other.nonLocalUtilizations[nonLocal] ?: NonLocalUtilLattice.Unchanged

            val joinedUtil = utilization1.join(utilization2)

            if (!joinedUtil.equalTo(NonLocalUtilization.Unchanged)) {
                merged.nonLocalUtilizations[nonLocal] = joinedUtil
            }
        }

        return merged
    }
}

fun List<PathInfo>.mergeInfos(mustCopy : Boolean = false) : PathInfo {
    val info = when(size) {
        0 -> PathInfo()
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

enum class NonLocalUtilization(val value: Int) {
    Utilized(4),
    Unutilized(2),
    Unchanged(1),
    Inaccessible(0)
}

// TODO: refactor?
class NonLocalUtilLattice(private val value : Int = NonLocalUtilization.Inaccessible.value) {

    companion object {
        val Utilized = NonLocalUtilLattice(NonLocalUtilization.Utilized)
        val Unchanged = NonLocalUtilLattice(NonLocalUtilization.Unchanged)
        val Unutilized = NonLocalUtilLattice(NonLocalUtilization.Unchanged)
        val Inaccessible = NonLocalUtilLattice(NonLocalUtilization.Inaccessible)
        val Unknown = NonLocalUtilLattice(Utilized.value + Unchanged.value + Unutilized.value)
    }

    constructor(initial: NonLocalUtilization) : this(initial.value) {}

    fun join(other: NonLocalUtilLattice) = NonLocalUtilLattice(this.joinVal(other))

    fun meet(other: NonLocalUtilLattice) = NonLocalUtilLattice(this.meetVal(other))

    fun leq(other: NonLocalUtilLattice) = this.meetVal(other) == this.value

    fun geq(other: NonLocalUtilLattice) = this.joinVal(other) == this.value

    private fun joinVal(other: NonLocalUtilLattice) = this.value.or(other.value)

    private fun meetVal(other: NonLocalUtilLattice) = this.value.and(other.value)

    fun equalTo(utilization: NonLocalUtilization) = value == utilization.value

    fun contains(utilization: NonLocalUtilization) = value.and(utilization.value) == utilization.value
}
