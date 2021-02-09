package me.mattco.reeva.ast

import me.mattco.reeva.ast.ASTNode.Companion.appendIndent

class BindingIdentifierNode(val identifierName: String) : VariableSourceNode(), ExpressionNode {
    override fun dump(indent: Int) = buildString {
        appendIndent(indent)
        appendName()
        append(" (identifier=")
        append(identifierName)
        append(")\n")
    }

    override fun toString() = identifierName
}

class IdentifierNode(val identifierName: String) : ASTNodeBase(), ExpressionNode {
    override fun dump(indent: Int) = buildString {
        appendIndent(indent)
        appendName()
        append(" (identifier=")
        append(identifierName)
        append(")\n")
    }

    override fun toString() = identifierName
}

class IdentifierReferenceNode(val identifierName: String) : VariableRefNode(), ExpressionNode {
    override val isInvalidAssignmentTarget = false

    override fun boundName() = identifierName

    override fun dump(indent: Int) = buildString {
        appendIndent(indent)
        appendName()
        append(" (identifier=")
        append(identifierName)
        append(")\n")
    }

    override fun toString() = identifierName
}
