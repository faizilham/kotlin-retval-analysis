package com.faizilham.kotlin.retval.fir.checkers.old

import com.faizilham.kotlin.retval.fir.checkers.analysis.FunctionInfo
import com.faizilham.kotlin.retval.fir.checkers.commons.*
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.resolvedType

object UtilizationChecker :  FirControlFlowChecker(MppCheckerKind.Common)  {
    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter, context: CheckerContext) {
        if (graph.kind != ControlFlowGraph.Kind.Function || graph.declaration?.isSynthetic != false) {
            return
        }

        val analyzer = Analyzer(context, logging = true)

        analyzer.log("analyze utilization ${context.containingFile?.name} ${graph.name} ${graph.kind}")

        analyzer.analyzeGraph(graph)

        for (fir in analyzer.getUnutilizedCreations()) {
            reporter.reportOn(fir.source, Utils.Warnings.UNCONSUMED_VALUE, context)
        }
    }

    private class Analyzer(val context: CheckerContext, val logging: Boolean = false) {
        val data = UtilizationData()

        val unutilizedCreationSites : MutableSet<FirElement> = mutableSetOf()

        fun getUnutilizedCreations() : Set<FirElement> = unutilizedCreationSites

        fun analyzeGraph(graph: ControlFlowGraph) {
            val finalPathInfos : MutableList<PathInfo> = mutableListOf()
            for (node in graph.nodes) {
                analyzeNode(node, finalPathInfos)
                (node as? CFGNodeWithSubgraphs<*>)?.subGraphs?.forEach { analyzeGraph(it) }
            }

            val finalInfo = finalPathInfos.mergeInfos()

            for (source in finalInfo.getUnutilizedCreations()) {
                collectUnutilizedValue(source)
            }

            val funcDeclaration = graph.declaration

            if (funcDeclaration is FirAnonymousFunction) {
                addLambdaInformation(funcDeclaration, finalInfo)
            }
        }

        private fun collectUnutilizedValue(src: ValueSource) {
            when(src) {
                is ValueSource.FuncCall -> unutilizedCreationSites.add(src.node.fir)
                is ValueSource.Choices -> {
                    src.sources.forEach { collectUnutilizedValue(it) }
                }
                else -> {} // Only collect creation sites
            }
        }

        private fun addLambdaInformation(anonFunction: FirAnonymousFunction, finalInfo: PathInfo) {
            var consumingThis = false
            val consumedParameters: MutableSet<Int> = mutableSetOf()
            val consumedFreeVariables: MutableSet<FirBasedSymbol<*>> = mutableSetOf()

            for ((nonlocal, utilization) in finalInfo.getNonLocalUtilizations().entries) {
                if (!utilization.equalTo(NonLocalUtilization.Utilized)) continue

                when (nonlocal) {
                    is ValueSource.ThisReference -> consumingThis = true
                    is ValueSource.ValueParameter -> consumedParameters.add(nonlocal.index)
                    is ValueSource.FreeVariable -> consumedFreeVariables.add(nonlocal.symbol)
                }
            }

            val returningConsumable = anonFunction.returnTypeRef.coneType.hasMustConsumeAnnotation(context.session)
            val isExtension = anonFunction.receiverParameter != null

            data.addLambdaInfo(anonFunction,
                FunctionInfo(
                    isLambda = true,
                    isExtension,
                    returningConsumable,
                    returnIsConsumed = false,
                    consumingThis,
                    consumedParameters,
                    consumedFreeVariables
                )
            )
        }

        private fun analyzeNode(node: CFGNode<*>, finalPathInfos: MutableList<PathInfo>) {
            if (node.isDead) return

            val info = propagatePathInfo(node)

            when {
                node is FunctionEnterNode -> handleFunctionStart(node, info)
                node is QualifiedAccessNode -> handleQualifiedAccess(node, info)
                node is FunctionCallNode -> handleFuncCallNode(node, info)
                node is VariableDeclarationNode -> handleVariableDeclaration(node, info)
                node.isReturnNode() -> handleReturnNode(node, info)
                node.isIndirectValueSource() -> propagateValueSource(node, info)

                node is PostponedLambdaExitNode -> {
                    info.resetUtilization() // TODO: check this with function start
//                    println("ple ${node.previousNodes.size} ${node.fir.anonymousFunction.invocationKind}")
                }

                else -> {}
            }

            if (node is FunctionExitNode || node.validNextSize() == 0) {
                finalPathInfos.add(info)
            }
        }

        private fun handleFunctionStart(node: FunctionEnterNode, info: PathInfo) {
            info.resetUtilization() // NOTE: each function should only care about its internal unutilized TODO: other way?

            node.fir.valueParameters.forEachIndexed { index, valParam ->
                val paramType = valParam.returnTypeRef.coneType

                if (paramType.hasMustConsumeAnnotation(context.session)) {
                    val valSrc = ValueSource.ValueParameter(valParam, index)

                    info.setVarValue(valParam.symbol, valSrc)
                    data.setVarOwner(valParam.symbol, node.owner)
                }
            }
        }

        private fun handleFuncCallNode(node: FunctionCallNode, info: PathInfo) {
            val funcInfo = getFunctionInfo(node.fir, info) ?: return

            consumeReceiver(node, info, funcInfo)
            consumeParameters(node, info, funcInfo)
            consumeFreeVariables(node, info, funcInfo)

            if (funcInfo.returningConsumable) {
                val valueSource = ValueSource.FuncCall(node)
                data.addValueSource(node, valueSource)

                if (!funcInfo.returnIsConsumed) {
                    info.addUnutilizedCreation(valueSource)
                }
            }
        }

        private fun getFunctionInfo(fir: FirFunctionCall, info: PathInfo) : FunctionInfo? {
            if (fir.isInvoke()) return resolveInvokeFunctionInfo(fir, info)

            return FunctionInfo(
                isLambda = false,
                isClassMemberOrExtension = fir.isClassMemberOrExtension(),
                returningConsumable = fir.resolvedType.hasMustConsumeAnnotation(context.session),
                returnIsConsumed = fir.hasDiscardableAnnotation(context.session),
                consumingThis = fir.hasConsumeAnnotation(context.session),
                consumedParameters = fir.getConsumedParameters()
            )
        }

        private fun resolveInvokeFunctionInfo(fir: FirFunctionCall, info: PathInfo) : FunctionInfo? {
            val originalRef = (fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference ?: return null
            val originalSymbol = originalRef.toResolvedCallableSymbol() ?: return null

            var funcInfo = info.getVarValue(originalSymbol)?.funcRef ?: return null

            // NOTE: invoking extension function causes the context object to be regarded as first argument
            //       the dispatchReceiver is no longer the context object, but the function reference
            if (funcInfo.isClassMemberOrExtension) {
                funcInfo = funcInfo.convertThisToFirstParameter()
            }

            return funcInfo
        }

        private fun consumeReceiver(node: FunctionCallNode, info: PathInfo, funcInfo: FunctionInfo) {
            if (!funcInfo.consumingThis) return

            val receiver = node.fir.dispatchReceiver ?: node.fir.extensionReceiver ?: return

            var valueSource = data.getValueSource(receiver)

            if (valueSource == null) {
                if (receiver is FirThisReceiverExpression) {
                    valueSource = ValueSource.ThisReference(node)
                } else {
                    return
                }
            }

            consumeValueSource(node, info, valueSource)
        }

        private fun consumeParameters(node: FunctionCallNode, info: PathInfo, funcInfo: FunctionInfo) {
            for (consumedId in funcInfo.consumedParameters) {
                val arg = node.fir.argumentList.arguments.getOrNull(consumedId) ?: continue
                val valueSource = data.getValueSource(arg) ?: continue

                consumeValueSource(node, info, valueSource)
            }
        }

        private fun consumeFreeVariables(node: FunctionCallNode, info: PathInfo, funcInfo: FunctionInfo) {
            for (freeVar in funcInfo.consumedFreeVariables) {
                consumeValueSource(node, info, variableValueSource(node, freeVar))
            }
        }

        private fun consumeValueSource(consumerNode: CFGNode<*>, info: PathInfo, valueSource: ValueSource, isReturn: Boolean = false) {
            when(valueSource) {
                is ValueSource.FuncCall -> info.removeUnutilizedCreation(valueSource)
                is ValueSource.Choices -> {
                    info.removeUnutilizedCreation(valueSource)
                    valueSource.sources.forEach { consumeValueSource(consumerNode, info, it, isReturn) }
                }
                is ValueSource.LocalVar -> {
                    val currentValue = info.getVarValue(valueSource.symbol)?.utilVal ?: return
                    consumeValueSource(consumerNode, info, currentValue, isReturn)
                }

                is ValueSource.NonLocalSource -> if (!isReturn) {
                    info.setNonLocalUtilization(valueSource, NonLocalUtilLattice.Utilized)
                }
            }
        }

        private fun handleReturnNode(node: CFGNode<*>, info: PathInfo) {
            val valueSource = propagateValueSource(node, info) ?: return
            consumeValueSource(node, info, valueSource, isReturn = true)
        }

        private fun handleVariableDeclaration(node: VariableDeclarationNode, info: PathInfo) {
            data.setVarOwner(node.fir.symbol, node.owner)

            val varType = node.fir.returnTypeRef.coneType

            if (varType.isSomeFunctionType(context.session)) {
                setVariableFunctionReference(node, info)
                return
            }

            if (!varType.hasMustConsumeAnnotation(context.session)) return

            var valueSource = data.getValueSource(node.firstPreviousNode) ?: return

            if (valueSource is ValueSource.LocalVar) {
                valueSource = info.getVarValue(valueSource.symbol)?.utilVal ?: return
            }

            info.setVarValue(node.fir.symbol, valueSource)
        }

        private fun setVariableFunctionReference(node: VariableDeclarationNode, info: PathInfo) {
            val functionInfo = node.firstPreviousNode.fir.let {
                when (it) {
                    is FirAnonymousFunctionExpression -> data.getLambdaInfo(it.anonymousFunction)

                    is FirCallableReferenceAccess -> {
                        val returnType = it.getReturnType() ?: return@let null

                        val funcinfo = FunctionInfo(
                            isLambda = false,
                            isClassMemberOrExtension = it.isClassMemberOrExtension(),
                            returningConsumable = returnType.hasMustConsumeAnnotation(context.session),
                            returnIsConsumed = it.hasDiscardableAnnotation(context.session),
                            consumingThis = it.hasConsumeAnnotation(context.session),
                            consumedParameters = it.getConsumedParameters()
                        )

                        funcinfo
                    }

                    is FirQualifiedAccessExpression -> {
                        val symbol = it.calleeReference.symbol ?: return
                        info.getVarValue(symbol)?.funcRef
                    }
                    else -> null
                }
            }

            if (functionInfo == null) return

            info.setVarValue(node.fir.symbol, functionInfo)
        }

        private fun handleQualifiedAccess(node: QualifiedAccessNode, info: PathInfo) {
            val varType = node.fir.resolvedType
            if (!varType.hasMustConsumeAnnotation(context.session)) return

            if (node.fir.calleeReference is FirThisReference) {
                data.addValueSource(node, ValueSource.ThisReference(node))
                return
            }

            val symbol = node.fir.calleeReference.symbol ?: return

            data.addValueSource(node, variableValueSource(node, symbol))
        }

        private fun variableValueSource(callerNode: CFGNode<*>, varSymbol: FirBasedSymbol<*>) : ValueSource {
            if (callerNode.owner != data.getVarOwner(varSymbol)) {
                return ValueSource.FreeVariable(varSymbol)
            }

            return ValueSource.LocalVar(varSymbol)
        }

        // Path context

        private fun propagatePathInfo(node: CFGNode<*>) : PathInfo {
            val pathInfos = node.previousNodes.asSequence()
                                .filterNot { it.isInvalidPrev(node) }
                                .mapNotNull { data.pathInfos[it] }
                                .toList()

            val mustCopy = node.previousNodes.getOrNull(0)?.validNextSize() != 1
            val info = pathInfos.mergeInfos(mustCopy)

            data.pathInfos[node] = info

            return info
        }

        // Value source

        private fun propagateValueSource(node: CFGNode<*>, info: PathInfo) : ValueSource? {
            var hasUnutilized = false
            val valueSources = node.previousNodes.mapNotNull {
                val valSrc = if (it.isInvalidPrev(node)) null
                             else data.removeValueSource(it)

                if (valSrc is ValueSource.CreationSource) {
                    hasUnutilized = hasUnutilized || info.removeUnutilizedCreation(valSrc)
                }

                valSrc
            }

            if (valueSources.isNotEmpty()) {
                val newSource = flattenSingleChoice(node, valueSources)
                data.addValueSource(node, newSource)

                if (hasUnutilized && newSource is ValueSource.CreationSource) {
                    info.addUnutilizedCreation(newSource)
                }

                return newSource
            }

            return null
        }

        private fun flattenSingleChoice(node: CFGNode<*>, sources: List<ValueSource>): ValueSource {
            if (sources.size == 1) {
                return sources[0]
            }

            return ValueSource.Choices(node, sources.mapNotNull { it as? ValueSource.CreationSource })
        }

        fun log(message: Any? = null) {
            if (logging) {
                if (message == null) println()
                else println(message)
            }
        }

    }

}