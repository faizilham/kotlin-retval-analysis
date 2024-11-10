package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.isInvalidPrev
import com.faizilham.kotlin.retval.fir.validNextSize
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.resolvedType

object UtilizationBackChecker :  FirControlFlowChecker(MppCheckerKind.Common) {
    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter, context: CheckerContext) {
        if (graph.kind != ControlFlowGraph.Kind.Function || graph.declaration?.isSynthetic != false) {
            return
        }

//        val logging = context.containingFile?.name == "consuming.kt" && graph.name == "insideNoCrossover"
        val logging = false
        val funcAnalyzer = FuncAnalysis(context, logging)

        funcAnalyzer.analyzeGraph(graph)
    }

    class FuncAnalysis(
        private val context: CheckerContext,
        private val logging: Boolean = false
    ) {
        val data = FuncAnalysisData()

        fun analyzeGraph(graph: ControlFlowGraph) {
            for (node in graph.nodes) {
                analyzeNode(node)
                (node as? CFGNodeWithSubgraphs<*>)?.subGraphs?.forEach { analyzeGraph(it) }
            }
        }

        private fun analyzeNode(node: CFGNode<*>) {
            val info = propagatePathInfo(node)

            when (node) {
                is VariableDeclarationNode -> handleVarDecl(node, info)
                is VariableAssignmentNode -> handleVarAssign(node, info)
//                is FunctionExitNode -> {
//                    log("Exit ${node.owner.name}")
//                    for ((sym, ref) in info.variableValue) {
//                        log("$sym -> $ref")
//                    }
//                }
                else -> {}
            }
        }

        private fun handleVarDecl(node: VariableDeclarationNode, info: FuncAnalysisPathInfo) {
            data.variableRecords[node.fir.symbol] = VariableRecord(node.owner, node.fir.isVal)

            val varType = node.fir.returnTypeRef.coneType
            if (!varType.isSomeFunctionType(context.session)) return

            val valExpr = node.fir.initializer ?: return

            setFuncRef(node, node.fir.symbol, valExpr, info)
        }

        private fun handleVarAssign(node: VariableAssignmentNode, info: FuncAnalysisPathInfo) {
            val varType = node.fir.lValue.resolvedType

            if (!varType.isSomeFunctionType(context.session)) return

            val varSymbol = node.fir.calleeReference?.symbol ?: return

            setFuncRef(node, varSymbol, node.fir.rValue, info)
        }

        private fun setFuncRef(node: CFGNode<*>, varSymbol: FirBasedSymbol<*>, valExpr: FirExpression, info: FuncAnalysisPathInfo) {
            val funcRef =
                when (valExpr) {
                    is FirAnonymousFunctionExpression -> FuncRefValue.LambdaRef(valExpr.anonymousFunction)
                    is FirCallableReferenceAccess -> FuncRefValue.CallableRef(valExpr)

                    is FirQualifiedAccessExpression -> {
                        val quaSymbol = valExpr.calleeReference.symbol
                        val quaRecord = data.variableRecords[quaSymbol]
                        val isLocalOrVal = (quaRecord != null) && (quaRecord.isVal || quaRecord.owner == node.owner)

                        if (isLocalOrVal) {
                            info.getVarValue(quaSymbol)
                        } else {
                            null
                        }
                    }
                    else -> null
                }

            data.pathInfos[node] = info.withNewVarValue(varSymbol, funcRef ?: FuncRefValue.Top)
        }

        private fun propagatePathInfo(node: CFGNode<*>) : FuncAnalysisPathInfo {
            val pathInfos = node.previousNodes.asSequence()
                .filterNot { it.isInvalidPrev(node) }
                .mapNotNull { data.pathInfos[it] }
                .toList()

            val mustCopy = node.previousNodes.getOrNull(0)?.validNextSize() != 1
            val info = pathInfos.mergeAll(mustCopy) ?: FuncAnalysisPathInfo()

            data.pathInfos[node] = info

            return info
        }

        private fun log(message: Any?) {
            if (logging) println(message)
        }
    }
}

class FuncAnalysisData {
    val variableRecords : MutableMap<FirBasedSymbol<*>, VariableRecord> = mutableMapOf()
    val pathInfos : MutableMap<CFGNode<*>, FuncAnalysisPathInfo> = mutableMapOf()
}

data class VariableRecord(val owner: ControlFlowGraph, val isVal: Boolean)

class FuncAnalysisPathInfo(
    private val variableValue: DefaultMapLat<FirBasedSymbol<*>, FuncRefValue> = DefaultMapLat(FuncRefValue.Bot),
) : IPathInfo<FuncAnalysisPathInfo> {
    override fun merge(other: FuncAnalysisPathInfo): FuncAnalysisPathInfo {
        val mergedVarValue = variableValue.join(other.variableValue)
        return FuncAnalysisPathInfo(mergedVarValue)
    }

    override fun copy(): FuncAnalysisPathInfo {
        return FuncAnalysisPathInfo(variableValue.copy())
    }

    fun withNewVarValue(varSymbol: FirBasedSymbol<*>, funcRef: FuncRefValue): FuncAnalysisPathInfo {
        val modified = copy()
        modified.variableValue[varSymbol] = funcRef
        return modified
    }

    fun getVarValue(varSymbol: FirBasedSymbol<*>?) = variableValue.getWithDefault(varSymbol)
}

interface IPathInfo<T: IPathInfo<T>> {
    fun copy(): T
    fun merge(other: T): T
}

fun<T: IPathInfo<T>> List<T>.mergeAll(mustCopy : Boolean = false) : T? {
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

interface Lattice<T: Lattice<T>> {
    fun join(other: T): T
}

class DefaultMapLat<K, V: Lattice<V>> private constructor (val defaultVal: V, private val _map: MutableMap<K, V>)
    : MutableMap<K, V> by _map, Lattice<DefaultMapLat<K, V>>
{
    constructor(defaultVal: V) : this(defaultVal, mutableMapOf())

    override fun join(other: DefaultMapLat<K, V>): DefaultMapLat<K, V> {
        val combinedKeys = keys + other.keys

        val combined = DefaultMapLat<K, V>(defaultVal)

        for (key in combinedKeys) {
            val left = this[key] ?: defaultVal
            val right = other[key] ?: defaultVal

            combined[key] = left.join(right)
        }

        return combined
    }

    fun getWithDefault(key: K?) = this[key] ?: defaultVal

    fun copy(): DefaultMapLat<K, V> {
        return DefaultMapLat(defaultVal, _map.toMutableMap())
    }
}


sealed interface FuncRefValue : Lattice<FuncRefValue> {
    data object Top : FuncRefValue
    data object Bot : FuncRefValue

    class LambdaRef(val lambda: FirAnonymousFunction) : FuncRefValue {
        override fun equals(other: Any?): Boolean {
            return (other is LambdaRef) && lambda == other.lambda
        }

        override fun hashCode(): Int {
            return lambda.hashCode()
        }
    }

    class CallableRef(val ref: FirCallableReferenceAccess) : FuncRefValue {
        override fun equals(other: Any?): Boolean {
            return (other is CallableRef) && ref == other.ref
        }

        override fun hashCode(): Int {
            return ref.hashCode()
        }
    }

    fun leq(other: FuncRefValue): Boolean {
        return this == Bot || other == Top || this == other
    }

    fun geq(other: FuncRefValue): Boolean {
        return this == Top || other == Bot || this == other
    }

    override fun join(other: FuncRefValue): FuncRefValue {
        if (this.leq(other)) {
            return other
        } else if (this.geq(other)) {
            return this
        }

        return Top
    }
}