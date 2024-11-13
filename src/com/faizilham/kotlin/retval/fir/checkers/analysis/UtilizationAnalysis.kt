package com.faizilham.kotlin.retval.fir.checkers.analysis

import com.faizilham.kotlin.retval.fir.checkers.commons.*
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType

class UtilizationAnalysis(
    private val context: CheckerContext,
    private val funcData: FuncAnalysisData,
    private val logging: Boolean = false
)
{
    private val data = UtilAnalysisData()
    private val warnings: MutableList<FirElement> = mutableListOf()

    fun analyzeGraph(graph: ControlFlowGraph) {
        graph.subGraphs.forEach { analyzeGraph(it) }

        for (node in graph.nodes.asReversed()) {
            analyzeNode(node)
        }
    }

    fun report(reporter: DiagnosticReporter) {
        for (warnedFir in warnings) {
            reporter.reportOn(warnedFir.source, Utils.Warnings.UNCONSUMED_VALUE, context)
        }
    }

    private fun analyzeNode(node: CFGNode<*>) {
        val info = propagatePathInfo(node)

        when {
            node is FunctionEnterNode -> handleFunctionStart(node, info)
            node is FunctionCallNode -> handleFuncCallNode(node, info)
            node is VariableDeclarationNode -> handleVariableDeclaration(node, info)
            node is VariableAssignmentNode -> handleVariableAssign(node, info)

            node.isReturnNode() -> handleReturnNode(node, info)
            node.isIndirectValueSource() -> handleIndirectValueSource(node, info)
        }
    }

    // function start related

    private fun handleFunctionStart(node: FunctionEnterNode, info: UtilAnalysisPathInfo) {
        // Warnings
        for ((call, util) in info.callSiteUtils()) {
            if (!util.leq(UtilLattice.RT)) {
                warnings.add(call)
            }
        }

        // Func Info
        val currentFunction = node.fir

        if (currentFunction is FirAnonymousFunction) {
            data.lambdaFuncInfos[currentFunction] = buildFuncInfo(currentFunction, info)
        }
    }

    private fun buildFuncInfo(func: FirFunction, info: UtilAnalysisPathInfo): FunctionInfo {
        var consumingThis = false
        val consumedParameters = mutableSetOf<Int>()
        val consumedFreeVariables = mutableSetOf<FirBasedSymbol<*>>()

        for ((nonlocal, utilization) in info.nonLocalUtils()) {
            if (!utilization.leq(UtilLattice.UT)) continue

            when (nonlocal) {
                is ValueRef.ThisRef -> consumingThis = true
                is ValueRef.Params -> consumedParameters.add(nonlocal.index)
                is ValueRef.FreeVar -> consumedFreeVariables.add(nonlocal.symbol)
            }
        }

        val returningConsumable = func.returnTypeRef.coneType.hasMustConsumeAnnotation(context.session)
        val isExtension = func.receiverParameter != null

        return FunctionInfo(
            isLambda = true,
            isExtension,
            returningConsumable,
            returnIsConsumed = false,
            consumingThis,
            consumedParameters,
            consumedFreeVariables
        )
    }

    // function call related

    private fun handleFuncCallNode(node: FunctionCallNode, info: UtilAnalysisPathInfo) {
        val funcInfo = getFunctionInfo(node) ?: return

        if (funcInfo.hasNoEffect()) return

        consumeReceiver(node, info, funcInfo)
        consumeParameters(node, info, funcInfo)
        consumeFreeVariables(node, info, funcInfo)

        if (funcInfo.returningConsumable && !funcInfo.returnIsConsumed) {
            val callExprRef = getExprValueRef(node.fir, node) ?: return
            val util = info.getValRefUtil(callExprRef)

            info.setCallSiteUtil(node.fir, util)
        }
    }

    private fun consumeReceiver(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
        if (!funcInfo.consumingThis) return

        val receiver = node.fir.dispatchReceiver ?: node.fir.extensionReceiver ?: return
        val receiverRef = getExprValueRef(receiver, node) ?: return

        info.meetValRefUtil(receiverRef, UtilLattice.UT)
    }

    private fun consumeParameters(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
        for (consumedId in funcInfo.consumedParameters) {
            val arg = node.fir.argumentList.arguments.getOrNull(consumedId) ?: continue
            val argRef = getExprValueRef(arg, node) ?: continue
            info.meetValRefUtil(argRef, UtilLattice.UT)
        }
    }

    private fun consumeFreeVariables(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
        for (freeVar in funcInfo.consumedFreeVariables) {
            val valRef = getVarValueRef(freeVar, node) ?: continue
            info.meetValRefUtil(valRef, UtilLattice.UT)
        }
    }

    private fun getFunctionInfo(node: FunctionCallNode) : FunctionInfo? {
        val fir = node.fir
        if (fir.isInvoke()) return resolveInvokeFunctionInfo(node)

        return FunctionInfo(
            isLambda = false,
            isClassMemberOrExtension = fir.isClassMemberOrExtension(),
            returningConsumable = fir.resolvedType.hasMustConsumeAnnotation(context.session),
            returnIsConsumed = fir.hasDiscardableAnnotation(context.session),
            consumingThis = fir.hasConsumeAnnotation(context.session),
            consumedParameters = fir.getConsumedParameters()
        )
    }

    private fun resolveInvokeFunctionInfo(node: FunctionCallNode) : FunctionInfo? {
        val originalRef = (node.fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference ?: return null
        val originalSymbol = originalRef.toResolvedCallableSymbol() ?: return null

        val funcRef = funcData.pathInfos[node]?.getVarValue(originalSymbol) ?: return null
        var funcInfo = getFuncRefInfo(funcRef) ?: return null

        // NOTE: invoking extension function causes the context object to be regarded as first argument
        //       the dispatchReceiver is no longer the context object, but the function reference
        if (funcInfo.isClassMemberOrExtension) {
            funcInfo = funcInfo.convertThisToFirstParameter()
        }

        return funcInfo
    }

    private fun getFuncRefInfo(funcRef: FuncRefValue): FunctionInfo? {
        return when (funcRef) {
            is FuncRefValue.LambdaRef -> data.lambdaFuncInfos[funcRef.lambda]
            is FuncRefValue.CallableRef -> {
                val ref = funcRef.ref
                val returnType = ref.getReturnType() ?: return null

                FunctionInfo(
                    isLambda = false,
                    isClassMemberOrExtension = ref.isClassMemberOrExtension(),
                    returningConsumable = returnType.hasMustConsumeAnnotation(context.session),
                    returnIsConsumed = ref.hasDiscardableAnnotation(context.session),
                    consumingThis = ref.hasConsumeAnnotation(context.session),
                    consumedParameters = ref.getConsumedParameters()
                )
            }
            else -> null
        }
    }

    // variable assignment nodes related

    private fun handleVariableDeclaration(node: VariableDeclarationNode, info: UtilAnalysisPathInfo) {
        val varType = node.fir.returnTypeRef.coneType
        if (!varType.hasMustConsumeAnnotation(context.session)) return

        val varUtilization = getVarValueRef(node.fir.symbol, node)?.let { info.getValRefUtil(it) } ?: return

        val initValRef = getExprValueRef(node.fir.initializer, node) ?: return

        info.meetValRefUtil(initValRef, varUtilization)
    }

    private fun handleVariableAssign(node: VariableAssignmentNode, info: UtilAnalysisPathInfo) {
        val varType = node.fir.lValue.resolvedType
        if (!varType.hasMustConsumeAnnotation(context.session)) return

        val varSymbol = node.fir.calleeReference?.symbol ?: return
        val varRef = getVarValueRef(varSymbol, node) ?: return
        val varUtilization = info.getValRefUtil(varRef)

        val assignmentExprRef = getExprValueRef(node.fir.rValue, node) ?: return

        info.meetValRefUtil(assignmentExprRef, varUtilization)

        info.resetValRefUtil(varRef)
    }

    // control flow nodes related

    private fun handleReturnNode(node: CFGNode<*>, info: UtilAnalysisPathInfo) {
        val retTarget = node.firstPreviousNode.fir
        if (retTarget !is FirExpression) return

        info.meetValRefUtil(getExprValueRef(retTarget, node), UtilLattice.RT)
    }

    private fun handleIndirectValueSource(node: CFGNode<*>, info: UtilAnalysisPathInfo) {
        val valRef = getValueRefFromNode(node) ?: return

        val util = info.getValRefUtil(valRef)
        if (util == UtilLattice.Top) return   // optimization: ValueRef is Top default

        for (prev in node.previousNodes) {
            val prevRef = getValueRefFromNode(prev) ?: continue
            info.meetValRefUtil(prevRef, util)
        }
    }

    // valueref helpers

    private fun getValueRefFromNode(node: CFGNode<*>) : ValueRef? {
        val fir = node.fir

        if (fir is FirExpression && fir.resolvedType.hasMustConsumeAnnotation(context.session)) {
            return getExprValueRef(fir, node)
        } else if (fir is FirWhenBranch && fir.result.resolvedType.hasMustConsumeAnnotation(context.session)) {
            return getExprValueRef(fir.result, node)
        }

        return null
    }

    private fun getExprValueRef(expr: FirExpression?, caller: CFGNode<*>): ValueRef? {
        if (expr == null) return null

        if (expr !is FirQualifiedAccessExpression) {
            return ValueRef.Expr(expr)
        }

        if (expr.calleeReference is FirThisReference) {
            return ValueRef.ThisRef
        }

        val symbol = expr.calleeReference.symbol ?: return null

        val varRef = getVarValueRef(symbol, caller)

        if (varRef != null) return varRef

        return ValueRef.Expr(expr)
    }

    private fun getVarValueRef(symbol: FirBasedSymbol<*>, caller: CFGNode<*>): ValueRef? {
        val records = funcData.variableRecords[symbol] ?: return null

        if (records.owner != caller.owner) {
            return ValueRef.FreeVar(symbol)
        }

        if (records.paramIndex >= 0) {
            return ValueRef.Params(symbol, records.paramIndex)
        }

        return ValueRef.LocalVar(symbol)
    }

    // path info helpers
    private fun propagatePathInfo(node: CFGNode<*>) : UtilAnalysisPathInfo {
        if (node is FunctionExitNode)
            return UtilAnalysisPathInfo()

        val pathInfos = node.followingNodes.asSequence()
            .filterNot { it.isInvalidNext(node) || it is FunctionEnterNode }
            .mapNotNull { data.pathInfos[it] }
            .toList()

        val info = pathInfos.mergeAll(true) ?: UtilAnalysisPathInfo()

        data.pathInfos[node] = info

        return info
    }

    private fun log(message: Any?) {
        if (logging) println(message)
    }
}


// Utilization analysis

class UtilAnalysisData {
    val pathInfos: MutableMap<CFGNode<*>, UtilAnalysisPathInfo> = mutableMapOf()
    val lambdaFuncInfos: MutableMap<FirAnonymousFunction, FunctionInfo> = mutableMapOf()
}

class UtilAnalysisPathInfo private constructor(
    private val callSiteUtilization: DefaultMapLat<FirFunctionCall, UtilLattice>,
    private val localRefUtilization: DefaultMapLat<ValueRef.LocalRef, UtilLattice>,
    private val nonLocalUtilization: DefaultMapLat<ValueRef.NonLocalRef, UtilLattice>
) : PathInfo<UtilAnalysisPathInfo>
{
    constructor() : this(
        DefaultMapLat(UtilLattice.Bot),
        DefaultMapLat(UtilLattice.Top),
        DefaultMapLat(UtilLattice.Top)
    )

    override fun copy(): UtilAnalysisPathInfo {
        return UtilAnalysisPathInfo(
            callSiteUtilization.copy(),
            localRefUtilization.copy(),
            nonLocalUtilization.copy()
        )
    }

    override fun merge(other: UtilAnalysisPathInfo): UtilAnalysisPathInfo {
        return UtilAnalysisPathInfo(
            callSiteUtilization.merge(other.callSiteUtilization),
            localRefUtilization.merge(other.localRefUtilization),
            nonLocalUtilization.merge(other.nonLocalUtilization)
        )
    }

    fun setCallSiteUtil(call: FirFunctionCall, util: UtilLattice) {
        callSiteUtilization[call] = util
    }

    fun getCallSiteUtil(call: FirFunctionCall) = callSiteUtilization.getWithDefault(call)

    fun callSiteUtils() = callSiteUtilization.asIterable()

    fun meetValRefUtil(valRef: ValueRef?, util: UtilLattice) {
        if (valRef == null) return

        if (valRef is ValueRef.LocalRef) {
            localRefUtilization.meetVal(valRef, util)
        } else {
            nonLocalUtilization.meetVal(valRef as ValueRef.NonLocalRef, util)
        }
    }

    fun resetValRefUtil(valRef: ValueRef?) {
        if (valRef == null) return

        if (valRef is ValueRef.LocalRef) {
            localRefUtilization.remove(valRef)
        } else {
            nonLocalUtilization.remove(valRef as ValueRef.NonLocalRef)
        }
    }

    fun getValRefUtil(valRef: ValueRef) : UtilLattice {
        return if (valRef is ValueRef.LocalRef) {
            localRefUtilization.getWithDefault(valRef)
        } else {
            nonLocalUtilization.getWithDefault(valRef as ValueRef.NonLocalRef)
        }
    }

    fun localUtils() = localRefUtilization.asIterable()

    fun nonLocalUtils() = nonLocalUtilization.asIterable()
}

sealed interface ValueRef {
    sealed interface LocalRef : ValueRef
    sealed interface NonLocalRef : ValueRef

    data class Expr(val fir: FirExpression) : LocalRef
    data class LocalVar(val symbol: FirBasedSymbol<*>): LocalRef

    data object ThisRef : NonLocalRef
    data class Params(val symbol: FirBasedSymbol<*>, val index: Int): NonLocalRef
    data class FreeVar(val symbol: FirBasedSymbol<*>): NonLocalRef
}

sealed class UtilLattice(private val value: Int): Lattice<UtilLattice>  {
    data object Top: UtilLattice(3)
    data object RT: UtilLattice(2)
    data object UT: UtilLattice(1)
    data object Bot: UtilLattice(0)

    fun leq(other: UtilLattice) = value <= other.value

    fun geq(other: UtilLattice) = value >= other.value

    override fun join(other: UtilLattice) = if (this.geq(other)) this else other

    override fun meet(other: UtilLattice) = if (this.geq(other)) other else this
}
