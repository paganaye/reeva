package com.reevajs.reeva.interpreter.transformer

import com.reevajs.reeva.ast.*
import com.reevajs.reeva.ast.expressions.*
import com.reevajs.reeva.ast.literals.BooleanLiteralNode
import com.reevajs.reeva.ast.literals.NumericLiteralNode
import com.reevajs.reeva.ast.literals.StringLiteralNode
import com.reevajs.reeva.ast.statements.*
import com.reevajs.reeva.core.Realm
import com.reevajs.reeva.core.lifecycle.Executable
import com.reevajs.reeva.interpreter.transformer.opcodes.*
import com.reevajs.reeva.parsing.HoistingScope
import com.reevajs.reeva.parsing.Scope
import com.reevajs.reeva.runtime.Operations
import com.reevajs.reeva.runtime.functions.JSFunction
import com.reevajs.reeva.utils.expect
import com.reevajs.reeva.utils.unreachable

class Transformer(val executable: Executable) : ASTVisitor {
    private lateinit var builder: IRBuilder
    private var currentScope: Scope? = null

    data class LabelledSection(val labels: Set<String>, val start: Int)

    private val breakableScopes = mutableListOf<LabelledSection>()
    private val continuableScopes = mutableListOf<LabelledSection>()

    fun transform(): TransformerResult {
        expect(executable.script != null)
        expect(!::builder.isInitialized, "Cannot reuse a Transformer")

        return try {
            val script = executable.script!!
            builder = IRBuilder(RESERVED_LOCALS, script.scope.inlineableLocalCount)

            globalDeclarationInstantiation(script.scope as HoistingScope) {
                visit(script.statements)
                if (!builder.isDone) {
                    +PushUndefined
                    +Return
                }
            }

            TransformerResult.Success(FunctionInfo(
                executable.name,
                builder.finalizeOpcodes(),
                builder.getLocals(),
                builder.argCount,
                script.scope.isStrict,
                isTopLevel = true,
                builder.getChildFunctions(),
            ))
        } catch (e: Throwable) {
            TransformerResult.InternalError(e)
        }
    }

    private fun enterScope(scope: Scope) {
        currentScope = scope

        if (scope.requiresEnv())
            +PushDeclarativeEnvRecord(scope.slotCount)
    }

    private fun exitScope(scope: Scope) {
        currentScope = scope.outer

        if (scope.requiresEnv())
            +PopEnvRecord
    }

    private fun globalDeclarationInstantiation(scope: HoistingScope, block: () -> Unit) {
        enterScope(scope)

        val variables = scope.variableSources

        val varVariables = variables.filter { it.type == VariableType.Var }
        val lexVariables = variables.filter { it.type != VariableType.Var }

        val varNames = varVariables.map { it.name() }
        val lexNames = lexVariables.map { it.name() }

        val functionNames = mutableListOf<String>()
        val functionsToInitialize = mutableListOf<FunctionDeclarationNode>()

        for (decl in varVariables.asReversed()) {
            if (decl !is FunctionDeclarationNode)
                continue

            val name = decl.name()
            if (name in functionNames)
                continue

            functionNames.add(0, name)

            // We only care about top-level functions. Functions in nested block
            // scopes get initialized in BlockDeclarationInstantiation
            if (decl !in scope.hoistedVariables)
                functionsToInitialize.add(0, decl)
        }

        val declaredVarNames = mutableListOf<String>()

        for (decl in varVariables) {
            if (decl is FunctionDeclarationNode)
                continue

            val name = decl.name()
            if (name in functionNames || name in declaredVarNames)
                continue

            declaredVarNames.add(name)
        }

        if (declaredVarNames.isNotEmpty() || lexNames.isNotEmpty() || functionNames.isNotEmpty())
            +DeclareGlobals(declaredVarNames, lexNames, functionNames)

        for (func in functionsToInitialize) {
            builder.addChildFunction(visitFunctionHelper(
                func.identifier.name,
                func.parameters,
                func.body,
                func.functionScope,
                func.body.scope,
                func.body.scope.isStrict,
                func.kind,
            ))

            storeToSource(func)
        }

        block()

        if (!builder.isDone)
            exitScope(scope)
    }

