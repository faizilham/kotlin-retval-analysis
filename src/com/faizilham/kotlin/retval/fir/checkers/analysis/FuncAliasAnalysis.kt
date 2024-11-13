package com.faizilham.kotlin.retval.fir.checkers.analysis

import com.faizilham.kotlin.retval.fir.checkers.commons.isInvalidPrev
import com.faizilham.kotlin.retval.fir.checkers.commons.validNextSize
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.resolvedType

class FuncAliasAnalysis(private val context: CheckerContext, private val logging: Boolean = false) {
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
            is FunctionEnterNode -> handleFunctionStart(node)
            is VariableDeclarationNode -> handleVarDecl(node, info)
            is VariableAssignmentNode -> handleVarAssign(node, info)

            else -> {}
        }
    }

    private fun handleFunctionStart(node: FunctionEnterNode) {
        node.fir.valueParameters.forEachIndexed { index, valParam ->
            data.variableRecords[valParam.symbol] = VariableRecord(node.owner, true, index)
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

class FuncAnalysisData {
    val variableRecords : MutableMap<FirBasedSymbol<*>, VariableRecord> = mutableMapOf()
    val pathInfos : MutableMap<CFGNode<*>, FuncAnalysisPathInfo> = mutableMapOf()
}

data class VariableRecord(
    val owner: ControlFlowGraph,
    val isVal: Boolean,
    val paramIndex: Int = -1 // paramIndex < 0 means not a param
)

class FuncAnalysisPathInfo private constructor(
    private val variableValue: DefaultMapLat<FirBasedSymbol<*>, FuncRefValue>,
) : PathInfo<FuncAnalysisPathInfo>
{
    constructor() : this(DefaultMapLat(FuncRefValue.Bot))

    override fun merge(other: FuncAnalysisPathInfo): FuncAnalysisPathInfo {
        val mergedVarValue = variableValue.merge(other.variableValue)
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

sealed interface FuncRefValue : Lattice<FuncRefValue> {
    data object Top : FuncRefValue
    data class LambdaRef(val lambda: FirAnonymousFunction) : FuncRefValue
    data class CallableRef(val ref: FirCallableReferenceAccess) : FuncRefValue
    data object Bot : FuncRefValue

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

    override fun meet(other: FuncRefValue): FuncRefValue {
        if (this.geq(other)) {
            return other
        } else if (this.leq(other)) {
            return this
        }

        return Bot
    }
}
