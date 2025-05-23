/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi.stubs.elements

import com.intellij.lang.ASTNode
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.stubs.StubUtils

class KtStringTemplateExpressionElementType(@NonNls debugName: String) :
    KtPlaceHolderStubElementType<KtStringTemplateExpression>(debugName, KtStringTemplateExpression::class.java) {

    override fun shouldCreateStub(node: ASTNode): Boolean {
        if (!StubUtils.isDeclaredInsideValueArgument(node)) {
            return false
        }

        return super.shouldCreateStub(node)
    }
}