    private fun visitFunctionHelper(
        name: String,
        parameters: ParameterList,
        body: ASTNode,
        functionScope: Scope,
        bodyScope: Scope,
        isStrict: Boolean,
        kind: Operations.FunctionKind,
        classConstructorKind: JSFunction.ConstructorKind? = null,
    ): FunctionInfo {
        val prevBuilder = builder
        builder = IRBuilder(
            parameters.size + RESERVED_LOCALS,
            functionScope.inlineableLocalCount,
            classConstructorKind == JSFunction.ConstructorKind.Derived,
        )

        val functionPackage = makeFunctionInfo(
            name,
            parameters,
            body,
            functionScope,
            bodyScope,
            isStrict,
            isAsync = kind.isAsync,
            classConstructorKind,
        )

        builder = prevBuilder
        val closureOp = when {
            classConstructorKind != null -> ::CreateClassConstructor
            kind.isGenerator && kind.isAsync -> ::CreateAsyncGeneratorClosure
            kind.isGenerator -> ::CreateGeneratorClosure
            kind.isAsync -> ::CreateAsyncClosure
            else -> ::CreateClosure
        }
        +closureOp(functionPackage)
        return functionPackage
    }

    override fun visitFunctionDeclaration(node: FunctionDeclarationNode) {
        // nop
    }

    override fun visitBlock(node: BlockNode) {
        visitBlock(node, pushScope = true)
    }

    private fun visitBlock(node: BlockNode, pushScope: Boolean) {
        if (pushScope)
            enterScope(node.scope)

        try {
            if (node.labels.isNotEmpty())
                enterBreakableScope(node.labels)

            // BlockScopeInstantiation
            node.scope.variableSources.filterIsInstance<FunctionDeclarationNode>().forEach {
                builder.addChildFunction(visitFunctionHelper(
                    it.name(),
                    it.parameters,
                    it.body,
                    it.functionScope,
                    it.body.scope,
                    it.body.scope.isStrict,
                    it.kind,
                ))

                storeToSource(it)
            }

            visitASTListNode(node.statements)

            if (node.labels.isNotEmpty())
                exitBreakableScope()
        } finally {
            if (pushScope)
                exitScope(node.scope)
        }
    }

    private fun callClassInstanceFieldInitializer() {
        +PushClosure
        +LoadNamedProperty(Realm.`@@classInstanceFields`)
        +LoadValue(RECEIVER_LOCAL)
        +Call(0)
    }

    private fun makeImplicitClassConstructor(
        name: String,
        constructorKind: JSFunction.ConstructorKind,
        hasInstanceFields: Boolean,
    ): FunctionInfo {
        // One for the receiver/new.target
        var argCount = RESERVED_LOCALS
        if (constructorKind == JSFunction.ConstructorKind.Derived) {
            // ...and one for the rest param, if necessary
            argCount++
        }

        val prevBuilder = builder
        builder = IRBuilder(argCount, 0, constructorKind == JSFunction.ConstructorKind.Derived)

        if (constructorKind == JSFunction.ConstructorKind.Base) {
            if (hasInstanceFields)
                callClassInstanceFieldInitializer()
            +PushUndefined
            +Return
        } else {
            // Initializer the super constructor
            +GetSuperConstructor
            +LoadValue(NEW_TARGET_LOCAL)
            +CreateRestParam
            +ConstructArray
            if (hasInstanceFields)
                callClassInstanceFieldInitializer()
            +Return
        }

        return FunctionInfo(
            name,
            builder.finalizeOpcodes(),
            builder.getLocals(),
            argCount,
            isStrict = true,
            isTopLevel = false,
            builder.getChildFunctions()
        ).also {
            builder = prevBuilder
        }
    }

