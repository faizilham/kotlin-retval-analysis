package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.*
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isExtensionFunctionType
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

        val utilAnalyzer = UtilAnalysis(context, funcAnalyzer.data, logging)

        utilAnalyzer.analyzeGraph(graph)
    }

    class FuncAnalysis(private val context: CheckerContext, private val logging: Boolean = false) {
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
//                is FunctionExitNode -> {
//                    log("Exit ${node.owner.name}")
//                    for ((sym, ref) in info.variableValue) {
//                        log("$sym -> $ref")
//                    }
//                }
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

    class UtilAnalysis(
        private val context: CheckerContext,
        private val funcData: FuncAnalysisData,
        private val logging: Boolean = false
    )
    {
        val data = UtilAnalysisData()
        val warnings: MutableList<FirElement> = mutableListOf()

        fun analyzeGraph(graph: ControlFlowGraph) {
            graph.subGraphs.forEach { analyzeGraph(it) }

            for (node in graph.nodes.reversed()) {
                analyzeNode(node)
            }
        }

        private fun analyzeNode(node: CFGNode<*>) {
            val info = propagatePathInfo(node)

            when {
                node is FunctionEnterNode -> handleFunctionStart(node, info)
                node is FunctionCallNode -> handleFuncCallNode(node, info)
                node is QualifiedAccessNode -> handleQualifiedAccess(node, info)
//                node is VariableDeclarationNode -> handleVariableDeclaration(node, info)
//                node is VariableAssignmentNode -> handleVarAssign(node, info)

                node.isReturnNode() -> handleReturnNode(node, info)
//                node.isIndirectValueSource() -> {}
            }
        }

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

        private fun handleFuncCallNode(node: FunctionCallNode, info: UtilAnalysisPathInfo) {
            val funcInfo = getFunctionInfo(node) ?: return

            consumeReceiver(node, info, funcInfo)
            consumeParameters(node, info, funcInfo)
            consumeFreeVariables(node, info, funcInfo)

            if (funcInfo.returningConsumable && !funcInfo.returnIsConsumed) {
                info.setCallSiteUtil(node.fir, info.getValRefUtil(node.fir.toValueRef()))
            }
        }

        private fun consumeReceiver(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
            if (!funcInfo.consumingThis) return
            val receiver = node.fir.dispatchReceiver ?: node.fir.extensionReceiver ?: return

            info.setValRefUtil(receiver.toValueRef(), UtilLattice.UT)
        }

        private fun consumeParameters(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
            for (consumedId in funcInfo.consumedParameters) {
                val arg = node.fir.argumentList.arguments.getOrNull(consumedId) ?: continue
                info.setValRefUtil(arg.toValueRef(), UtilLattice.UT)
            }
        }

        private fun consumeFreeVariables(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
            for (freeVar in funcInfo.consumedFreeVariables) {
                val valRef = getVarValueRef(freeVar, node) ?: continue
                info.setValRefUtil(valRef, UtilLattice.UT)
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

        private fun handleQualifiedAccess(node: QualifiedAccessNode, info: UtilAnalysisPathInfo) {
            val varType = node.fir.resolvedType
            if (!varType.hasMustConsumeAnnotation(context.session)) return

            val utilization = info.getValRefUtil(node.fir.toValueRef())

            val valRef =
                if (node.fir.calleeReference is FirThisReference) {
                    ValueRef.ThisRef
                } else {
                    val symbol = node.fir.calleeReference.symbol ?: return
                    getVarValueRef(symbol, node) ?: return
                }

            info.setValRefUtil(valRef, UtilLattice.UT)
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

        private fun handleReturnNode(node: CFGNode<*>, info: UtilAnalysisPathInfo) {
            val retTarget = node.firstPreviousNode.fir

            if (retTarget !is FirExpression) return

            info.setValRefUtil(retTarget.toValueRef(), UtilLattice.RT)
        }


        //
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
    }
}

// Utilization analysis

class UtilAnalysisData {
    val pathInfos: MutableMap<CFGNode<*>, UtilAnalysisPathInfo> = mutableMapOf()
    val lambdaFuncInfos: MutableMap<FirAnonymousFunction, FunctionInfo> = mutableMapOf()
}

class UtilAnalysisPathInfo(
    private val callSiteUtilization: DefaultMapLat<FirFunctionCall, UtilLattice> = DefaultMapLat(UtilLattice.Bot),
    private val localRefUtilization: DefaultMapLat<ValueRef.LocalRef, UtilLattice> = DefaultMapLat(UtilLattice.Top),
    private val nonLocalUtilization: DefaultMapLat<ValueRef.NonLocalRef, UtilLattice> = DefaultMapLat(UtilLattice.Top)
) : IPathInfo<UtilAnalysisPathInfo> {
    override fun copy(): UtilAnalysisPathInfo {
        return UtilAnalysisPathInfo(
            callSiteUtilization.copy(),
            localRefUtilization.copy()
        )
    }

    override fun merge(other: UtilAnalysisPathInfo): UtilAnalysisPathInfo {
        return UtilAnalysisPathInfo(
            callSiteUtilization.join(other.callSiteUtilization),
            localRefUtilization.join(other.localRefUtilization)
        )
    }

    fun setCallSiteUtil(call: FirFunctionCall, util: UtilLattice) {
        callSiteUtilization[call] = util
    }

    fun getCallSiteUtil(call: FirFunctionCall) = callSiteUtilization.getWithDefault(call)

    fun callSiteUtils() = callSiteUtilization.asIterable()

    fun setValRefUtil(valRef: ValueRef, util: UtilLattice) {
        if (valRef is ValueRef.LocalRef) {
            localRefUtilization[valRef] = util
        } else {
            nonLocalUtilization[valRef as ValueRef.NonLocalRef] = util
        }
    }

    fun getValRefUtil(valRef: ValueRef) : UtilLattice {
        return if (valRef is ValueRef.LocalRef) {
            localRefUtilization.getWithDefault(valRef)
        } else {
            nonLocalUtilization.getWithDefault(valRef as ValueRef.NonLocalRef)
        }
    }

    fun nonLocalUtils() = nonLocalUtilization.asIterable()
}

sealed interface ValueRef {
    sealed interface LocalRef : ValueRef
    sealed interface NonLocalRef : ValueRef

//    class Node(val node: CFGNode<*>) : LocalRef {
//        override fun equals(other: Any?): Boolean {
//            return other is Node && node == other.node
//        }
//
//        override fun hashCode(): Int {
//            return node.hashCode()
//        }
//    }

    class Expr(val fir: FirExpression) : LocalRef {
        override fun equals(other: Any?): Boolean {
            return other is Expr && fir == other.fir
        }

        override fun hashCode(): Int {
            return fir.hashCode()
        }
    }

    class LocalVar(val symbol: FirBasedSymbol<*>): LocalRef {
        override fun equals(other: Any?): Boolean {
            return other is LocalVar && symbol == other.symbol
        }

        override fun hashCode(): Int {
            return symbol.hashCode()
        }
    }

    data object ThisRef : NonLocalRef

    class Params(val symbol: FirBasedSymbol<*>, val index: Int): NonLocalRef {
        override fun equals(other: Any?): Boolean {
            return other is LocalVar && symbol == other.symbol
        }

        override fun hashCode(): Int {
            return symbol.hashCode()
        }
    }

    class FreeVar(val symbol: FirBasedSymbol<*>): NonLocalRef {
        override fun equals(other: Any?): Boolean {
            return other is FreeVar && symbol == other.symbol
        }

        override fun hashCode(): Int {
            return symbol.hashCode()
        }
    }
}

//fun CFGNode<*>.toValueRef() = ValueRef.Node(this)
fun FirExpression.toValueRef() = ValueRef.Expr(this)

sealed class UtilLattice(private val value: Int): Lattice<UtilLattice>  {
    data object Top: UtilLattice(3)
    data object RT: UtilLattice(2)
    data object UT: UtilLattice(1)
    data object Bot: UtilLattice(0)

    fun leq(other: UtilLattice) = value <= other.value

    fun geq(other: UtilLattice) = value >= other.value

    override fun join(other: UtilLattice) = if (this.geq(other)) this else other
}

// Function alias analysis

class FuncAnalysisData {
    val variableRecords : MutableMap<FirBasedSymbol<*>, VariableRecord> = mutableMapOf()
    val pathInfos : MutableMap<CFGNode<*>, FuncAnalysisPathInfo> = mutableMapOf()
}

data class VariableRecord(
    val owner: ControlFlowGraph,
    val isVal: Boolean,
    val paramIndex: Int = -1 // paramIndex < 0 means not a param
)

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

// helpers

private fun CFGNode<*>.isReturnNode(): Boolean {
    val nextIsExit = followingNodes.firstOrNull() is FunctionExitNode ||
            followingNodes.lastOrNull() is FunctionExitNode

    return  (this is JumpNode && nextIsExit) ||
            (this is BlockExitNode && nextIsExit && owner.isLambda())
}

private fun CFGNode<*>.isIndirectValueSource(): Boolean {
    return  (this is JumpNode) ||
            (this is BlockExitNode) ||
            (this is WhenExitNode) ||
            (this is WhenBranchResultExitNode) ||
            (this is ElvisLhsExitNode) ||
            (this is ElvisLhsIsNotNullNode) ||
            (this is ElvisExitNode) ||
            (this is BinaryAndExitLeftOperandNode) ||
            (this is BinaryAndExitNode) ||
            (this is BinaryOrExitLeftOperandNode) ||
            (this is BinaryOrExitNode)
}


private fun FirQualifiedAccessExpression.getConsumedParameters() : Set<Int> {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return setOf()

    return funcSymbol.valueParameterSymbols.asSequence()
        .withIndex()
        .filter { (_, it) ->
            it.containsAnnotation(Utils.Constants.ConsumeClassId)
        }
        .map { (i, _) -> i}
        .toSet()
}

private fun FirQualifiedAccessExpression.isClassMemberOrExtension() : Boolean {
    return  resolvedType.isExtensionFunctionType ||
            (calleeReference.toResolvedFunctionSymbol()?.containingClassLookupTag() != null)
}