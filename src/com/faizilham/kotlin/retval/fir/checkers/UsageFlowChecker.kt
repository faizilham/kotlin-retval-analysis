package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.Utils
import com.faizilham.kotlin.retval.fir.attributes.usageObligation
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isUnitOrNullableUnit
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId

object UsageFlowChecker : FirControlFlowChecker(MppCheckerKind.Common) {
    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter, context: CheckerContext) {

        if (graph.kind != ControlFlowGraph.Kind.Function) {
            return
        }

        println("analyze ${graph.name} ${graph.kind}")

        var argumentScopeLevel = 0

        val unusedValues : MutableList<CFGNode<*>> = mutableListOf()

        for (node in graph.nodes) {
            println("${node.toString()} ${node.level}")

            when(node) {
                is FunctionCallArgumentsEnterNode -> {
                    argumentScopeLevel += 1
                }

                is FunctionCallArgumentsExitNode -> {
                    argumentScopeLevel -= 1
                }

                is FunctionCallNode -> {
                    if (argumentScopeLevel != 0 || isDiscardable(node.fir)) continue
                    unusedValues.add(node)
                }

                is VariableAssignmentNode -> {
                    if (node.firstPreviousNode == unusedValues.lastOrNull()) {
                        unusedValues.removeLast()
                    }
                }

                is VariableDeclarationNode -> {
                    if (node.firstPreviousNode == unusedValues.lastOrNull()) {
                        unusedValues.removeLast()
                    }
                }

                is JumpNode -> {
                    // TODO: check if there is other cases of jump nodes aside from returning
                    if (node.firstPreviousNode == unusedValues.lastOrNull()) {
                        unusedValues.removeLast()
                    }
                }

//                is EqualityOperatorCallNode -> {
//                    node.leftOperandNode
//                }

                is WhenBranchConditionEnterNode -> {
                    argumentScopeLevel += 1
                }

                is WhenBranchConditionExitNode -> {
                    argumentScopeLevel -= 1
                }

                else -> {
//                     TODO: subgraph, when nodes?, binary nodes etc
                }
            }
        }

        // report all unused values
        for (node in unusedValues) {
            reporter.reportOn(node.fir.source, Utils.Warnings.UNUSED_RETURN_VALUE, context)
        }
    }


    private fun isDiscardable(expr: FirFunctionCall) : Boolean {
        if (isDiscardableType(expr.resolvedType)) {
            return true
        }

        val annotations = expr.calleeReference.toResolvedFunctionSymbol()?.resolvedAnnotationClassIds
        val hasDiscardable = annotations?.any { it == Utils.Constants.DiscardableClassId } ?: false

        return hasDiscardable
    }

    private fun isDiscardableType(type: ConeKotlinType) : Boolean {
        return type.isUnitOrNullableUnit ||
                isBuiltInDiscardable(type.classId) ||
                (type.attributes.usageObligation?.isMayUse() ?: false)
    }

    private fun isBuiltInDiscardable(classId: ClassId?) : Boolean {
        return classId != null && builtInDiscardable.contains(classId)
    }

    // Hard-coded class to be discardable, work around until it can be annotated
    private val builtInDiscardable : Set<ClassId> = setOf(
        ClassId.fromString("kotlin/contracts/CallsInPlace")
    )
}
