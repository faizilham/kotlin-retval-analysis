package com.faizilham.kotlin.retval.fir.checkers.analysis

import com.faizilham.kotlin.retval.fir.checkers.commons.*
import org.jetbrains.kotlin.diagnostics.DiagnosticContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.packageFqName
import org.jetbrains.kotlin.fir.references.*
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.utils.SmartSet

class UtilizationAnalysis(
    private val context: CheckerContext,
    private val funcData: FuncAnalysisData,
    private val logging: Boolean = false
)
{
    private val data = UtilAnalysisData()
    private val warnings: MutableList<AnalysisWarning> = mutableListOf()

    fun analyzeGraph(graph: ControlFlowGraph) {
        for (node in graph.nodes) {
            analyzeNode(node)
            (node as? CFGNodeWithSubgraphs<*>)?.subGraphs?.forEach { analyzeGraph(it) }
        }
    }

    fun report(reporter: DiagnosticReporter) {
        for (warning in warnings) {
            warning.report(reporter, context)
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
            if (!util.leq(UtilLattice.UT)) {
                warnings.add(UnutilizedValueWarning(call.fir))
            }
        }

        // Func Info
        val currentFunction = node.fir

        if (currentFunction is FirAnonymousFunction) {
            data.lambdaSignatures[currentFunction] = buildLambdaSignature(currentFunction, info)
        }
    }

    private fun buildLambdaSignature(func: FirFunction, info: UtilAnalysisPathInfo): Signature {
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

        // TODO: param signature? parametric inference?

        val isExtension = func.receiverParameter != null

        return Signature (
            isClassMemberOrExtension = isExtension,
            paramSignature = mapOf(),
            paramEffect,
            receiverEffect,
            fvEffect = FVEffectSign.FVEMap(fvEffect),
        )
    }

    /* Function Call */

    private fun handleFuncCall(node: FunctionCallNode, info: UtilAnalysisPathInfo) {
        if (logging && node.fir.calleeReference.name.toString() in setOf("let1", "let2", "let3")) {

            run {
                val fnSymbol = node.fir.calleeReference.toResolvedFunctionSymbol() ?: return@run
                val thisAnn = fnSymbol.resolvedReceiverTypeRef?.getAnnotationByClassId(Commons.Annotations.Util, context.session)



                log("${node.fir.calleeReference.name} ${fnSymbol.parseUtilAnnotations(context.session)}")
                log("param ${fnSymbol.valueParameterSymbols.first().resolvedReturnType.parseUtilAnnotations(context.session)}")

            }
        }

        val signature = resolveSignature(node) ?: return

        if (signature.hasEffect()) {
            val sigInstance = instantiateSignature(node, signature)

            if (sigInstance?.isParametric() == false) {
                consumeReceiver(node, info, sigInstance)
                consumeParameters(node, info, sigInstance)
                consumeFreeVariables(node, info, sigInstance)
            } else {
                warnings.add(MismatchUtilEffectWarning(node.fir))
            }
        }

        if (isUtilizableType(node.fir.resolvedType)) {
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
        val fvEffect = signature.fvEffect as? FVEffectSign.FVEMap ?: return

        for ((freeVar, effect) in fvEffect.map) {
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
            else -> return
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
    private fun resolveSignature(node: FunctionCallNode) : Signature? {
        if (node.fir.isInvoke()) return resolveInvokeSignature(node)
        return getSignature(node.fir)
    }

    private fun resolveInvokeSignature(node: FunctionCallNode) : Signature? {
        val originalRef = (node.fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference ?: return null
        val originalSymbol = originalRef.toResolvedCallableSymbol() ?: return null

        var signature = resolveSymbolSignature(node, originalSymbol) ?: return null

        // NOTE: invoking extension function causes the context object to be regarded as first argument
        //       the dispatchReceiver is no longer the context object, but the function reference
        if (signature.isClassMemberOrExtension) {
            signature = signature.convertReceiverToParameter()
        }

        return signature
    }

    private fun resolveSymbolSignature(node: CFGNode<*>, symbol: FirBasedSymbol<*>): Signature? {
        val funcRef = funcData.pathInfos[node]?.getVarValue(symbol) ?: return null

        return when (funcRef) {
            is FuncRefValue.LambdaRef -> data.lambdaSignatures[funcRef.lambda]
            is FuncRefValue.CallableRef -> getSignature(funcRef.ref)
            else -> null
        }
    }

    private fun getSignature(fir: FirQualifiedAccessExpression) : Signature? {
        val funcSymbol = fir.calleeReference.toResolvedFunctionSymbol() ?: return null
        return getSignature(funcSymbol)
    }

    private fun getSignature(funcSymbol: FirFunctionSymbol<*>): Signature? {
        val signature = data.cachedSignature[funcSymbol]
        if (signature != null) return signature

        val newSignature = buildSignature(context.session, funcSymbol) ?: return null
        data.cachedSignature[funcSymbol] = newSignature

        return newSignature
    }

    private fun instantiateSignature(node: FunctionCallNode, signature: Signature) : Signature? {
        if (!signature.isParametric()) return signature

        val argSignatures = node.fir.arguments.map {
            if (!it.resolvedType.isSomeFunctionType(context.session)) null
            else getArgumentSignature(node, it)
        }

        try {
            return signature.instantiateWith(argSignatures)
        } catch (e: SignatureInstanceException) {
            return null
        }
    }

    private fun getArgumentSignature(node: CFGNode<*>, argument: FirExpression): Signature? {
        val symbol = argument.toResolvedCallableSymbol(context.session)

        if (symbol is FirFunctionSymbol<*>) {
            return getSignature(symbol)
        } else if (symbol != null) {
            return resolveSymbolSignature(node, symbol)
        }

        val lambda = argument as? FirAnonymousFunctionExpression ?: return null

        return data.lambdaSignatures[lambda.anonymousFunction]
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

        if (record.owner != node.owner) {
            warnings.add(InvalidatedFreeVarWarning(node.fir))
            return
        }

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
    val cachedSignature: MutableMap<FirFunctionSymbol<*>, Signature> = mutableMapOf()
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

// UEffect parsing
data class ParsedEffect(
    val receiverEffect: UtilEffect,
    val paramEffect: Map<Int, UtilEffect>,
    val fvEffect: FVEffectSign
)

private fun buildSignature(session: FirSession, funcSymbol: FirFunctionSymbol<*>) : Signature? {
    val anno = funcSymbol.getAnnotationByClassId(Commons.Annotations.UEffect, session)

    val effects =
        if (anno != null) parseUEffectAnnotation(anno)
        else parseConsumeAnnotation(session, funcSymbol)

    if (effects == null) return null

    return Signature(
        isClassMemberOrExtension = funcSymbol.isClassMemberOrExtension(),
        paramSignature = buildParamSignature(session, funcSymbol),

        paramEffect = effects.paramEffect,
        receiverEffect = effects.receiverEffect,
        fvEffect = effects.fvEffect,
    )
}

private fun buildParamSignature(session:FirSession, fnSymbol: FirFunctionSymbol<*>) : Map<Int, Signature> {
    val paramSignature = mutableMapOf<Int, Signature>()

    for ((i, param) in fnSymbol.valueParameterSymbols.withIndex()) {
        val paramType = param.resolvedReturnType
        if (!paramType.isSomeFunctionType(session)) continue

        val anno = param.getAnnotationByClassId(Commons.Annotations.UEffect, session) ?: continue
        val effects = parseUEffectAnnotation(anno) ?: continue

        val signature = Signature(
            isClassMemberOrExtension = paramType.isExtensionFunctionType,
            paramSignature = mapOf(), // TODO: >2 order param signature?

            paramEffect = effects.paramEffect,
            receiverEffect = effects.receiverEffect,
            fvEffect = effects.fvEffect,
        )

        paramSignature[i] = signature
    }

    return paramSignature
}

private fun parseConsumeAnnotation(session: FirSession, funcSymbol: FirFunctionSymbol<*>) : ParsedEffect {
    return ParsedEffect(
        paramEffect = funcSymbol.getParameterEffects(),
        receiverEffect = if (funcSymbol.hasConsumeAnnotation(session)) UtilEffect.U else UtilEffect.N ,
        fvEffect = FVEffectSign.FVEMap(),
    )
}

private fun parseUEffectAnnotation(anno: FirAnnotation) : ParsedEffect? {
    val effects = (anno.argumentMapping.mapping.values.first() as? FirArrayLiteral) ?: return null

    var receiverEffect : UtilEffect = UtilEffect.N
    val paramEffect = mutableMapOf<Int, UtilEffect>()
    var fvEffect: FVEffectSign = FVEffectSign.FVEMap()

    for (ueCall in effects.argumentList.arguments) {
        val (target, effect) = parseUECall(ueCall) ?: continue

        if (target >= 0) {
            paramEffect[target] = effect
        } else if (target == -1) {
            receiverEffect = effect
        } else if (target == -2 && effect is UtilEffect.Var) {
            fvEffect = FVEffectSign.FVEVar(effect.name)
        }

        // TODO: error for others?
    }

    return ParsedEffect(receiverEffect, paramEffect, fvEffect)
}

private fun parseUECall(fir: FirExpression?) : Pair<Int, UtilEffect>? {
    val ueCall = (fir as? FirFunctionCall) ?: return null
    val args = ueCall.argumentList.arguments
    if (args.size != 2) return null

    val targetFir = args[0]
    val target = if (targetFir is FirLiteralExpression<*>) {
        (targetFir.value as? Long)?.toInt() ?: (targetFir.value as? Int) ?: return null
    } else if (
        targetFir is FirPropertyAccessExpression &&
        targetFir.calleeReference.symbol?.packageFqName() == Commons.Annotations.PACKAGE_FQN
    ) {
        Commons.Annotations.UETarget[targetFir.calleeReference.resolved?.name?.toString()] ?: return null
    } else {
        return null
    }

    if (target < -2) return null

    val effectStr = (args[1] as? FirLiteralExpression<*>)?.value as? String ?: return null

    val effect = when(effectStr) {
        "U" -> UtilEffect.U
        "N" -> UtilEffect.N
        "I" -> UtilEffect.I
        ""  -> UtilEffect.N
        else -> UtilEffect.Var(effectStr)
    }

    return Pair(target, effect)
}

data class ParsedUtilAnnotations(
    val context: UtilAnnotation?,
    val params: Map<Int, UtilAnnotation>,
    val retVal: UtilAnnotation
)

private fun FirFunctionSymbol<*>.parseUtilAnnotations(session: FirSession) : ParsedUtilAnnotations {
    val ctx = resolvedReceiverTypeRef?.annotations?.getUtilAnnotation(session)

    val params = mutableMapOf<Int, UtilAnnotation>()

    for ((i, valParam) in valueParameterSymbols.withIndex()) {
        val utilAnn = valParam.resolvedReturnTypeRef.annotations.getUtilAnnotation(session)

        if (utilAnn is UtilAnnotation.Val && utilAnn.value == UtilLattice.Top) {
            continue
        }

        params[i] = utilAnn
    }

    val retVal = resolvedReturnTypeRef.annotations.getUtilAnnotation(session)

    return ParsedUtilAnnotations(ctx, params, retVal)
}

private fun ConeKotlinType.parseUtilAnnotations(session: FirSession) : ParsedUtilAnnotations? {
    if (!isSomeFunctionType(session)) return null

    val paramSize: Int
    val paramOffset: Int
    val ctx : UtilAnnotation?

    if (isExtensionFunctionType) {
        paramSize = typeArguments.size - 2
        paramOffset = 1
        ctx = typeArguments.first().type?.getUtilAnnotation(session)
    } else {
        paramSize = typeArguments.size - 1
        paramOffset = 0
        ctx = null
    }

    val params = mutableMapOf<Int, UtilAnnotation>()

    for (i in 0..<paramSize) {
        val util = typeArguments.getOrNull(i + paramOffset)?.type?.getUtilAnnotation(session)

        if (util == null || (util is UtilAnnotation.Val && util.value == UtilLattice.Top)) {
            continue
        }

        params[i] = util
    }

    val retVal = typeArguments.last().type?.getUtilAnnotation(session) ?: UtilAnnotation.Val(UtilLattice.Top)

    return ParsedUtilAnnotations(ctx, params, retVal)
}

private fun isUtilizableType(session: FirSession, type: ConeKotlinType) : Boolean {
    return type.hasMustConsumeAnnotation(session)
}

private fun ConeKotlinType.getUtilAnnotation(session: FirSession): UtilAnnotation {
    return this.customAnnotations.getUtilAnnotation(session)
}

private fun List<FirAnnotation>.getUtilAnnotation(session: FirSession) : UtilAnnotation {
    var firstArg : String? = null
    for (annotation in this){
        if (annotation.toAnnotationClassId(session) != Commons.Annotations.Util) continue
        val args = (annotation as? FirAnnotationCall)?.arguments
        firstArg = (args?.firstOrNull() as? FirLiteralExpression<*>)?.value as? String
        break
    }

    when(firstArg) {
        null -> return UtilAnnotation.Val(UtilLattice.Top)
        "" -> return UtilAnnotation.Val(UtilLattice.Top)
        "0" -> return UtilAnnotation.Val(UtilLattice.NU)
        "1" -> return UtilAnnotation.Val(UtilLattice.UT)
        "0|1" -> return UtilAnnotation.Val(UtilLattice.Top)
        else -> return UtilAnnotation.Var(firstArg)
    }
}

// Warnings
private abstract class AnalysisWarning(val fir: FirElement) {
    abstract fun report(reporter: DiagnosticReporter, context: DiagnosticContext)
}

private class UnutilizedValueWarning(fir: FirElement): AnalysisWarning(fir){
    override fun report(reporter: DiagnosticReporter, context: DiagnosticContext) {
        reporter.reportOn(fir.source, Commons.Warnings.UNCONSUMED_VALUE, context)
    }
}

private class MismatchUtilEffectWarning(fir: FirElement): AnalysisWarning(fir){
    override fun report(reporter: DiagnosticReporter, context: DiagnosticContext) {
        reporter.reportOn(fir.source, Commons.Warnings.MISMATCH_UTIL_EFFECT, context)
    }
}

private class InvalidatedFreeVarWarning(fir: FirElement): AnalysisWarning(fir) {
    override fun report(reporter: DiagnosticReporter, context: DiagnosticContext) {
        reporter.reportOn(fir.source, Commons.Warnings.INVALIDATED_FREE_VAR, context)
    }
}