    private fun makeFunctionInfo(
        name: String,
        parameters: ParameterList,
        body: ASTNode,
        functionScope: Scope,
        bodyScope: Scope,
        isStrict: Boolean,
        isAsync: Boolean,
        classConstructorKind: JSFunction.ConstructorKind?,
        hasClassFields: Boolean = false,
    ): FunctionInfo {
        functionDeclarationInstantiation(
            parameters,
            functionScope,
            bodyScope,
            isStrict
        ) {
            if (hasClassFields && classConstructorKind == JSFunction.ConstructorKind.Base) {
                // We can't load fields here if we are in a derived constructor as super() hasn't
                // been called
                callClassInstanceFieldInitializer()
            }

            // body's scope is the same as the function's scope (the scope we receive
            // as a parameter). We don't want to re-enter the same scope, so we explicitly
            // call visitASTListNode instead, which skips the {enter,exit}Scope calls.
            if (body is BlockNode) {
                visitASTListNode(body.statements)
            } else visit(body)

            if (!builder.isDone) {
                if (classConstructorKind == JSFunction.ConstructorKind.Derived) {
                    expect(body is BlockNode)
                    // TODO: Check to see if this is redundant
                    +LoadValue(RECEIVER_LOCAL)
                    +ThrowSuperNotInitializedIfEmpty
                } else if (body is BlockNode) {
                    +PushUndefined
                }

                +Return
            }
        }

        return FunctionInfo(
            name,
            builder.finalizeOpcodes(),
            builder.getLocals(),
            builder.argCount,
            isStrict,
            isTopLevel = false,
            builder.getChildFunctions(),
        )
    }

    private fun functionDeclarationInstantiation(
        parameters: ParameterList,
        functionScope: Scope,
        bodyScope: Scope,
        isStrict: Boolean,
        evaluationBlock: () -> Unit,
    ) {
        expect(functionScope is HoistingScope)
        expect(bodyScope is HoistingScope)

        val variables = bodyScope.variableSources
        val varVariables = variables.filter { it.type == VariableType.Var }
        val functionNames = mutableListOf<String>()
        val functionsToInitialize = mutableListOf<FunctionDeclarationNode>()

        for (decl in varVariables.asReversed()) {
            if (decl !is FunctionDeclarationNode)
                continue

            val name = decl.name()
            if (name in functionNames)
                continue

            functionNames.add(0, name)

            // We only care about top-level functions. Functions in nested block
            // scopes get initialized in BlockDeclarationInstantiation
            if (decl !in bodyScope.hoistedVariables)
                functionsToInitialize.add(0, decl)
        }

        when (functionScope.argumentsMode) {
            HoistingScope.ArgumentsMode.None -> {
            }
            HoistingScope.ArgumentsMode.Unmapped -> {
                +CreateUnmappedArgumentsObject
                storeToSource(functionScope.argumentsSource)
            }
            HoistingScope.ArgumentsMode.Mapped -> {
                +CreateMappedArgumentsObject
                storeToSource(functionScope.argumentsSource)
            }
        }

        enterScope(functionScope)

        if (parameters.containsDuplicates())
            TODO("Handle duplicate parameter names")

        val receiver = functionScope.receiverVariable

        if (receiver != null && !receiver.isInlineable) {
            +LoadValue(RECEIVER_LOCAL)
            storeToSource(receiver)
        }

        parameters.forEachIndexed { index, param ->
            val local = Local(RESERVED_LOCALS + index)

            when (param) {
                is SimpleParameter -> {
                    if (param.initializer != null) {
                        +LoadValue(local)
                        builder.ifHelper(::JumpIfNotUndefined) {
                            visit(param.initializer)
                            storeToSource(param)
                        }
                    } else if (!param.isInlineable) {
                        +LoadValue(local)
                        storeToSource(param)
                    }
                }
                is BindingParameter -> {
                    if (param.initializer != null) {
                        +LoadValue(local)
                        builder.ifHelper(::JumpIfNotUndefined) {
                            visit(param.initializer)
                            +StoreValue(local)
                        }
                    }
                    TODO()
                    // assign(param.pattern, register)
                }
                is RestParameter -> {
                    TODO()
                    // +CreateRestParam
                    // assign(param.declaration.node)
                }
            }
        }

        for (func in functionsToInitialize) {
            builder.addChildFunction(visitFunctionHelper(
                func.identifier.name,
                func.parameters,
                func.body,
                func.functionScope,
                func.body.scope,
                isStrict,
                func.kind,
            ))

            storeToSource(func)
        }

        if (bodyScope != functionScope)
            enterScope(bodyScope)

        evaluationBlock()

        if (builder.isDone) {
            if (bodyScope != functionScope)
                exitScope(bodyScope)
            exitScope(functionScope)
        }
    }

