/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.builder

import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.KtNodeTypes.*
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.builder.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.utils.addDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.diagnostics.ConeSimpleDiagnostic
import org.jetbrains.kotlin.fir.diagnostics.ConeUnderscoreIsReserved
import org.jetbrains.kotlin.fir.diagnostics.DiagnosticKind
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.builder.*
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.builder.*
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.lexer.KtTokens.CLOSING_QUOTE
import org.jetbrains.kotlin.lexer.KtTokens.OPEN_QUOTE
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.parsing.*
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.types.ConstantValueKind
import org.jetbrains.kotlin.util.OperatorNameConventions

//T can be either PsiElement, or LighterASTNode
abstract class BaseFirBuilder<T>(val baseSession: FirSession, val context: Context<T> = Context()) {
    val baseModuleData: FirModuleData = baseSession.moduleData

    abstract fun T.toFirSourceElement(kind: FirFakeSourceElementKind? = null): FirSourceElement

    protected val implicitUnitType = baseSession.builtinTypes.unitType
    protected val implicitAnyType = baseSession.builtinTypes.anyType
    protected val implicitEnumType = baseSession.builtinTypes.enumType
    protected val implicitAnnotationType = baseSession.builtinTypes.annotationType

    abstract val T.elementType: IElementType
    abstract val T.asText: String
    abstract val T.unescapedValue: String
    abstract fun T.getReferencedNameAsName(): Name
    abstract fun T.getLabelName(): String?
    abstract fun T.getExpressionInParentheses(): T?
    abstract fun T.getAnnotatedExpression(): T?
    abstract fun T.getLabeledExpression(): T?
    abstract fun T.getChildNodeByType(type: IElementType): T?
    abstract val T?.receiverExpression: T?
    abstract val T?.selectorExpression: T?
    abstract val T?.arrayExpression: T?
    abstract val T?.indexExpressions: List<T>?

    /**** Class name utils ****/
    inline fun <T> withChildClassName(
        name: Name,
        isExpect: Boolean,
        forceLocalContext: Boolean = false,
        l: () -> T
    ): T {
        context.className = context.className.child(name)
        val oldForcedLocalContext = context.forcedLocalContext
        context.forcedLocalContext = forceLocalContext || context.forcedLocalContext
        val previousIsExpect = context.containerIsExpect
        context.containerIsExpect = previousIsExpect || isExpect
        val dispatchReceiversNumber = context.dispatchReceiverTypesStack.size
        return try {
            l()
        } finally {
            require(context.dispatchReceiverTypesStack.size <= dispatchReceiversNumber + 1) {
                "Wrong number of ${context.dispatchReceiverTypesStack.size}"
            }

            if (context.dispatchReceiverTypesStack.size > dispatchReceiversNumber) {
                context.dispatchReceiverTypesStack.removeAt(context.dispatchReceiverTypesStack.lastIndex)
            }

            context.className = context.className.parent()
            context.forcedLocalContext = oldForcedLocalContext
            context.containerIsExpect = previousIsExpect
        }
    }

    fun registerSelfType(selfType: FirResolvedTypeRef) {
        context.dispatchReceiverTypesStack.add(selfType.type as ConeClassLikeType)
    }

    inline fun <T> withCapturedTypeParameters(status: Boolean, currentFirTypeParameters: List<FirTypeParameterRef>, block: () -> T): T {
        context.pushFirTypeParameters(status, currentFirTypeParameters)
        return try {
            block()
        } finally {
            context.popFirTypeParameters()
        }
    }

    fun callableIdForName(name: Name) =
        when {
            context.className.shortNameOrSpecial() == SpecialNames.ANONYMOUS -> CallableId(ANONYMOUS_CLASS_ID, name)
            context.className.isRoot && !context.inLocalContext -> CallableId(context.packageFqName, name)
            context.inLocalContext -> {
                val pathFqName =
                    context.firFunctionTargets.fold(
                        if (context.className.isRoot) context.packageFqName else context.currentClassId.asSingleFqName()
                    ) { result, firFunctionTarget ->
                        if (firFunctionTarget.isLambda || firFunctionTarget.labelName == null)
                            result
                        else
                            result.child(Name.identifier(firFunctionTarget.labelName!!))
                    }
                CallableId(name, pathFqName)
            }
            else -> CallableId(context.packageFqName, context.className, name)
        }

    fun currentDispatchReceiverType(): ConeClassLikeType? = currentDispatchReceiverType(context)

    /**
     * @return second from the end dispatch receiver. For the inner class constructor it would be the outer class.
     */
    protected fun dispatchReceiverForInnerClassConstructor(): ConeClassLikeType? {
        val dispatchReceivers = context.dispatchReceiverTypesStack
        return dispatchReceivers.getOrNull(dispatchReceivers.lastIndex - 1)
    }

    fun callableIdForClassConstructor() =
        if (context.className == FqName.ROOT) CallableId(context.packageFqName, Name.special("<anonymous-init>"))
        else CallableId(context.packageFqName, context.className, context.className.shortName())


    /**** Function utils ****/
    fun <T> MutableList<T>.removeLast(): T {
        return removeAt(size - 1)
    }

    fun <T> MutableList<T>.pop(): T? {
        val result = lastOrNull()
        if (result != null) {
            removeAt(size - 1)
        }
        return result
    }

