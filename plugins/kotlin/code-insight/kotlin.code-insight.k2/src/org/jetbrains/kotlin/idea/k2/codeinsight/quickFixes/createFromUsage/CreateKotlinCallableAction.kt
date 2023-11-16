// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.quickFixes.createFromUsage

import com.intellij.codeInsight.CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement
import com.intellij.codeInsight.daemon.QuickFixBundle.message
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.lang.jvm.JvmClass
import com.intellij.lang.jvm.actions.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.PsiType
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.core.TemplateKind
import org.jetbrains.kotlin.idea.core.getFunctionBodyTextFromTemplate
import org.jetbrains.kotlin.idea.createFromUsage.setupEditorSelection
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.createSmartPointer
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.Variance

internal class CreateKotlinCallableAction(
    override val request: CreateMethodRequest,
    private val targetClass: JvmClass,
    private val abstract: Boolean,
    private val needFunctionBody: Boolean,
    private val myText: String,
    private val pointerToContainer: SmartPsiElementPointer<*>,
) : CreateKotlinElementAction(request, pointerToContainer), JvmGroupIntentionAction {
    private val candidatesOfParameterNames: List<MutableCollection<String>> = request.expectedParameters.map { it.semanticNames }

    private val candidatesOfRenderedParameterTypes: List<List<String>> = renderCandidatesOfParameterTypes()

    private val candidatesOfRenderedReturnType: List<String> = renderCandidatesOfReturnType()

    private val containerClassFqName: FqName? = (getContainer() as? KtClassOrObject)?.fqName

    // Note that this variable must be initialized after initializing above variables, because it has dependency on them.
    private val callableDefinitionAsString = buildCallableAsString()

    override fun getActionGroup(): JvmActionGroup = if (abstract) CreateAbstractMethodActionGroup else CreateMethodActionGroup

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return super.isAvailable(project, editor, file) && PsiNameHelper.getInstance(project)
            .isIdentifier(request.methodName) && callableDefinitionAsString != null
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        return IntentionPreviewInfo.CustomDiff(KotlinFileType.INSTANCE, getContainerName(), "", callableDefinitionAsString ?: "")
    }

    override fun getRenderData() = JvmActionGroup.RenderData { request.methodName }

    override fun getTarget(): JvmClass = targetClass

    override fun getFamilyName(): String = message("create.method.from.usage.family")

    override fun getText(): String = myText

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        callableDefinitionAsString?.let { callableDefinition ->
            PsiEditor(
                project,
                editor,
                callableDefinition,
                pointerToContainer,
                candidatesOfParameterNames,
                candidatesOfRenderedParameterTypes,
                candidatesOfRenderedReturnType,
                containerClassFqName,
            ).execute()
        }
    }

    private fun getContainer(): KtElement? = pointerToContainer.element as? KtElement

    private fun renderCandidatesOfParameterTypes(): List<List<String>> {
        val container = getContainer() ?: return List(request.expectedParameters.size) { listOf("Any") }
        return analyze(container) {
            request.expectedParameters.map { expectedParameter ->
                expectedParameter.expectedTypes.map { it.render(container) }
            }
        }
    }

    private fun renderCandidatesOfReturnType(): List<String> {
        val container = getContainer() ?: return emptyList()
        return analyze(container) {
            request.returnType.mapNotNull { returnType ->
                val psiReturnType = returnType.theType as? PsiType
                psiReturnType?.asKtType(container)?.render(renderer = WITH_TYPE_NAMES_FOR_CREATE_ELEMENTS, position = Variance.INVARIANT)
            }
        }
    }

    context (KtAnalysisSession)
    private fun ExpectedType.render(container: KtElement): String {
        val parameterType = theType as? PsiType
        return if (parameterType?.isValid != true) {
            "Any"
        } else {
            val ktType = parameterType.asKtType(container)
            ktType?.render(renderer = WITH_TYPE_NAMES_FOR_CREATE_ELEMENTS, position = Variance.INVARIANT) ?: "Any"
        }
    }

    private fun buildCallableAsString(): String? {
        val container = pointerToContainer.element as? KtElement ?: return null
        val modifierListAsString = container.getModifierListAsString()
        return analyze(container) {
            buildString {
                append(modifierListAsString)
                if (abstract) append("abstract")
                if (isNotEmpty()) append(" ")
                append(KtTokens.FUN_KEYWORD)
                append(" ")
                append(request.methodName)
                append("(")
                append(renderParameterList())
                append(")")
                candidatesOfRenderedReturnType.firstOrNull()?.let { append(": $it") }
                if (needFunctionBody) append(" {}")
            }
        }
    }

    private fun KtElement.getModifierListAsString(): String =
        KotlinModifierBuilder(this).apply { addJvmModifiers(request.modifiers) }.modifierList.text

    private fun renderParameterList(): String {
        assert(candidatesOfParameterNames.size == candidatesOfRenderedParameterTypes.size)
        return candidatesOfParameterNames.mapIndexed { index, candidates ->
            val candidatesOfTypes = candidatesOfRenderedParameterTypes[index]
            "${candidates.firstOrNull() ?: "p$index"}: ${candidatesOfTypes.firstOrNull() ?: "Any"}"
        }.joinToString()
    }

    private fun getContainerName(): String = getContainer()?.let { container ->
        when (container) {
            is KtClassOrObject -> container.name
            is KtFile -> container.name
            else -> null
        }
    } ?: ""
}