    private fun loadFromSource(source: VariableSourceNode) {
        if (source.mode == VariableMode.Global) {
            if (source.name() == "undefined") {
                +PushUndefined
            } else {
                expect(source.type == VariableType.Var)
                +LoadGlobal(source.name())
            }

            return
        }

        expect(source.index != -1)

        if (source.isInlineable) {
            +LoadValue(Local(source.index))
        } else {
            val distance = currentScope!!.envDistanceFrom(source.scope)
            if (distance == 0) {
                +LoadCurrentEnvSlot(source.index)
            } else {
                +LoadEnvSlot(source.index, distance)
            }
        }
    }

    private fun storeToSource(source: VariableSourceNode) {
        if (source.mode == VariableMode.Global) {
            if (source.name() == "undefined") {
                if (source.scope.isStrict) {
                    +ThrowConstantError("cannot assign to constant variable \"undefined\"")
                } else return
            } else {
                expect(source.type == VariableType.Var)
                +StoreGlobal(source.name())
            }

            return
        }

        expect(source.index != -1)

        if (source.isInlineable) {
            +StoreValue(Local(source.index))
        } else {
            val distance = currentScope!!.envDistanceFrom(source.scope)
            if (distance == 0) {
                +StoreCurrentEnvSlot(source.index)
            } else {
                +StoreEnvSlot(source.index, distance)
            }
        }
    }

    override fun visitExpressionStatement(node: ExpressionStatementNode) {
        visitExpression(node.node)
        +Pop
    }

    override fun visitIfStatement(node: IfStatementNode) {
        visitExpression(node.condition)
        if (node.falseBlock == null) {
            builder.ifHelper(::JumpIfToBooleanFalse) {
                visit(node.trueBlock)
            }
        } else {
            builder.ifElseHelper(
                ::JumpIfToBooleanFalse,
                { visit(node.trueBlock) },
                { visit(node.falseBlock) },
            )
        }
    }

    override fun visitWhileStatement(node: WhileStatementNode) {
        val head = builder.opcodeCount()
        val jumpToEnd = Jump(-1)

        visitExpression(node.condition)
        builder.ifElseHelper(
            ::JumpIfToBooleanFalse,
            {
                enterBreakableScope(node.labels)
                enterContinuableScope(node.labels)
                visitStatement(node.body)
                exitContinuableScope()
                exitBreakableScope()

                +Jump(head)
            },
            {
                +jumpToEnd
            },
        )

        jumpToEnd.to = builder.opcodeCount()
    }

    override fun visitDoWhileStatement(node: DoWhileStatementNode) {
        val head = builder.opcodeCount()

        enterBreakableScope(node.labels)
        enterContinuableScope(node.labels)
        visitStatement(node.body)
        exitContinuableScope()
        exitBreakableScope()

        visitExpression(node.condition)
        builder.ifHelper(::JumpIfToBooleanFalse) {
            +Jump(head)
        }
    }

    override fun visitForStatement(node: ForStatementNode) {
        val jumpToEnd = JumpIfToBooleanFalse(-1)

        node.initializerScope?.also(::enterScope)
        node.initializer?.also(::visit)

        val head = builder.opcodeCount()
        node.condition?.also {
            visitExpression(it)
            +jumpToEnd
        }

        enterBreakableScope(node.labels)
        enterContinuableScope(node.labels)
        visitStatement(node.body)
        exitContinuableScope()
        exitBreakableScope()

        node.incrementer?.also(::visitExpression)
        +Jump(head)

        jumpToEnd.to = builder.opcodeCount()

        node.initializerScope?.also(::exitScope)
    }