    fun FirExpression.toReturn(
        baseSource: FirSourceElement? = source,
        labelName: String? = null,
        fromKtReturnExpression: Boolean = false
    ): FirReturnExpression {
        return buildReturnExpression {
            fun FirFunctionTarget.bindToErrorFunction(message: String, kind: DiagnosticKind) {
                bind(
                    buildErrorFunction {
                        source = baseSource
                        moduleData = baseModuleData
                        origin = FirDeclarationOrigin.Source
                        diagnostic = ConeSimpleDiagnostic(message, kind)
                        symbol = FirErrorFunctionSymbol()
                    }
                )
            }

            source =
                if (fromKtReturnExpression) baseSource?.realElement()
                else baseSource?.fakeElement(FirFakeSourceElementKind.ImplicitReturn)
            result = this@toReturn
            if (labelName == null) {
                target = context.firFunctionTargets.lastOrNull { !it.isLambda } ?: FirFunctionTarget(labelName, isLambda = false).apply {
                    bindToErrorFunction("Cannot bind unlabeled return to a function", DiagnosticKind.ReturnNotAllowed)
                }
            } else {
                for (functionTarget in context.firFunctionTargets.asReversed()) {
                    if (functionTarget.labelName == labelName) {
                        target = functionTarget
                        return@buildReturnExpression
                    }
                }
                target = FirFunctionTarget(labelName, false).apply {
                    if (context.firLabels.any { it.name == labelName }) {
                        bindToErrorFunction("Label $labelName does not target a function", DiagnosticKind.NotAFunctionLabel)
                    } else {
                        bindToErrorFunction("Cannot bind label $labelName to a function", DiagnosticKind.UnresolvedLabel)
                    }
                }
            }
        }
    }

    fun T?.toDelegatedSelfType(firClass: FirRegularClassBuilder): FirResolvedTypeRef =
        toDelegatedSelfType(firClass.typeParameters, firClass.symbol)

    fun T?.toDelegatedSelfType(firObject: FirAnonymousObjectBuilder): FirResolvedTypeRef =
        toDelegatedSelfType(firObject.typeParameters, firObject.symbol)