private class PsiEditor(
    private val project: Project,
    private val editor: Editor?,
    private val definitionAsString: String,
    private val pointerToContainer: SmartPsiElementPointer<*>,
    private val candidatesOfParameterNames: List<MutableCollection<String>>,
    private val candidatesOfRenderedParameterTypes: List<List<String>>,
    private val candidatesOfRenderedReturnType: List<String>,
    private val containerClassFqName: FqName?,
) {
    fun execute() {
        val factory = KtPsiFactory(project)
        var function = factory.createFunction(definitionAsString)
        function = pointerToContainer.element?.let { function.addToContainer(it) } as? KtNamedFunction ?: return
        function = forcePsiPostprocessAndRestoreElement(function) ?: return
        editor?.document?.let { document -> runTemplate(editor, document, function) }
    }

    private fun moveCaretToCallable(editor: Editor, function: KtCallableDeclaration) {
        val caretModel = editor.caretModel
        caretModel.moveToOffset(function.startOffset)
    }

    private fun getDocumentManager() = PsiDocumentManager.getInstance(project)

    private fun runTemplate(editor: Editor, document: Document, function: KtNamedFunction) {
        val file = function.containingKtFile
        val functionMarker = document.createRangeMarker(function.textRange)
        getDocumentManager().doPostponedOperationsAndUnblockDocument(document)
        moveCaretToCallable(editor, function)
        val templateImpl = setupTemplate(function)
        TemplateManager.getInstance(project)
            .startTemplate(editor, templateImpl, buildTemplateListener(editor, file, document, functionMarker))
    }

    private fun setupTemplate(function: KtNamedFunction): TemplateImpl {
        val builder = TemplateBuilderImpl(function)
        function.valueParameters.forEachIndexed { index, parameter -> builder.setupParameter(index, parameter) }
        val returnType = function.typeReference
        if (returnType != null) builder.replaceElement(returnType, ExpressionForCreateCallable(candidatesOfRenderedReturnType))
        return builder.buildInlineTemplate() as TemplateImpl
    }

    private fun TemplateBuilderImpl.setupParameter(parameterIndex: Int, parameter: KtParameter) {
        val nameIdentifier = parameter.nameIdentifier ?: return
        replaceElement(nameIdentifier, ParameterNameExpression(parameterIndex, candidatesOfParameterNames[parameterIndex].toList()))
        val parameterTypeElement = parameter.typeReference ?: return
        replaceElement(parameterTypeElement, ExpressionForCreateCallable(candidatesOfRenderedParameterTypes[parameterIndex]))
    }

    private fun KtElement.addToContainer(container: PsiElement): PsiElement = when (container) {
        is KtClassOrObject -> {
            val classBody = container.getOrCreateBody()
            classBody.addBefore(this, classBody.rBrace)
        }

        else -> container.add(this)
    }

    private fun buildTemplateListener(editor: Editor, file: KtFile, document: Document, functionMarker: RangeMarker): TemplateEditingAdapter {
        return object : TemplateEditingAdapter() {
            private fun finishTemplate(brokenOff: Boolean) {
                getDocumentManager().commitDocument(document)
                if (brokenOff && !isUnitTestMode()) return
                val pointerToNewCallable = getPointerToNewCallable() ?: return
                WriteCommandAction.writeCommandAction(project).run<Throwable> {
                    val newCallable = pointerToNewCallable.element ?: return@run
                    when (newCallable) {
                        is KtNamedFunction -> setupDeclarationBody(newCallable)
                        else -> TODO("Handle other cases.")
                    }
                    CodeStyleManager.getInstance(project).reformat(newCallable)
                    setupEditorSelection(editor, newCallable)
                }
            }

            private fun getPointerToNewCallable() = PsiTreeUtil.findElementOfClassAtOffset(
                file,
                functionMarker.startOffset,
                KtCallableDeclaration::class.java,
                false
            )?.createSmartPointer()

            private fun setupDeclarationBody(func: KtDeclarationWithBody) {
                if (func !is KtNamedFunction && func !is KtPropertyAccessor) return
                val oldBody = func.bodyExpression ?: return
                val bodyText = getFunctionBodyTextFromTemplate(
                    func.project, TemplateKind.FUNCTION, func.name, (func as? KtFunction)?.typeReference?.text ?: "", containerClassFqName
                )
                oldBody.replace(KtPsiFactory(func.project).createBlock(bodyText))
            }

            override fun templateCancelled(template: Template?) {
                finishTemplate(true)
            }

            override fun templateFinished(template: Template, brokenOff: Boolean) {
                finishTemplate(brokenOff)
            }
        }
    }
}