    override fun visitSwitchStatement(node: SwitchStatementNode) {
        /**
         * switch (x) {
         *     case 0:
         *     case 1:
         *     case 2:
         *         <block>
         * }
         *
         * ...gets transformed into...
         *
         *     <x>
         *     Dup
         *     <0>
         *     TestEqualStrict
         *     JumpIfToBooleanTrue BLOCK    <- True op for cascading case
         *     Dup
         *     <1>
         *     TestEqualStrict
         *     JumpIfToBooleanTrue BLOCK    <- True op for cascading case
         *     Dup
         *     <2>
         *     TestEqualStrict
         *     JumpIfToBooleanFalse END     <- False op for non-cascading case
         * CODE:
         *     <block>
         * END:
         */

        visitExpression(node.target)

        var defaultClause: SwitchClause? = null
        val fallThroughJumps = mutableListOf<JumpInstr>()

        val endJumps = mutableListOf<Jump>()

        for (clause in node.clauses) {
            if (clause.target == null) {
                defaultClause = clause
                continue
            }

            +Dup
            visitExpression(clause.target)
            +TestEqualStrict

            if (clause.body == null) {
                fallThroughJumps.add(+JumpIfToBooleanTrue(-1))
            } else {
                val jumpAfterBody = +JumpIfToBooleanFalse(-1)

                if (fallThroughJumps.isNotEmpty()) {
                    for (jump in fallThroughJumps)
                        jump.to = builder.opcodeCount()
                    fallThroughJumps.clear()
                }

                enterBreakableScope(clause.labels)
                visit(clause.body)
                exitBreakableScope()

                endJumps.add(+Jump(-1))
                jumpAfterBody.to = builder.opcodeCount()
            }
        }

        defaultClause?.body?.also(::visit)

        endJumps.forEach {
            it.to = builder.opcodeCount()
        }
    }

    override fun visitForIn(node: ForInNode) {
        visitExpression(node.expression)
        +Dup
        builder.ifHelper(::JumpIfNotUndefined) {
            +ForInEnumerate
            iterateForEach(node)
        }
    }

    override fun visitForOf(node: ForOfNode) {
        visitExpression(node.expression)
        +GetIterator
        iterateForEach(node)
    }

    private fun iterateForEach(node: ForEachNode) {
        val iteratorLocal = builder.newLocalSlot(LocalKind.Value)
        +StoreValue(iteratorLocal)

        iterateValues(node.labels, iteratorLocal) {
            node.initializerScope?.also(::enterScope)
            when (val decl = node.decl) {
                is DeclarationNode -> assign(decl.declarations[0])
                else -> assign(decl)
            }
            visit(node.body)
            node.initializerScope?.also(::exitScope)
        }
    }

    private fun iterateValues(
        labels: Set<String>,
        iteratorLocal: Local,
        action: () -> Unit,
    ) {
        val head = builder.opcodeCount()

        +LoadValue(iteratorLocal)
        +IteratorNext
        +Dup
        // result result
        +IteratorResultDone
        // result isDone
        builder.ifHelper(::JumpIfTrue) {
            // result
            +IteratorResultValue

            enterBreakableScope(labels)
            enterContinuableScope(labels)
            action()
            exitContinuableScope()
            exitBreakableScope()

            +Jump(head)
        }
    }

    override fun visitBreakStatement(node: BreakStatementNode) {
        super.visitBreakStatement(node)
    }

    override fun visitCommaExpression(node: CommaExpressionNode) {
        for (expression in node.expressions) {
            visitExpression(expression)
            +Pop
        }
    }

