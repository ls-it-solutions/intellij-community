// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.base.codeInsight.handlers

import com.intellij.lang.SmartEnterProcessorWithFixers
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.TokenSet
import com.intellij.util.text.CharArrayUtil
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.codeInsight.handlers.fixers.*
import org.jetbrains.kotlin.idea.formatter.kotlinCommonSettings
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

class KotlinSmartEnterHandler : SmartEnterProcessorWithFixers() {
    init {
        val allFixers: List<Fixer<out SmartEnterProcessorWithFixers>> = LanguageSmartEnterProcessorFixer.allForLanguage(KotlinLanguage.INSTANCE)
        addFixers(*allFixers.toTypedArray<Fixer<out SmartEnterProcessorWithFixers>>())
        addEnterProcessors(KotlinPlainEnterProcessor())
    }

    override fun getStatementAtCaret(editor: Editor?, psiFile: PsiFile?): PsiElement? {
        var atCaret = super.getStatementAtCaret(editor, psiFile)

        if (atCaret is PsiWhiteSpace) return null

        while (atCaret != null) {
            when {
                atCaret.isKotlinStatement() -> return atCaret
                atCaret.parent is KtFunctionLiteral -> return atCaret
                atCaret is KtDeclaration -> {
                    val declaration = atCaret
                    when {
                        declaration is KtParameter && !declaration.isLambdaParameter -> {/* proceed to function declaration */
                        }
                        declaration.parent is KtForExpression -> {/* skip variable declaration in 'for' expression */
                        }
                        else -> return atCaret
                    }
                }
            }

            atCaret = atCaret.parent
        }

        return null
    }

    override fun moveCaretInsideBracesIfAny(editor: Editor, file: PsiFile) {
        var caretOffset = editor.caretModel.offset
        val chars = editor.document.charsSequence

        if (CharArrayUtil.regionMatches(chars, caretOffset, "{}")) {
            caretOffset += 2
        } else {
            if (CharArrayUtil.regionMatches(chars, caretOffset, "{\n}")) {
                caretOffset += 3
            }
        }

        caretOffset = CharArrayUtil.shiftBackward(chars, caretOffset - 1, " \t") + 1

        if (CharArrayUtil.regionMatches(chars, caretOffset - "{}".length, "{}") ||
            CharArrayUtil.regionMatches(chars, caretOffset - "{\n}".length, "{\n}")
        ) {
            commit(editor)
            val settings = file.kotlinCommonSettings
            val old = settings.KEEP_LINE_BREAKS
            settings.KEEP_LINE_BREAKS = true
            file.findElementAt(caretOffset - 1)?.getStrictParentOfType<KtBlockExpression>()?.let(::reformat)

            settings.KEEP_LINE_BREAKS = old
            editor.caretModel.moveToOffset(caretOffset - 1)
        }
    }

    private fun PsiElement.isKotlinStatement() = when {
        parent is KtBlockExpression && node?.elementType !in BRACES -> true
        parent?.node?.elementType in BRANCH_CONTAINERS && this !is KtBlockExpression -> true
        else -> false
    }

    class KotlinPlainEnterProcessor : FixEnterProcessor() {
        private fun getControlStatementBlock(caret: Int, element: PsiElement): KtExpression? {
            when (element) {
                is KtDeclarationWithBody -> return element.bodyExpression
                is KtIfExpression -> {
                    if (element.then.isWithCaret(caret)) return element.then
                    if (element.`else`.isWithCaret(caret)) return element.`else`
                }
                is KtLoopExpression -> return element.body
            }

            return null
        }

        override fun doEnter(atCaret: PsiElement, file: PsiFile?, editor: Editor, modified: Boolean): Boolean {
            if (modified && atCaret is KtCallExpression) return true

            val block = getControlStatementBlock(editor.caretModel.offset, atCaret) as? KtBlockExpression
            if (block != null) {
                val firstElement = block.firstChild?.nextSibling

                val offset = if (firstElement != null) {
                    firstElement.textRange!!.startOffset - 1
                } else {
                    block.textRange!!.endOffset
                }

                editor.caretModel.moveToOffset(offset)
            }

            plainEnter(editor)
            return true
        }
    }

    override fun processDefaultEnter(project: Project, editor: Editor, file: PsiFile) {
        plainEnter(editor)
    }
}

private val BRANCH_CONTAINERS = TokenSet.create(KtNodeTypes.THEN, KtNodeTypes.ELSE, KtNodeTypes.BODY)
private val BRACES = TokenSet.create(KtTokens.RBRACE, KtTokens.LBRACE)
