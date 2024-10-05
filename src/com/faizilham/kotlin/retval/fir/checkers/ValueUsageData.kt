package com.faizilham.kotlin.retval.fir.checkers

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol


class ValueUsageData() {
    private val pathContexts : MutableMap<CFGNode<*>, PathContext> = mutableMapOf()
    private val unusedValues : MutableMap<CFGNode<*>, UnusedSource> = mutableMapOf()
    private val lastUnusedFirNode : MutableMap<FirElement, CFGNode<*>> = mutableMapOf()

    val discardableFunctionRef: MutableSet<FunctionRef> = mutableSetOf()

    fun getPathContext(node: CFGNode<*>) : PathContext? {
        return pathContexts[node]
    }

    fun addPathContext(node: CFGNode<*>, context: PathContext) {
        pathContexts[node] = context
    }

    fun addUnused(node: CFGNode<*>, source: UnusedSource) {
        unusedValues[node] = source
        lastUnusedFirNode[node.fir] = node
    }

    fun getUnused(node: CFGNode<*>) : UnusedSource? {
        return unusedValues[node]
    }

    fun removeUnused(node: CFGNode<*>) : UnusedSource? {
        if (lastUnusedFirNode[node.fir] == node) {
            lastUnusedFirNode.remove(node.fir)
        }

        return unusedValues.remove(node)
    }

    fun removeUnused(fir: FirElement) : UnusedSource? {
        // work-around for EqualityOperatorCallNode case
        val node = lastUnusedFirNode[fir] ?: return null
        return unusedValues.remove(node)
    }

    fun getUnusedValues() = unusedValues.values
}

class PathContext(
    val previousContext: PathContext?,
    val contextType: ContextType,
    contextDepth: Int? = null
) {
    val contextDepth : Int

    companion object {
        val defaultBlockContext = PathContext(null, ContextType.Block)
    }

    init {
        if (contextDepth != null) {
            this.contextDepth = contextDepth
        } else {
            this.contextDepth =
                if (previousContext == null) 0
                else previousContext.contextDepth + 1
        }
    }

    fun isValueConsuming() : Boolean {
        return contextType == ContextType.ValueConsuming
    }

    fun copy(): PathContext {
        return PathContext(this.previousContext, this.contextType, this.contextDepth)
    }
}

enum class ContextType {
    ValueConsuming,
    Block
}

sealed interface UnusedSource {
    class AtomicExpr(val node: CFGNode<*>) : UnusedSource {}
    class FuncCall(val node: CFGNode<*>) : UnusedSource {}
    class Indirect(val node: CFGNode<*>, val sources: List<UnusedSource>) : UnusedSource {}
}

sealed interface FunctionRef {
    class Lambda(val declaration: FirDeclaration) : FunctionRef {
        override fun equals(other: Any?): Boolean {
            return other is Lambda && declaration == other.declaration
        }

        override fun hashCode(): Int {
            return declaration.hashCode()
        }
    }

    class Identifier(val symbol: FirBasedSymbol<*>) : FunctionRef {
        override fun equals(other: Any?): Boolean {
            return other is Identifier && symbol == other.symbol
        }

        override fun hashCode(): Int {
            return symbol.hashCode()
        }
    }
}