    override fun visitBinaryExpression(node: BinaryExpressionNode) {
        val op = when (node.operator) {
            BinaryOperator.Add -> Add
            BinaryOperator.Sub -> Sub
            BinaryOperator.Mul -> Mul
            BinaryOperator.Div -> Div
            BinaryOperator.Exp -> Exp
            BinaryOperator.Mod -> Mod
            BinaryOperator.BitwiseAnd -> BitwiseAnd
            BinaryOperator.BitwiseOr -> BitwiseOr
            BinaryOperator.BitwiseXor -> BitwiseXor
            BinaryOperator.Shl -> ShiftLeft
            BinaryOperator.Shr -> ShiftRight
            BinaryOperator.UShr -> ShiftRightUnsigned
            BinaryOperator.StrictEquals -> TestEqualStrict
            BinaryOperator.StrictNotEquals -> TestNotEqualStrict
            BinaryOperator.SloppyEquals -> TestEqual
            BinaryOperator.SloppyNotEquals -> TestNotEqual
            BinaryOperator.LessThan -> TestLessThan
            BinaryOperator.LessThanEquals -> TestLessThanOrEqual
            BinaryOperator.GreaterThan -> TestGreaterThan
            BinaryOperator.GreaterThanEquals -> TestGreaterThanOrEqual
            BinaryOperator.Instanceof -> TestInstanceOf
            BinaryOperator.In -> TestIn
            BinaryOperator.And -> {
                visit(node.lhs)
                builder.ifHelper(::JumpIfToBooleanTrue) {
                    visit(node.rhs)
                }
                return
            }
            BinaryOperator.Or -> {
                visit(node.lhs)
                builder.ifHelper(::JumpIfToBooleanFalse) {
                    visit(node.rhs)
                }
                return
            }
            BinaryOperator.Coalesce -> {
                visit(node.lhs)
                builder.ifHelper(::JumpIfNotNullish) {
                    visit(node.rhs)
                }
                return
            }
        }

        visitExpression(node.lhs)
        visitExpression(node.rhs)
        +op
    }

    override fun visitUnaryExpression(node: UnaryExpressionNode) {
        if (node.op == UnaryOperator.Delete) {
            when (val expr = node.expression) {
                is IdentifierReferenceNode -> +PushConstant(false)
                !is MemberExpressionNode -> +PushConstant(true)
                else -> if (expr.type == MemberExpressionNode.Type.Tagged) {
                    +PushConstant(true)
                } else {
                    visitExpression(expr.lhs)

                    if (expr.type == MemberExpressionNode.Type.Computed) {
                        visit(expr.rhs)
                    } else {
                        +PushConstant((expr.rhs as IdentifierNode).name)
                    }

                    +if (node.scope.isStrict) DeletePropertyStrict else DeletePropertySloppy
                }
            }

            return
        }

        visitExpression(node.expression)

        when (node.op) {
            UnaryOperator.Void -> {
                +Pop
                +PushUndefined
            }
            UnaryOperator.Typeof -> +TypeOf
            UnaryOperator.Plus -> +ToNumber
            UnaryOperator.Minus -> +Negate
            UnaryOperator.BitwiseNot -> +BitwiseNot
            UnaryOperator.Not -> +ToBooleanLogicalNot
            else -> unreachable()
        }
    }

    override fun visitUpdateExpression(node: UpdateExpressionNode) {
        val op = if (node.isIncrement) Inc else Dec

        fun execute(duplicator: Opcode) {
            if (node.isPostfix) {
                +duplicator
                +op
            } else {
                +op
                +duplicator
            }
        }

        when (val target = node.target) {
            is IdentifierReferenceNode -> {
                visitExpression(target)
                +ToNumber
                execute(Dup)
                storeToSource(target.source)
            }
            is MemberExpressionNode -> {
                visitExpression(target.lhs)
                +Dup
                // lhs lhs

                when (target.type) {
                    MemberExpressionNode.Type.Computed -> {
                        visitExpression(target.rhs)
                        // lhs lhs rhs
                        +LoadKeyedProperty
                        // lhs value
                        +ToNumeric
                        // lhs value
                        execute(DupX1)
                        // value lhs value
                        visitExpression(target.rhs)
                        +Swap
                        // value lhs key value
                        +StoreKeyedProperty
                    }
                    MemberExpressionNode.Type.NonComputed -> {
                        val name = (target.rhs as IdentifierNode).name
                        +LoadNamedProperty(name)
                        execute(DupX1)
                        +StoreNamedProperty(name)
                    }
                    MemberExpressionNode.Type.Tagged -> TODO()
                }
            }
            else -> TODO()
        }
    }

