// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.core

import com.intellij.debugger.impl.DebuggerUtilsImpl.getLocalVariableBorders
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.search.GlobalSearchScope
import com.sun.jdi.LocalVariable
import com.sun.jdi.Location
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.base.indices.KotlinPackageIndexUtils.findFilesWithExactPackage
import org.jetbrains.kotlin.idea.base.projectStructure.scope.KotlinSourceFilterScope
import org.jetbrains.kotlin.idea.base.util.KOTLIN_FILE_EXTENSIONS
import org.jetbrains.kotlin.idea.debugger.base.util.FileApplicabilityChecker
import org.jetbrains.kotlin.idea.stubindex.KotlinFileFacadeFqNameIndex
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import java.util.*

object DebuggerUtils {
    @set:TestOnly
    var forceRanking = false

    private val IR_BACKEND_LAMBDA_REGEX = ".+\\\$lambda[$-]\\d+".toRegex()

    fun findSourceFileForClassIncludeLibrarySources(
        project: Project,
        scope: GlobalSearchScope,
        className: JvmClassName,
        fileName: String,
        location: Location? = null
    ): KtFile? {
        return runReadAction {
            findSourceFileForClass(
              project,
              listOf(scope, KotlinSourceFilterScope.librarySources(GlobalSearchScope.allScope(project), project)),
              className,
              fileName,
              location
            )
        }
    }

    fun findSourceFileForClass(
        project: Project,
        scopes: List<GlobalSearchScope>,
        className: JvmClassName,
        fileName: String,
        location: Location?
    ): KtFile? {
        if (!isKotlinSourceFile(fileName)) return null
        if (DumbService.getInstance(project).isDumb) return null

        val partFqName = className.fqNameForClassNameWithoutDollars

        for (scope in scopes) {
            val files = findFilesByNameInPackage(className, fileName, project, scope)
                .filter { it.platform.isJvm() || it.platform.isCommon() }

            if (files.isEmpty()) {
                continue
            }

            if (files.size == 1 && !forceRanking || location == null) {
                return files.first()
            }

            val singleFile = runReadAction {
                val matchingFiles = KotlinFileFacadeFqNameIndex[partFqName.asString(), project, scope]
                PackagePartClassUtils.getFilesWithCallables(matchingFiles).singleOrNull { it.name == fileName }
            }

            if (singleFile != null) {
                return singleFile
            }

            return chooseApplicableFile(files, location)
        }

        return null
    }

    private fun chooseApplicableFile(files: List<KtFile>, location: Location): KtFile {
        return if (Registry.`is`("kotlin.debugger.analysis.api.file.applicability.checker")) {
            FileApplicabilityChecker.chooseMostApplicableFile(files, location)
        } else {
            KotlinDebuggerLegacyFacade.getInstance()?.fileSelector?.chooseMostApplicableFile(files, location)
                ?: FileApplicabilityChecker.chooseMostApplicableFile(files, location)
        }
    }

    private fun findFilesByNameInPackage(
        className: JvmClassName,
        fileName: String,
        project: Project,
        searchScope: GlobalSearchScope
    ): List<KtFile> {
        val files = findFilesWithExactPackage(className.packageFqName, searchScope, project).filter { it.name == fileName }
        return files.sortedWith(JavaElementFinder.byClasspathComparator(searchScope))
    }

    fun isKotlinSourceFile(fileName: String): Boolean {
        val extension = FileUtilRt.getExtension(fileName).lowercase(Locale.getDefault())
        return extension in KOTLIN_FILE_EXTENSIONS
    }

    fun String.trimIfMangledInBytecode(isMangledInBytecode: Boolean): String =
        if (isMangledInBytecode)
            getMethodNameWithoutMangling()
        else
            this

    private fun String.getMethodNameWithoutMangling() =
        substringBefore('-')

    fun String.isGeneratedIrBackendLambdaMethodName() =
        matches(IR_BACKEND_LAMBDA_REGEX)

    fun LocalVariable.getBorders(): ClosedRange<Location>? {
        val range = getLocalVariableBorders(this) ?: return null
        return range.from..range.to
    }
}
