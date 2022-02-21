package com.github.andy2003.intellij.plugin.swagger_annotation_to_openapi.action

import com.github.andy2003.intellij.plugin.swagger_annotation_to_openapi.services.AnnotationConverterService
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore

class SwaggerAnnotationToOpenApiConverterAction : AnAction() {

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = getJavaFiles(event).isNotEmpty()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val action = { process(project, event) }
        ProgressManager
            .getInstance()
            .runProcessWithProgressSynchronously(action, "Converting Swagger to OpenAPI annotations", true, project)
    }

    private fun process(project: Project, event: AnActionEvent) {
        val indicator = ProgressManager.getInstance().progressIndicator
        indicator.isIndeterminate = false
        val psiManager = PsiManager.getInstance(project)

        val allJavaFiles = ReadAction.compute<Collection<PsiJavaFile>, Nothing> {
            getJavaFiles(event)
                .mapNotNull { psiManager.findFile(it) }
                .filterIsInstance<PsiJavaFile>()
        }

        allJavaFiles.forEachIndexed { index, file ->
            ProgressIndicatorProvider.checkCanceled()
            ReadAction.run<Nothing> { indicator.text2 = "Processing: ${file.name}" }

            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction
                    .writeCommandAction(project, file)
                    .withName("Convert Swagger to OpenAPI annotations")
                    .withGroupId(SwaggerAnnotationToOpenApiConverterAction::class.qualifiedName)
                    .run<Nothing> { AnnotationConverterService(project, file).handleFile() }
            }

            indicator.fraction = index / allJavaFiles.size.toDouble()
        }

    }

    private fun getJavaFiles(event: AnActionEvent): Collection<VirtualFile> {
        val project = event.project ?: return emptyList()
        val files: Array<VirtualFile> = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return emptyList()
        val scope: GlobalSearchScope = GlobalSearchScopesCore.directoriesScope(project, true, *files)
        return FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)
    }
}