    override fun visitLexicalDeclaration(node: LexicalDeclarationNode) {
        node.declarations.forEach(::visitDeclaration)
    }

    override fun visitVariableDeclaration(node: VariableDeclarationNode) {
        node.declarations.forEach(::visitDeclaration)
    }

    private fun visitDeclaration(declaration: Declaration) {
        if (declaration.initializer != null) {
            visitExpression(declaration.initializer!!)
        } else {
            +PushUndefined
        }


        when (declaration) {
            is NamedDeclaration -> assign(declaration)
            is DestructuringDeclaration -> assign(declaration.pattern)
        }
    }

    private fun assign(node: ASTNode, bindingPatternLocal: Local? = null) {
        when (node) {
            is VariableSourceNode -> storeToSource(node)
            is BindingPatternNode -> {
                val valueLocal = bindingPatternLocal ?: builder.newLocalSlot(LocalKind.Value)
                if (bindingPatternLocal == null)
                    +StoreValue(valueLocal)
                assignToBindingPattern(node, valueLocal)
            }
            is DestructuringDeclaration -> assign(node.pattern)
            is IdentifierReferenceNode -> storeToSource(node.source)
            else -> TODO()
        }
    }

    private fun assignToBindingPattern(node: BindingPatternNode, valueLocal: Local) {
        when (node.kind) {
            BindingKind.Object -> assignToObjectBindingPattern(node, valueLocal)
            BindingKind.Array -> assignToArrayBindingPattern(node, valueLocal)
        }
    }

    private fun assignToObjectBindingPattern(node: BindingPatternNode, valueLocal: Local) {
        TODO()
    }

    private fun assignToArrayBindingPattern(node: BindingPatternNode, valueLocal: Local) {
        TODO()
    }

    override fun visitAssignmentExpression(node: AssignmentExpressionNode) {
        val lhs = node.lhs
        val rhs = node.rhs

        expect(node.op == null || node.op.isAssignable)

        fun pushRhs() {
            if (node.op != null) {
                // First figure out the new value
                visitBinaryExpression(BinaryExpressionNode(lhs, rhs, node.op))
            } else {
                visitExpression(rhs)
            }
        }

        when (lhs) {
            is IdentifierReferenceNode -> {
                if (checkForConstReassignment(lhs))
                    return

                pushRhs()
                storeToSource(lhs.source)
            }
            is MemberExpressionNode -> {
                expect(!lhs.isOptional)
                visitExpression(lhs.lhs)

                when (lhs.type) {
                    MemberExpressionNode.Type.Computed -> {
                        visitExpression(lhs.rhs)
                        pushRhs()
                        +StoreKeyedProperty
                    }
                    MemberExpressionNode.Type.NonComputed -> {
                        pushRhs()
                        +StoreNamedProperty((lhs.rhs as IdentifierNode).name)
                    }
                    MemberExpressionNode.Type.Tagged -> TODO()
                }
            }
            else -> TODO()
        }
    }

    private fun checkForConstReassignment(node: VariableRefNode): Boolean {
        return if (node.source.type == VariableType.Const) {
            +ThrowConstantError("cannot reassign constant variable \"${node.source.name()}\"")
            true
        } else false
    }

    override fun visitMemberExpression(node: MemberExpressionNode) {
        pushMemberExpression(node, pushReceiver = false)
    }

