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
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.utils.SmartSet

class UtilizationAnalysis(
    private val context: CheckerContext,
    private val funcData: FuncAnalysisData,
    private val logging: Boolean = false
)
{
    private val data = UtilAnalysisData()
    private val warnings: MutableList<FirElement> = mutableListOf()

    fun analyzeGraph(graph: ControlFlowGraph) {
        for (node in graph.nodes) {
            analyzeNode(node)
            (node as? CFGNodeWithSubgraphs<*>)?.subGraphs?.forEach { analyzeGraph(it) }
        }
    }

    fun report(reporter: DiagnosticReporter) {
        for (warnedFir in warnings) {
            reporter.reportOn(warnedFir.source, Commons.Warnings.UNCONSUMED_VALUE, context)
        }
    }

    private fun analyzeNode(node: CFGNode<*>) {
        val info = propagatePathInfo(node)

        when {
            node is FunctionExitNode -> handleFuncExit(node, info)
            node is FunctionCallNode -> handleFuncCall(node, info)
            node is VariableDeclarationNode -> handleVarDeclaration(node, info)
            node is VariableAssignmentNode -> handleVarAssignment(node, info)
            node.isReturnNode() -> handleReturnNode(node, info)
            node.isIndirectValueSource() -> handleIndirectValueSource(node, info)
        }
    }

    /* Function Exit */
    private fun handleFuncExit(node: FunctionExitNode, info: UtilAnalysisPathInfo) {
        // Warnings
        for ((call, util) in info.callSiteUtils) {
            if (!util.leq(UtilLattice.RT)) {
                warnings.add(call.fir)
            }
        }

        // Func Info
        val currentFunction = node.fir

        if (currentFunction is FirAnonymousFunction) {
            data.lambdaSignatures[currentFunction] = buildFuncInfo(currentFunction, info)
        }
    }

    private fun buildFuncInfo(func: FirFunction, info: UtilAnalysisPathInfo): Signature {
        var receiverEffect : UtilEffect = UtilEffect.N
        val paramEffect = mutableMapOf<Int, UtilEffect>()
        val fvEffect = mutableMapOf<FirBasedSymbol<*>, UtilEffect>()

        for ((nonlocal, utilization) in info.nonLocalUtils) {
            if (!utilization.leq(UtilLattice.UT)) continue

            when (nonlocal) {
                is ValueSource.ThisRef -> receiverEffect = UtilEffect.U
                is ValueSource.Params -> paramEffect[nonlocal.index] = UtilEffect.U
                is ValueSource.FreeVar -> fvEffect[nonlocal.symbol] = UtilEffect.U
            }
        }

        val returningUtilizable = isUtilizableType(func.returnTypeRef.coneType)
        val isExtension = func.receiverParameter != null

        return Signature (
            isClassMemberOrExtension = isExtension,
            returningUtilizable,

            receiverSignature = null, // TODO: param signature
            paramSignature = mapOf(),
            paramEffect,
            receiverEffect,
            fvEffect,
        )
    }

    /* Function Call */

    private fun handleFuncCall(node: FunctionCallNode, info: UtilAnalysisPathInfo) {
        val signature = getFunctionSignature(node) ?: return
        if (signature.hasEffect()) {
            consumeReceiver(node, info, signature)
            consumeParameters(node, info, signature)
            consumeFreeVariables(node, info, signature)
        }

        if (signature.returningUtilizable) {
            val source = ValueSource.CallSite(node.fir)
            info.callSiteUtils[source] = UtilLattice.Top
        }
    }

    private fun consumeReceiver(node: FunctionCallNode, info: UtilAnalysisPathInfo, signature: Signature) {
        if (signature.receiverEffect == UtilEffect.N) return

        val receiver = node.fir.dispatchReceiver ?: node.fir.extensionReceiver ?: return
        val resolvedReceiver = resolve(receiver, node, info, true)

        applyEffect(resolvedReceiver, signature.receiverEffect, info)
    }

    private fun consumeParameters(node: FunctionCallNode, info: UtilAnalysisPathInfo, signature: Signature) {
        for ((paramId, effect) in signature.paramEffect) {
            val arg = node.fir.argumentList.arguments.getOrNull(paramId) ?: continue
            val argSources = resolve(arg, node, info, true)

            applyEffect(argSources, effect, info)
        }
    }

    private fun consumeFreeVariables(node: FunctionCallNode, info: UtilAnalysisPathInfo, signature: Signature) {
        for ((freeVar, effect) in signature.fvEffect) {
            val sources = resolveVar(freeVar, node, info, true)
            applyEffect(sources, effect, info)
        }
    }

    private fun applyEffect(valueSources: SetLat<ValueSource>, effect: UtilEffect, info: UtilAnalysisPathInfo) {
        if (effect == UtilEffect.N) return

        val utilization = when(effect) {
            UtilEffect.N -> return
            UtilEffect.U -> UtilLattice.UT
            UtilEffect.I -> UtilLattice.Top
            else -> return // TODO: Error? Var?
        }

        for (source in valueSources) {
            when(source) {
                is ValueSource.NonLocalSource -> info.nonLocalUtils.meetVal(source, utilization)
                is ValueSource.CallSite -> info.callSiteUtils.meetVal(source, utilization)
                is ValueSource.TransientVar -> {}
            }
        }
    }

    // Function Signature Resolving
    private fun getFunctionSignature(node: FunctionCallNode) : Signature? {
        val fir = node.fir
        if (fir.isInvoke()) return resolveInvokeSignature(node)

        return Signature(
            isClassMemberOrExtension = fir.isClassMemberOrExtension(),
            returningUtilizable = isUtilizableType(fir.resolvedType),

            receiverSignature = null, // TODO: param signature
            paramSignature = mapOf(),

            paramEffect = fir.getParameterEffects(),
            receiverEffect = if (fir.hasConsumeAnnotation(context.session)) UtilEffect.U else UtilEffect.N ,
            fvEffect = mapOf(),
        )
    }

    private fun resolveInvokeSignature(node: FunctionCallNode) : Signature? {
        val originalRef = (node.fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference ?: return null
        val originalSymbol = originalRef.toResolvedCallableSymbol() ?: return null

        val funcRef = funcData.pathInfos[node]?.getVarValue(originalSymbol) ?: return null
        var funcInfo = getFuncRefSignature(funcRef) ?: return null

        // NOTE: invoking extension function causes the context object to be regarded as first argument
        //       the dispatchReceiver is no longer the context object, but the function reference
        if (funcInfo.isClassMemberOrExtension) {
            funcInfo = funcInfo.convertReceiverToParameter()
        }

        return funcInfo
    }

    private fun getFuncRefSignature(funcRef: FuncRefValue): Signature? {
        return when (funcRef) {
            is FuncRefValue.LambdaRef -> data.lambdaSignatures[funcRef.lambda]
            is FuncRefValue.CallableRef -> {
                val ref = funcRef.ref
                val returnType = ref.getReturnType() ?: return null

                Signature(
                    isClassMemberOrExtension = ref.isClassMemberOrExtension(),
                    returningUtilizable = isUtilizableType(returnType),

                    receiverSignature = null, // TODO: param signature
                    paramSignature = mapOf(),
                    paramEffect = ref.getParameterEffects(),
                    receiverEffect = if (ref.hasConsumeAnnotation(context.session)) UtilEffect.U else UtilEffect.N ,
                    fvEffect = mapOf(),
                )
            }
            else -> null
        }
    }

    /* Variable aliasing */

    private fun handleVarDeclaration(node: VariableDeclarationNode, info: UtilAnalysisPathInfo) {
        val varType = node.fir.returnTypeRef.coneType
        if (!isUtilizableType(varType)) return

        val varRef = ValueRef.Variable(node.fir.symbol, VariableRecord(node.owner, true))

        info.reachingValues[varRef] = resolve(node.fir.initializer, node, info, false)
    }

    private fun handleVarAssignment(node: VariableAssignmentNode, info: UtilAnalysisPathInfo) {
        val varType = node.fir.lValue.resolvedType
        if (!isUtilizableType(varType)) return

        val symbol = node.fir.calleeReference?.symbol ?: return
        val record = funcData.variableRecords[symbol] ?: return

        val varRef = ValueRef.Variable(symbol, record)

        info.occludedSources.joinVal(varRef, getOccluded(info.reachingValues[varRef]))
        info.reachingValues[varRef] = resolve(node.fir.rValue, node, info, false) // TODO: solve cyclic assignment?
    }

    /* returns and other nodes */

    private fun handleReturnNode(node: CFGNode<*>, info: UtilAnalysisPathInfo) {
        val retTarget = node.firstPreviousNode.fir
        if (retTarget !is FirExpression) return

        val resolvedTargets = resolve(retTarget, node, info, true)

        for (source in resolvedTargets) {
            if (source is ValueSource.CallSite) {
                info.callSiteUtils.meetVal(source, UtilLattice.UT)
            }
        }
    }

    private fun handleIndirectValueSource(node: CFGNode<*>, info: UtilAnalysisPathInfo) {
        val firExpr = when(val fir = node.fir) {
            is FirExpression -> fir
            is FirWhenBranch -> fir.result
            else -> return
        }

        if (!isUtilizableType(firExpr.resolvedType)) return

        val combined = SmartSet.create<ValueSource>()

        for (prev in node.previousNodes) {
            val prevSources = resolveNode(prev, info, false)
            combined.addAll(prevSources)
        }

        info.reachingValues[ValueRef.Expr(firExpr)] = SetLat.from(combined)
    }
    
    // resolving

    private fun resolveNode(node: CFGNode<*>, info: UtilAnalysisPathInfo, deepResolve: Boolean): SetLat<ValueSource> {
        return when (val fir = node.fir) {
            is FirExpression -> resolve(fir, node, info, deepResolve)
            is FirWhenBranch -> resolve(fir.result, node, info, deepResolve)
            else -> SetLat()
        }
    }

    private fun resolve(expr: FirExpression?, caller: CFGNode<*>, info: UtilAnalysisPathInfo, deepResolve: Boolean): SetLat<ValueSource> {
        if (expr == null || !isUtilizableType(expr.resolvedType)) {
            return emptySource
        }

        if (expr is FirFunctionCall) return SetLat(ValueSource.CallSite(expr))

        if (expr !is FirQualifiedAccessExpression) {
            return resolveRef(ValueRef.Expr(expr), caller, info, deepResolve)
        }

        if (expr.calleeReference is FirThisReference) return SetLat(ValueSource.ThisRef)

        return resolveVar(expr.calleeReference.symbol, caller, info, deepResolve)
    }

    private fun resolveVar(symbol: FirBasedSymbol<*>?, caller: CFGNode<*>, info: UtilAnalysisPathInfo, deepResolve: Boolean): SetLat<ValueSource> {
        if (symbol == null) return emptySource

        val records = funcData.variableRecords[symbol] ?: return emptySource
        return resolveRef(ValueRef.Variable(symbol, records), caller, info, deepResolve)
    }

    private fun resolveRef(valueRef: ValueRef, caller: CFGNode<*>, info: UtilAnalysisPathInfo, deepResolve: Boolean) : SetLat<ValueSource> {
        if(!deepResolve && valueRef is ValueRef.Variable) {
            return SetLat(ValueSource.TransientVar(valueRef.symbol, valueRef.record, caller))
        }

        val reaching = info.reachingValues.getWithDefault(valueRef)

        if (reaching.isEmpty()) {
            if (valueRef !is ValueRef.Variable)
                return emptySource
            if (valueRef.record.owner != caller.owner)
                return SetLat(ValueSource.FreeVar(valueRef.symbol))
            if (valueRef.record.paramIndex >= 0)
                return SetLat(ValueSource.Params(valueRef.symbol, valueRef.record.paramIndex))

            return emptySource
        }

        if (reaching.size > 1) {
            val occluded = info.occludedSources.getWithDefault(valueRef)
            return SetLat.from(reaching.filter { it is ValueSource.CallSite && it !in occluded })
        }

        val source = reaching.first()
        if (!deepResolve || source !is ValueSource.TransientVar) return reaching

        if (valueRef is ValueRef.Variable && valueRef.symbol == source.symbol && caller == source.accessAt) {
            // NOTE: should not be possible, but just in case
            return emptySource
        }

        val sourceInfo = data.pathInfos[source.accessAt] ?: return emptySource
        return resolveRef(ValueRef.Variable(source.symbol, source.record), source.accessAt, sourceInfo, true)
    }

    private fun getOccluded(valueSources: SetLat<ValueSource>?): SetLat<ValueSource.CallSite> {
        if (valueSources == null) return emptyCallsites

        val occluded = SmartSet.create<ValueSource.CallSite>()

        for (source in valueSources) {
            if (source is ValueSource.CallSite) {
                occluded.add(source)
            }
        }

        return SetLat.from(occluded)
    }

    private val emptySource = SetLat<ValueSource>()
    private val emptyCallsites = SetLat<ValueSource.CallSite>()

    private fun isUtilizableType(type: ConeKotlinType) : Boolean {
        return type.hasMustConsumeAnnotation(context.session)
    }

    // Path Info

    private fun propagatePathInfo(node: CFGNode<*>) : UtilAnalysisPathInfo {
        if (node is FunctionEnterNode)
            return UtilAnalysisPathInfo()

        val pathInfos = node.previousNodes.asSequence()
            .filterNot { it.isInvalidPrev(node) || it is FunctionExitNode }
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

private class UtilAnalysisData {
    val pathInfos: MutableMap<CFGNode<*>, UtilAnalysisPathInfo> = mutableMapOf()
    val lambdaSignatures: MutableMap<FirAnonymousFunction, Signature> = mutableMapOf()
}

private class UtilAnalysisPathInfo private constructor(
    val reachingValues : DefaultMapLat<ValueRef, SetLat<ValueSource>>,
    val occludedSources: DefaultMapLat<ValueRef, SetLat<ValueSource.CallSite>>,
    val callSiteUtils: DefaultMapLat<ValueSource.CallSite, UtilLattice>,
    val nonLocalUtils: DefaultMapLat<ValueSource.NonLocalSource, UtilLattice>
) : PathInfo<UtilAnalysisPathInfo> {
    constructor() : this(
        DefaultMapLat(SetLat()),
        DefaultMapLat(SetLat()),
        DefaultMapLat(UtilLattice.Bot),
        DefaultMapLat(UtilLattice.Top)
    )

    override fun copy(): UtilAnalysisPathInfo {
        return UtilAnalysisPathInfo(
            reachingValues.copy(),
            occludedSources.copy(),
            callSiteUtils.copy(),
            nonLocalUtils.copy()
        )
    }

    override fun merge(other: UtilAnalysisPathInfo): UtilAnalysisPathInfo {
        return UtilAnalysisPathInfo(
            reachingValues.merge(other.reachingValues),
            occludedSources.merge(other.occludedSources),
            callSiteUtils.merge(other.callSiteUtils),
            nonLocalUtils.merge(other.nonLocalUtils)
        )
    }
}

// Lattices

private sealed interface ValueRef{
    data class Expr(val fir: FirExpression) : ValueRef
    data class Variable(val symbol: FirBasedSymbol<*>, val record: VariableRecord): ValueRef
}

private sealed interface ValueSource {
    data class TransientVar(val symbol: FirBasedSymbol<*>, val record: VariableRecord, val accessAt: CFGNode<*>): ValueSource

    data class CallSite(val fir: FirFunctionCall) : ValueSource

    sealed interface NonLocalSource : ValueSource

    data object ThisRef : NonLocalSource
    data class Params(val symbol: FirBasedSymbol<*>, val index: Int): NonLocalSource
    data class FreeVar(val symbol: FirBasedSymbol<*>): NonLocalSource
}

private sealed class UtilLattice(private val value: Int): Lattice<UtilLattice> {
    data object Top: UtilLattice(3)
    data object RT: UtilLattice(2)
    data object UT: UtilLattice(1)
    data object Bot: UtilLattice(0)

    fun leq(other: UtilLattice) = value <= other.value

    fun geq(other: UtilLattice) = value >= other.value

    override fun join(other: UtilLattice) = if (this.geq(other)) this else other

    override fun meet(other: UtilLattice) = if (this.geq(other)) other else this
}