    protected fun T?.toDelegatedSelfType(typeParameters: List<FirTypeParameterRef>, symbol: FirClassLikeSymbol<*>): FirResolvedTypeRef {
        return buildResolvedTypeRef {
            source = this@toDelegatedSelfType?.toFirSourceElement(FirFakeSourceElementKind.ClassSelfTypeRef)
            type = ConeClassLikeTypeImpl(
                symbol.toLookupTag(),
                typeParameters.map { ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), false) }.toTypedArray(),
                false
            )
        }
    }

    fun constructorTypeParametersFromConstructedClass(ownerTypeParameters: List<FirTypeParameterRef>): List<FirTypeParameterRef> {
        return ownerTypeParameters.mapNotNull {
            val declaredTypeParameter = (it as? FirTypeParameter) ?: return@mapNotNull null
            buildConstructedClassTypeParameterRef { symbol = declaredTypeParameter.symbol }
        }
    }

    fun FirLoopBuilder.prepareTarget(firLabelUser: Any): FirLoopTarget = prepareTarget(context.getLastLabel(firLabelUser))

    fun FirLoopBuilder.prepareTarget(label: FirLabel?): FirLoopTarget {
        this.label = label
        val target = FirLoopTarget(label?.name)
        context.firLoopTargets += target
        return target
    }

    fun FirLoopBuilder.configure(target: FirLoopTarget, generateBlock: () -> FirBlock): FirLoop {
        block = generateBlock()
        val loop = build()
        val stackTopTarget = context.firLoopTargets.removeLast()
        assert(target == stackTopTarget) {
            "Loop target preparation and loop configuration mismatch"
        }
        target.bind(loop)
        return loop
    }

    fun FirLoopJumpBuilder.bindLabel(expression: T): FirLoopJumpBuilder {
        val labelName = expression.getLabelName()
        val lastLoopTarget = context.firLoopTargets.lastOrNull()
        val sourceElement = expression.toFirSourceElement()
        if (labelName == null) {
            target = lastLoopTarget ?: FirLoopTarget(labelName).apply {
                bind(
                    buildErrorLoop(
                        sourceElement,
                        ConeSimpleDiagnostic("Cannot bind unlabeled jump to a loop", DiagnosticKind.JumpOutsideLoop)
                    )
                )
            }
        } else {
            for (firLoopTarget in context.firLoopTargets.asReversed()) {
                if (firLoopTarget.labelName == labelName) {
                    target = firLoopTarget
                    return this
                }
            }
            target = FirLoopTarget(labelName).apply {
                bind(
                    buildErrorLoop(
                        sourceElement,
                        ConeSimpleDiagnostic(
                            "Cannot bind label $labelName to a loop",
                            lastLoopTarget?.let { DiagnosticKind.NotLoopLabel } ?: DiagnosticKind.JumpOutsideLoop
                        )
                    )
                )
            }
        }
        return this
    }

    fun generateConstantExpressionByLiteral(expression: T): FirExpression {
        val type = expression.elementType
        val text: String = expression.asText
        val sourceElement = expression.toFirSourceElement()

        fun reportIncorrectConstant(kind: DiagnosticKind): FirErrorExpression {
            return buildErrorExpression {
                source = sourceElement
                diagnostic = ConeSimpleDiagnostic("Incorrect constant expression: $text", kind)
            }
        }

        val convertedText: Any? = when (type) {
            INTEGER_CONSTANT, FLOAT_CONSTANT -> when {
                hasIllegalUnderscore(text, type) -> return reportIncorrectConstant(DiagnosticKind.IllegalUnderscore)
                else -> parseNumericLiteral(text, type)
            }
            BOOLEAN_CONSTANT -> parseBoolean(text)
            else -> null
        }
        return when (type) {
            INTEGER_CONSTANT -> {
                var diagnostic: DiagnosticKind = DiagnosticKind.IllegalConstExpression
                val number: Long?

                val kind = when {
                    convertedText == null -> {
                        number = null
                        diagnostic = DiagnosticKind.IntLiteralOutOfRange
                        ConstantValueKind.IntegerLiteral
                    }

                    convertedText !is Long -> return reportIncorrectConstant(DiagnosticKind.IllegalConstExpression)

                    hasUnsignedLongSuffix(text) -> {
                        if (text.endsWith("l")) {
                            diagnostic = DiagnosticKind.WrongLongSuffix
                            number = null
                        } else {
                            number = convertedText
                        }
                        ConstantValueKind.UnsignedLong
                    }
                    hasLongSuffix(text) -> {
                        if (text.endsWith("l")) {
                            diagnostic = DiagnosticKind.WrongLongSuffix
                            number = null
                        } else {
                            number = convertedText
                        }
                        ConstantValueKind.Long
                    }
                    hasUnsignedSuffix(text) -> {
                        number = convertedText
                        ConstantValueKind.UnsignedIntegerLiteral
                    }

                    else -> {
                        number = convertedText
                        ConstantValueKind.IntegerLiteral
                    }
                }

                buildConstOrErrorExpression(
                    sourceElement,
                    kind,
                    number,
                    ConeSimpleDiagnostic("Incorrect integer literal: $text", diagnostic)
                )
            }
            FLOAT_CONSTANT ->
                if (convertedText is Float) {
                    buildConstOrErrorExpression(
                        sourceElement,
                        ConstantValueKind.Float,
                        convertedText,
                        ConeSimpleDiagnostic("Incorrect float: $text", DiagnosticKind.FloatLiteralOutOfRange)
                    )
                } else {
                    buildConstOrErrorExpression(
                        sourceElement,
                        ConstantValueKind.Double,
                        convertedText as? Double,
                        ConeSimpleDiagnostic("Incorrect double: $text", DiagnosticKind.FloatLiteralOutOfRange)
                    )
                }
            CHARACTER_CONSTANT -> {
                val characterWithDiagnostic = text.parseCharacter()
                buildConstOrErrorExpression(
                    sourceElement,
                    ConstantValueKind.Char,
                    characterWithDiagnostic.value,
                    ConeSimpleDiagnostic(
                        "Incorrect character: $text",
                        characterWithDiagnostic.getDiagnostic() ?: DiagnosticKind.IllegalConstExpression
                    )
                )
            }
            BOOLEAN_CONSTANT ->
                buildConstExpression(
                    sourceElement,
                    ConstantValueKind.Boolean,
                    convertedText as Boolean
                )
            NULL ->
                buildConstExpression(
                    sourceElement,
                    ConstantValueKind.Null,
                    null
                )
            else ->
                throw AssertionError("Unknown literal type: $type, $text")
        }
    }

    fun Array<out T?>.toInterpolatingCall(
        base: T,
        getElementType: (T) -> IElementType = { it.elementType },
        convertTemplateEntry: T?.(String) -> FirExpression
    ): FirExpression {
        return buildStringConcatenationCall {
            val sb = StringBuilder()
            var hasExpressions = false
            argumentList = buildArgumentList {
                L@ for (entry in this@toInterpolatingCall) {
                    if (entry == null) continue
                    arguments += when (getElementType(entry)) {
                        OPEN_QUOTE, CLOSING_QUOTE -> continue@L
                        LITERAL_STRING_TEMPLATE_ENTRY -> {
                            sb.append(entry.asText)
                            buildConstExpression(entry.toFirSourceElement(), ConstantValueKind.String, entry.asText)
                        }
                        ESCAPE_STRING_TEMPLATE_ENTRY -> {
                            sb.append(entry.unescapedValue)
                            buildConstExpression(entry.toFirSourceElement(), ConstantValueKind.String, entry.unescapedValue)
                        }
                        SHORT_STRING_TEMPLATE_ENTRY, LONG_STRING_TEMPLATE_ENTRY -> {
                            hasExpressions = true
                            entry.convertTemplateEntry("Incorrect template argument")
                        }
                        else -> {
                            hasExpressions = true
                            buildErrorExpression {
                                source = entry.toFirSourceElement()
                                diagnostic = ConeSimpleDiagnostic("Incorrect template entry: ${entry.asText}", DiagnosticKind.Syntax)
                            }
                        }
                    }
                }
            }
            source = base?.toFirSourceElement()
            // Fast-pass if there is no non-const string expressions
            if (!hasExpressions) return buildConstExpression(source, ConstantValueKind.String, sb.toString())
        }
    }

    /**
     * given:
     * argument++
     *
     * result:
     * {
     *     val <unary> = argument
     *     argument = <unary>.inc()
     *     ^<unary>
     * }
     *
     * given:
     * ++argument
     *
     * result:
     * {
     *     val <unary-result> = argument.inc()
     *     argument = <unary-result>
     *     ^<unary-result>
     * }
     *
     */

    // TODO:
    // 1. Support receiver capturing for `a?.b++` (elementType == SAFE_ACCESS_EXPRESSION).
    // 2. Add box test cases for #1 where receiver expression has side effects.
    fun generateIncrementOrDecrementBlock(
        baseExpression: T,
        operationReference: T?,
        argument: T?,
        callName: Name,
        prefix: Boolean,
        convert: T.() -> FirExpression
    ): FirExpression {
        val unwrappedArgument = argument.unwrap() ?: return buildErrorExpression {
            diagnostic = ConeSimpleDiagnostic("Inc/dec without operand", DiagnosticKind.Syntax)
        }

        if (unwrappedArgument.elementType == DOT_QUALIFIED_EXPRESSION) {
            return generateIncrementOrDecrementBlockForQualifiedAccess(
                baseExpression,
                operationReference,
                unwrappedArgument,
                callName,
                prefix,
                convert
            )
        }

        if (unwrappedArgument.elementType == ARRAY_ACCESS_EXPRESSION) {
            return generateIncrementOrDecrementBlockForArrayAccess(
                baseExpression,
                operationReference,
                unwrappedArgument,
                callName,
                prefix,
                convert
            )
        }

        return buildBlock {
            val baseSource = baseExpression?.toFirSourceElement()
            val desugaredSource = baseSource?.fakeElement(FirFakeSourceElementKind.DesugaredIncrementOrDecrement)
            source = desugaredSource

            // initialValueVar is only used for postfix increment/decrement (stores the argument value before increment/decrement).
            val initialValueVar = generateTemporaryVariable(
                baseModuleData,
                desugaredSource,
                SpecialNames.UNARY,
                unwrappedArgument.convert()
            )

            // resultInitializer is the expression for `argument.inc()`
            val resultInitializer = buildFunctionCall {
                source = desugaredSource
                calleeReference = buildSimpleNamedReference {
                    source = operationReference?.toFirSourceElement()
                    name = callName
                }
                explicitReceiver = if (prefix) {
                    unwrappedArgument.convert()
                } else {
                    generateResolvedAccessExpression(desugaredSource, initialValueVar)
                }
                origin = FirFunctionCallOrigin.Operator
            }

            // resultVar is only used for prefix increment/decrement.
            val resultVar = generateTemporaryVariable(
                baseModuleData,
                desugaredSource,
                Name.special("<unary-result>"),
                resultInitializer
            )

            val assignment = unwrappedArgument.generateAssignment(
                desugaredSource,
                null,
                if (prefix && unwrappedArgument.elementType != REFERENCE_EXPRESSION)
                    generateResolvedAccessExpression(source, resultVar)
                else
                    resultInitializer,
                FirOperation.ASSIGN, convert
            )

            fun appendAssignment() {
                if (assignment is FirBlock) {
                    statements += assignment.statements
                } else {
                    statements += assignment
                }
            }

            if (prefix) {
                if (unwrappedArgument.elementType != REFERENCE_EXPRESSION) {
                    statements += resultVar
                    appendAssignment()
                    statements += generateResolvedAccessExpression(desugaredSource, resultVar)
                } else {
                    appendAssignment()
                    statements += generateAccessExpression(desugaredSource, desugaredSource, unwrappedArgument.getReferencedNameAsName())
                }
            } else {
                statements += initialValueVar
                appendAssignment()
                statements += generateResolvedAccessExpression(desugaredSource, initialValueVar)
            }
        }
    }

    private fun T?.unwrap(): T? {
        // NOTE: By removing surrounding parentheses and labels, FirLabels will NOT be created for those labels.
        // This should be fine since the label is meaningless and unusable for a ++/-- argument or assignment LHS.
        var unwrapped = this
        while (true) {
            unwrapped = when (unwrapped?.elementType) {
                PARENTHESIZED -> unwrapped?.getExpressionInParentheses()
                LABELED_EXPRESSION -> unwrapped?.getLabeledExpression()
                ANNOTATED_EXPRESSION -> unwrapped?.getAnnotatedExpression()
                else -> return unwrapped
            }
        }
    }

    /**
     * given:
     * a.b++
     *
     * result:
     * {
     *     val <receiver> = a
     *     val <unary> = <receiver>.b
     *     <receiver>.b = <unary>.inc()
     *     ^<unary>
     * }
     *
     * given:
     * ++a.b
     *
     * result:
     * {
     *     val <receiver> = a
     *     val <unary-result> = <receiver>.b.inc()
     *     <receiver>.b = <unary-result>
     *     ^<unary-result>
     * }
     *
     */
    private fun generateIncrementOrDecrementBlockForQualifiedAccess(
        baseExpression: T,
        operationReference: T?,
        argument: T,
        callName: Name,
        prefix: Boolean,
        convert: T.() -> FirExpression
    ): FirExpression {
        return buildBlock {
            val baseSource = baseExpression?.toFirSourceElement()
            val desugaredSource = baseSource?.fakeElement(FirFakeSourceElementKind.DesugaredIncrementOrDecrement)
            source = desugaredSource

            val argumentReceiver = argument.receiverExpression
            val argumentSelector = argument.selectorExpression

            val argumentReceiverVariable = generateTemporaryVariable(
                baseModuleData,
                argumentReceiver?.toFirSourceElement(),
                Name.special("<receiver>"),
                argumentReceiver?.convert() ?: buildErrorExpression {
                    source = argument.toFirSourceElement()
                    diagnostic = ConeSimpleDiagnostic("Qualified expression without receiver", DiagnosticKind.Syntax)
                }
            ).also { statements += it }

            val firArgument = generateResolvedAccessExpression(argumentReceiverVariable.source, argumentReceiverVariable).let { receiver ->
                val firArgumentSelector = argumentSelector?.convert() ?: buildErrorExpression {
                    source = argument.toFirSourceElement()
                    diagnostic = ConeSimpleDiagnostic("Qualified expression without selector", DiagnosticKind.Syntax)
                }
                firArgumentSelector.also { if (it is FirQualifiedAccessExpression) it.replaceExplicitReceiver(receiver) }
            }

            // initialValueVar is only used for postfix increment/decrement (stores the argument value before increment/decrement).
            val initialValueVar = generateTemporaryVariable(
                baseModuleData,
                desugaredSource,
                SpecialNames.UNARY,
                firArgument
            )

            // resultInitializer is the expression for `argument.inc()`
            val resultInitializer = buildFunctionCall {
                source = desugaredSource
                calleeReference = buildSimpleNamedReference {
                    source = operationReference?.toFirSourceElement()
                    name = callName
                }
                explicitReceiver = if (prefix) {
                    firArgument
                } else {
                    generateResolvedAccessExpression(desugaredSource, initialValueVar)
                }
                origin = FirFunctionCallOrigin.Operator
            }

            // resultVar is only used for prefix increment/decrement.
            val resultVar = generateTemporaryVariable(
                baseModuleData,
                desugaredSource,
                Name.special("<unary-result>"),
                resultInitializer
            )

            fun appendAssignment() {
                if (firArgument is FirQualifiedAccessExpression) {
                    statements += buildVariableAssignment {
                        source = desugaredSource
                        rValue = if (prefix) {
                            generateResolvedAccessExpression(source, resultVar)
                        } else {
                            resultInitializer
                        }
                        explicitReceiver = generateResolvedAccessExpression(argumentReceiverVariable.source, argumentReceiverVariable)
                        calleeReference = buildSimpleNamedReference {
                            source = firArgument.calleeReference.source
                            name = (firArgument.calleeReference as FirSimpleNamedReference).name
                        }
                    }
                }
            }

            if (prefix) {
                statements += resultVar
                appendAssignment()
                statements += generateResolvedAccessExpression(desugaredSource, resultVar)
            } else {
                statements += initialValueVar
                appendAssignment()
                statements += generateResolvedAccessExpression(desugaredSource, initialValueVar)
            }
        }
    }

    /**
     * given:
     * a[b, c]++
     *
     * result:
     * {
     *     val <array> = a
     *     val <index0> = b
     *     val <index1> = c
     *     val <unary> = <array>.get(b, c)
     *     <array>.set(b, c, <unary>.inc())
     *     ^<unary>
     * }
     *
     * given:
     * ++a[b, c]
     *
     * result:
     * {
     *     val <array> = a
     *     val <index0> = b
     *     val <index1> = c
     *     val <unary-result> = <array>.get(b, c).inc()
     *     <array>.set(b, c, <unary-result>)
     *     ^<unary-result>
     * }
     *
     */
    private fun generateIncrementOrDecrementBlockForArrayAccess(
        baseExpression: T,
        operationReference: T?,
        argument: T,
        callName: Name,
        prefix: Boolean,
        convert: T.() -> FirExpression
    ): FirExpression {
        return buildBlock {
            val baseSource = baseExpression?.toFirSourceElement()
            val desugaredSource = baseSource?.fakeElement(FirFakeSourceElementKind.DesugaredIncrementOrDecrement)
            source = desugaredSource

            val array = argument.arrayExpression
            val indices = argument.indexExpressions
            requireNotNull(indices) { "No indices in ${baseExpression.asText}" }

            val arrayVariable = generateTemporaryVariable(
                baseModuleData,
                array?.toFirSourceElement(),
                Name.special("<array>"),
                array?.convert() ?: buildErrorExpression {
                    source = argument.toFirSourceElement()
                    diagnostic = ConeSimpleDiagnostic("No array expression", DiagnosticKind.Syntax)
                }
            ).also { statements += it }

            val indexVariables = indices.mapIndexed { i, index ->
                generateTemporaryVariable(
                    baseModuleData,
                    index.toFirSourceElement(),
                    Name.special("<index$i>"),
                    index.convert()
                ).also { statements += it }
            }

            val firArgument = buildFunctionCall {
                source = desugaredSource
                calleeReference = buildSimpleNamedReference {
                    source = argument?.toFirSourceElement()
                    name = OperatorNameConventions.GET
                }
                explicitReceiver = generateResolvedAccessExpression(arrayVariable.source, arrayVariable)
                argumentList = buildArgumentList {
                    for (indexVar in indexVariables) {
                        arguments += generateResolvedAccessExpression(indexVar.source, indexVar)
                    }
                }
                origin = FirFunctionCallOrigin.Operator
            }

            // initialValueVar is only used for postfix increment/decrement (stores the argument value before increment/decrement).
            val initialValueVar = generateTemporaryVariable(
                baseModuleData,
                desugaredSource,
                SpecialNames.UNARY,
                firArgument
            )

            // resultInitializer is the expression for `argument.inc()`
            val resultInitializer = buildFunctionCall {
                source = desugaredSource
                calleeReference = buildSimpleNamedReference {
                    source = operationReference?.toFirSourceElement()
                    name = callName
                }
                explicitReceiver = if (prefix) {
                    firArgument
                } else {
                    generateResolvedAccessExpression(desugaredSource, initialValueVar)
                }
                origin = FirFunctionCallOrigin.Operator
            }

            // resultVar is only used for prefix increment/decrement.
            val resultVar = generateTemporaryVariable(
                baseModuleData,
                desugaredSource,
                Name.special("<unary-result>"),
                resultInitializer
            )

            fun appendAssignment() {
                statements += buildFunctionCall {
                    source = desugaredSource
                    calleeReference = buildSimpleNamedReference {
                        source = argument.toFirSourceElement()
                        name = OperatorNameConventions.SET
                    }
                    explicitReceiver = generateResolvedAccessExpression(arrayVariable.source, arrayVariable)
                    argumentList = buildArgumentList {
                        for (indexVar in indexVariables) {
                            arguments += generateResolvedAccessExpression(indexVar.source, indexVar)
                        }
                        arguments += if (prefix) {
                            generateResolvedAccessExpression(source, resultVar)
                        } else {
                            resultInitializer
                        }
                    }
                    origin = FirFunctionCallOrigin.Operator
                }
            }

            if (prefix) {
                statements += resultVar
                appendAssignment()
                statements += generateResolvedAccessExpression(desugaredSource, resultVar)
            } else {
                statements += initialValueVar
                appendAssignment()
                statements += generateResolvedAccessExpression(desugaredSource, initialValueVar)
            }
        }
    }

    private fun FirQualifiedAccessBuilder.initializeLValue(
        left: T?,
        convertQualified: T.() -> FirQualifiedAccess?
    ): FirReference {
        val tokenType = left?.elementType
        if (left != null) {
            when (tokenType) {
                REFERENCE_EXPRESSION -> {
                    return buildSimpleNamedReference {
                        source = left.toFirSourceElement()
                        name = left.getReferencedNameAsName()
                    }
                }
                THIS_EXPRESSION -> {
                    return buildExplicitThisReference {
                        source = left.toFirSourceElement()
                        labelName = left.getLabelName()
                    }
                }
                DOT_QUALIFIED_EXPRESSION, SAFE_ACCESS_EXPRESSION -> {
                    val firMemberAccess = left.convertQualified()
                    return if (firMemberAccess != null) {
                        explicitReceiver = firMemberAccess.explicitReceiver
                        firMemberAccess.calleeReference
                    } else {
                        buildErrorNamedReference {
                            source = left.toFirSourceElement()
                            diagnostic = ConeSimpleDiagnostic("Unsupported qualified LValue: ${left.asText}", DiagnosticKind.Syntax)
                        }
                    }
                }
            }
        }
        return buildErrorNamedReference {
            source = left?.toFirSourceElement()
            diagnostic = ConeSimpleDiagnostic("Unsupported LValue: $tokenType", DiagnosticKind.VariableExpected)
        }
    }

    fun T?.generateAssignment(
        baseSource: FirSourceElement?,
        rhs: T?,
        value: FirExpression, // value is FIR for rhs
        operation: FirOperation,
        convert: T.() -> FirExpression
    ): FirStatement {
        val unwrappedLhs = this.unwrap() ?: return buildErrorExpression {
            diagnostic = ConeSimpleDiagnostic("Inc/dec without operand", DiagnosticKind.Syntax)
        }

        val tokenType = unwrappedLhs.elementType
        if (tokenType == ARRAY_ACCESS_EXPRESSION) {
            if (operation == FirOperation.ASSIGN) {
                context.arraySetArgument[unwrappedLhs] = value
            }
            return if (operation == FirOperation.ASSIGN) {
                unwrappedLhs.convert()
            } else {
                generateAugmentedArraySetCall(unwrappedLhs, baseSource, operation, rhs, convert)
            }
        }

        if (operation in FirOperation.ASSIGNMENTS && operation != FirOperation.ASSIGN) {
            return buildAssignmentOperatorStatement {
                source = baseSource
                this.operation = operation
                leftArgument = withDefaultSourceElementKind(FirFakeSourceElementKind.DesugaredCompoundAssignment) {
                    this@generateAssignment?.convert()
                } ?: buildErrorExpression {
                    source = null
                    diagnostic = ConeSimpleDiagnostic(
                        "Unsupported left value of assignment: ${baseSource?.psi?.text}", DiagnosticKind.ExpressionExpected
                    )
                }
                rightArgument = value
            }
        }
        require(operation == FirOperation.ASSIGN)

        if (this?.elementType == SAFE_ACCESS_EXPRESSION && this != null) {
            val safeCallNonAssignment = convert() as? FirSafeCallExpression
            if (safeCallNonAssignment != null) {
                return putAssignmentToSafeCall(safeCallNonAssignment, baseSource, value)
            }
        }

        return buildVariableAssignment {
            source = baseSource
            rValue = value
            calleeReference = initializeLValue(unwrappedLhs) { convert() as? FirQualifiedAccess }
        }
    }

    // gets a?.{ $subj.x } and turns it to a?.{ $subj.x = v }
    private fun putAssignmentToSafeCall(
        safeCallNonAssignment: FirSafeCallExpression,
        baseSource: FirSourceElement?,
        value: FirExpression
    ): FirSafeCallExpression {
        val nestedAccess = safeCallNonAssignment.regularQualifiedAccess

        val assignment = buildVariableAssignment {
            source = baseSource
            rValue = value
            calleeReference = nestedAccess.calleeReference
            explicitReceiver = safeCallNonAssignment.checkedSubjectRef.value
        }

        safeCallNonAssignment.replaceRegularQualifiedAccess(
            assignment
        )

        return safeCallNonAssignment
    }

    private fun generateAugmentedArraySetCall(
        unwrappedReceiver: T,
        baseSource: FirSourceElement?,
        operation: FirOperation,
        rhs: T?,
        convert: T.() -> FirExpression
    ): FirStatement {
        return buildAugmentedArraySetCall {
            source = baseSource
            this.operation = operation
            assignCall = generateAugmentedCallForAugmentedArraySetCall(unwrappedReceiver, operation, rhs, convert)
            setGetBlock = generateSetGetBlockForAugmentedArraySetCall(unwrappedReceiver, baseSource, operation, rhs, convert)
        }
    }

    private fun generateAugmentedCallForAugmentedArraySetCall(
        unwrappedReceiver: T,
        operation: FirOperation,
        rhs: T?,
        convert: T.() -> FirExpression
    ): FirFunctionCall {
        /*
         * Desugarings of a[x, y] += z to
         * a.get(x, y).plusAssign(z)
         */
        return buildFunctionCall {
            calleeReference = buildSimpleNamedReference {
                name = FirOperationNameConventions.ASSIGNMENTS.getValue(operation)
            }
            explicitReceiver = unwrappedReceiver.convert()
            argumentList = buildArgumentList {
                arguments += rhs?.convert() ?: buildErrorExpression(
                    null,
                    ConeSimpleDiagnostic("No value for array set", DiagnosticKind.Syntax)
                )
            }
            origin = FirFunctionCallOrigin.Operator
        }
    }


    private fun generateSetGetBlockForAugmentedArraySetCall(
        unwrappedReceiver: T,
        baseSource: FirSourceElement?,
        operation: FirOperation,
        rhs: T?,
        convert: T.() -> FirExpression
    ): FirBlock {
        /*
         * Desugarings of a[x, y] += z to
         * {
         *     val tmp_a = a
         *     val tmp_x = x
         *     val tmp_y = y
         *     tmp_a.set(tmp_x, tmp_a.get(tmp_x, tmp_y).plus(z))
         * }
         */
        return buildBlock {
            val baseCall = unwrappedReceiver.convert() as FirFunctionCall

            val arrayVariable = generateTemporaryVariable(
                baseModuleData,
                source = null,
                specialName = "<array>",
                initializer = baseCall.explicitReceiver ?: buildErrorExpression {
                    source = baseSource
                    diagnostic = ConeSimpleDiagnostic("No receiver for array access", DiagnosticKind.Syntax)
                }
            )
            statements += arrayVariable
            val indexVariables = baseCall.arguments.mapIndexed { i, index ->
                generateTemporaryVariable(baseModuleData, source = null, specialName = "<index_$i>", initializer = index)
            }
            statements += indexVariables
            statements += buildFunctionCall {
                source = baseSource
                explicitReceiver = arrayVariable.toQualifiedAccess()
                calleeReference = buildSimpleNamedReference {
                    name = OperatorNameConventions.SET
                }
                origin = FirFunctionCallOrigin.Operator
                argumentList = buildArgumentList {
                    for (indexVariable in indexVariables) {
                        arguments += indexVariable.toQualifiedAccess()
                    }

                    val getCall = buildFunctionCall {
                        explicitReceiver = arrayVariable.toQualifiedAccess()
                        calleeReference = buildSimpleNamedReference {
                            name = OperatorNameConventions.GET
                        }
                        argumentList = buildArgumentList {
                            for (indexVariable in indexVariables) {
                                arguments += indexVariable.toQualifiedAccess()
                            }
                        }
                        origin = FirFunctionCallOrigin.Operator
                    }

                    val operatorCall = buildFunctionCall {
                        calleeReference = buildSimpleNamedReference {
                            name = FirOperationNameConventions.ASSIGNMENTS_TO_SIMPLE_OPERATOR.getValue(operation)
                        }
                        explicitReceiver = getCall
                        argumentList = buildArgumentList {
                            arguments += rhs?.convert() ?: buildErrorExpression(
                                null,
                                ConeSimpleDiagnostic(
                                    "No value for array set",
                                    DiagnosticKind.Syntax
                                )
                            )
                        }
                        origin = FirFunctionCallOrigin.Operator
                    }
                    arguments += operatorCall
                }
            }
        }
    }

    inner class DataClassMembersGenerator(
        private val source: T,
        private val classBuilder: FirRegularClassBuilder,
        private val zippedParameters: List<Pair<T, FirProperty>>,
        private val packageFqName: FqName,
        private val classFqName: FqName,
        private val createClassTypeRefWithSourceKind: (FirFakeSourceElementKind) -> FirTypeRef,
        private val createParameterTypeRefWithSourceKind: (FirProperty, FirFakeSourceElementKind) -> FirTypeRef,
    ) {
        fun generate() {
            generateComponentFunctions()
            generateCopyFunction()
            // Refer to (IR utils or FIR backend) DataClassMembersGenerator for generating equals, hashCode, and toString
        }

        private fun generateComponentAccess(
            parameterSource: FirSourceElement?,
            firProperty: FirProperty,
            classTypeRefWithCorrectSourceKind: FirTypeRef,
            firPropertyReturnTypeRefWithCorrectSourceKind: FirTypeRef
        ) =
             buildPropertyAccessExpression {
                source = parameterSource
                typeRef = firPropertyReturnTypeRefWithCorrectSourceKind
                dispatchReceiver = buildThisReceiverExpression {
                    source = parameterSource
                    calleeReference = buildImplicitThisReference {
                        boundSymbol = classBuilder.symbol
                    }
                    typeRef = classTypeRefWithCorrectSourceKind
                }
                calleeReference = buildResolvedNamedReference {
                    source = parameterSource
                    this.name = firProperty.name
                    resolvedSymbol = firProperty.symbol
                }
            }

        private fun generateComponentFunctions() {
            var componentIndex = 1
            for ((sourceNode, firProperty) in zippedParameters) {
                if (!firProperty.isVal && !firProperty.isVar) continue
                val name = Name.identifier("component$componentIndex")
                componentIndex++
                val parameterSource = sourceNode?.toFirSourceElement()
                val componentFunction = buildSimpleFunction {
                    source = parameterSource?.fakeElement(FirFakeSourceElementKind.DataClassGeneratedMembers)
                    moduleData = baseModuleData
                    origin = FirDeclarationOrigin.Source
                    returnTypeRef = firProperty.returnTypeRef
                    receiverTypeRef = null
                    this.name = name
                    status = FirDeclarationStatusImpl(Visibilities.Public, Modality.FINAL).apply {
                        isOperator = true
                    }
                    symbol = FirNamedFunctionSymbol(CallableId(packageFqName, classFqName, name))
                    dispatchReceiverType = currentDispatchReceiverType()
                    // Refer to FIR backend ClassMemberGenerator for body generation.
                }
                classBuilder.addDeclaration(componentFunction)
            }
        }

        private val copyName = Name.identifier("copy")

        private fun generateCopyFunction() {
            classBuilder.addDeclaration(
                buildSimpleFunction {
                    val classTypeRef = createClassTypeRefWithSourceKind(FirFakeSourceElementKind.DataClassGeneratedMembers)
                    source = this@DataClassMembersGenerator.source.toFirSourceElement(FirFakeSourceElementKind.DataClassGeneratedMembers)
                    moduleData = baseModuleData
                    origin = FirDeclarationOrigin.Source
                    returnTypeRef = classTypeRef
                    name = copyName
                    status = FirDeclarationStatusImpl(Visibilities.Public, Modality.FINAL)
                    symbol = FirNamedFunctionSymbol(CallableId(packageFqName, classFqName, copyName))
                    dispatchReceiverType = currentDispatchReceiverType()
                    for ((ktParameter, firProperty) in zippedParameters) {
                        val propertyName = firProperty.name
                        val parameterSource = ktParameter?.toFirSourceElement(FirFakeSourceElementKind.DataClassGeneratedMembers)
                        val propertyReturnTypeRef =
                            createParameterTypeRefWithSourceKind(firProperty, FirFakeSourceElementKind.DataClassGeneratedMembers)
                        valueParameters += buildValueParameter {
                            source = parameterSource
                            moduleData = baseModuleData
                            origin = FirDeclarationOrigin.Source
                            returnTypeRef = propertyReturnTypeRef
                            name = propertyName
                            symbol = FirValueParameterSymbol(propertyName)
                            defaultValue = generateComponentAccess(parameterSource, firProperty, classTypeRef, propertyReturnTypeRef)
                            isCrossinline = false
                            isNoinline = false
                            isVararg = false
                        }
                    }
                    // Refer to FIR backend ClassMemberGenerator for body generation.
                }
            )
        }
    }

    protected fun FirRegularClass.initContainingClassForLocalAttr() {
        if (isLocal) {
            val currentDispatchReceiverType = currentDispatchReceiverType()
            if (currentDispatchReceiverType != null) {
                containingClassForLocalAttr = currentDispatchReceiverType.lookupTag
            }
        }
    }

    protected fun FirCallableDeclaration.initContainingClassAttr() {
        initContainingClassAttr(context)
    }

    private fun FirVariable.toQualifiedAccess(): FirQualifiedAccessExpression = buildPropertyAccessExpression {
        calleeReference = buildResolvedNamedReference {
            source = this@toQualifiedAccess.source?.fakeElement(FirFakeSourceElementKind.ReferenceInAtomicQualifiedAccess)
            name = this@toQualifiedAccess.name
            resolvedSymbol = this@toQualifiedAccess.symbol
        }
    }

    protected inline fun <R> withDefaultSourceElementKind(newDefault: FirSourceElementKind, action: () -> R): R {
        val currentForced = context.forcedElementSourceKind
        context.forcedElementSourceKind = newDefault
        try {
            return action()
        } finally {
            context.forcedElementSourceKind = currentForced
        }
    }

    protected fun buildLabelAndErrorSource(rawName: String, source: FirSourceElement): Pair<FirLabel, FirSourceElement?> {
        val firLabel = buildLabel {
            name = KtPsiUtil.unquoteIdentifier(rawName)
            this.source = source
        }

        return Pair(firLabel, if (rawName.isUnderscore) firLabel.source else null)
    }

    protected fun buildExpressionWithErrorLabel(
        element: FirElement?,
        errorLabelSource: FirSourceElement?,
        elementSource: FirSourceElement
    ): FirElement {
        return if (element != null) {
            if (errorLabelSource != null) {
                buildErrorExpression {
                    this.source = element.source
                    this.expression = element as? FirExpression
                    diagnostic = ConeUnderscoreIsReserved(errorLabelSource)
                }
            } else {
                element
            }
        } else {
            buildErrorExpression(elementSource, ConeSimpleDiagnostic("Empty label", DiagnosticKind.Syntax))
        }
    }

    protected fun convertValueParameterName(
        safeName: Name,
        rawName: String?,
        valueParameterDeclaration: ValueParameterDeclaration
    ): Name {
        return if (valueParameterDeclaration == ValueParameterDeclaration.LAMBDA && rawName == "_"
            ||
            valueParameterDeclaration == ValueParameterDeclaration.CATCH &&
            safeName.asString() == "_" &&
            baseSession.safeLanguageVersionSettings?.supportsFeature(LanguageFeature.ForbidReferencingToUnderscoreNamedParameterOfCatchBlock) == true
        ) {
            SpecialNames.UNDERSCORE_FOR_UNUSED_VAR
        } else {
            safeName
        }
    }

    enum class ValueParameterDeclaration {
        OTHER,
        LAMBDA,
        CATCH,
        PRIMARY_CONSTRUCTOR,
    }
}