    private fun pushMemberExpression(node: MemberExpressionNode, pushReceiver: Boolean) {
        visitExpression(node.lhs)

        if (node.isOptional) {
            builder.ifHelper(::JumpIfNotNullish) {
                +PushUndefined
            }
        }

        if (pushReceiver)
            +Dup

        when (node.type) {
            MemberExpressionNode.Type.Computed -> {
                visitExpression(node.rhs)
                +LoadKeyedProperty
            }
            MemberExpressionNode.Type.NonComputed -> {
                +LoadNamedProperty((node.rhs as IdentifierNode).name)
            }
            MemberExpressionNode.Type.Tagged -> TODO()
        }

        if (pushReceiver)
            +Swap
    }

    override fun visitReturnStatement(node: ReturnStatementNode) {
        if (node.expression == null) {
            +PushUndefined
        } else {
            visitExpression(node.expression)
        }

        +Return
    }

    override fun visitIdentifierReference(node: IdentifierReferenceNode) {
        loadFromSource(node.source)
        if (node.source.isInlineable)
            return

        if (node.source.mode == VariableMode.Global || node.source.type == VariableType.Var)
            return

        // We need to check if the variable has been initialized
        +Dup
        builder.ifHelper(::JumpIfNotEmpty) {
            +ThrowConstantError("cannot access lexical variable \"${node.identifierName}\" before initialization")
        }
    }

    enum class ArgumentsMode {
        Spread,
        Normal,
    }

    private fun argumentsMode(arguments: ArgumentList): ArgumentsMode {
        return if (arguments.any { it.isSpread }) {
            ArgumentsMode.Spread
        } else ArgumentsMode.Normal
    }

    private fun pushArguments(arguments: ArgumentList): ArgumentsMode {
        val mode = argumentsMode(arguments)
        when (mode) {
            ArgumentsMode.Spread -> TODO()
            ArgumentsMode.Normal -> {
                for (argument in arguments)
                    visitExpression(argument)
            }
        }
        return mode
    }

    override fun visitCallExpression(node: CallExpressionNode) {
        if (node.target is MemberExpressionNode) {
            pushMemberExpression(node.target, pushReceiver = true)
        } else {
            visitExpression(node.target)
            +PushUndefined
        }

        fun buildCall() {
            if (pushArguments(node.arguments) == ArgumentsMode.Normal) {
                +Call(node.arguments.size)
            } else {
                +CallArray
            }
        }

        if (node.isOptional) {
            builder.ifElseHelper(
                ::JumpIfNotNullish,
                { +PushUndefined },
                { buildCall() },
            )
        } else {
            buildCall()
        }
    }

    override fun visitNewExpression(node: NewExpressionNode) {
        visitExpression(node.target)
        // TODO: Property new.target
        +Dup

        if (pushArguments(node.arguments) == ArgumentsMode.Normal) {
            +Construct(node.arguments.size)
        } else {
            +ConstructArray
        }
    }

    override fun visitStringLiteral(node: StringLiteralNode) {
        +PushConstant(node.value)
    }

    override fun visitBooleanLiteral(node: BooleanLiteralNode) {
        +PushConstant(node.value)
    }

    override fun visitNullLiteral() {
        +PushNull
    }

    override fun visitNumericLiteral(node: NumericLiteralNode) {
        +PushConstant(node.value)
    }

    private fun unsupported(message: String): Nothing {
        throw NotImplementedError(message)
    }

    private fun enterBreakableScope(labels: Set<String>) {
        breakableScopes.add(LabelledSection(labels, builder.opcodeCount()))
    }

    private fun exitBreakableScope() {
        breakableScopes.removeLast()
    }

    private fun enterContinuableScope(labels: Set<String>) {
        continuableScopes.add(LabelledSection(labels, builder.opcodeCount()))
    }

    private fun exitContinuableScope() {
        continuableScopes.removeLast()
    }

    private operator fun <T : Opcode> T.unaryPlus() = apply {
        builder.addOpcode(this)
    }

    companion object {
        val RECEIVER_LOCAL = Local(0)
        val NEW_TARGET_LOCAL = Local(1)
        const val RESERVED_LOCALS = 2
    }
}