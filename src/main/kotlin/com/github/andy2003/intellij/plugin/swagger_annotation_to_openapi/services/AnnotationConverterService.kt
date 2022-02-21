package com.github.andy2003.intellij.plugin.swagger_annotation_to_openapi.services

import com.intellij.codeInsight.generation.ConstructorBodyGenerator
import com.intellij.codeInsight.generation.GenerateConstructorHandler
import com.intellij.codeInsight.generation.GenerateMembersUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.impl.source.PsiImmediateClassType
import com.intellij.psi.impl.source.tree.java.PsiLiteralExpressionImpl
import com.jetbrains.rd.util.first

class AnnotationConverterService(private val project: Project, val file: PsiJavaFile) {

    private val elementFactory = JavaPsiFacade.getInstance(project).elementFactory
    private val newAnnotations = mutableListOf<PsiAnnotation>()
    private val psiParser = PsiParserFacade.SERVICE.getInstance(project);

    fun handleFile() {
        file.classes.forEach { handleClass(it) }

        if (newAnnotations.isNotEmpty()) {
            val javaCodeStyleManager = JavaCodeStyleManager.getInstance(project)
            val codeStylist = CodeStyleManager.getInstance(project)
            newAnnotations.forEach {
                val reformat = codeStylist.reformat(it)
                it.replace(javaCodeStyleManager.shortenClassReferences(reformat))
            }
            javaCodeStyleManager.removeRedundantImports(file)
        }
    }

    private fun handleClass(psiClass: PsiClass) {
        convertApiAnnotation(psiClass)
        psiClass.methods.forEach { handleMethod(it) }
        psiClass.fields.forEach { handleField(it) }

        psiClass.innerClasses.forEach { handleClass(it) }
    }

    private fun handleMethod(method: PsiMethod) {
        convertApiOperatorAnnotation(method)
        convertApiResponsesAnnotation(method)
        convertApiModelAnnotation(method.modifierList)
        convertApiParamAnnotation(method.modifierList)

        method.parameterList.parameters.forEach { parameter -> handleMethodParameter(parameter) }
    }

    private fun handleField(field: PsiField) {
        val psiModifierList = field.modifierList ?: return
        convertApiModelAnnotation(psiModifierList)
        convertApiParamAnnotation(psiModifierList)
    }

    private fun convertApiModelAnnotation(psiModifierList: PsiModifierList) {
        val swaggerAnnotation =
                psiModifierList.annotations.find { it.qualifiedName == "io.swagger.annotations.ApiModelProperty" }
                        ?: return

        val inserted =
                psiModifierList.addAnnotation(swaggerAnnotation, "io.swagger.v3.oas.annotations.media.Schema")
        swaggerAnnotation.parameterList.attributes.forEach { attribute ->
            val value = attribute.value
            when (attribute.name) {
                "name" -> inserted.setDeclaredAttributeValue("name", value)
                "value", null -> inserted.setDeclaredAttributeValue("description", value)
                "required" -> inserted.setDeclaredAttributeValue("required", value)
                "dataType" -> inserted.setDeclaredAttributeValue("type", value)
                "allowableValues" -> {
                    val allowableValues = ((value as PsiLiteralExpression).value as String).split(Regex("\\s*,\\s*"))
                    val array =
                            elementFactory.createFieldFromText(
                                    allowableValues.joinToString(",", "String[] a = {", "}") { "\"$it\"" }, null
                            ).initializer
                    inserted.setDeclaredAttributeValue("allowableValues", array)
                }
                "example" -> inserted.setDeclaredAttributeValue("example", value)
                else -> {
                    TODO("not implemented")
                }
            }
        }
        swaggerAnnotation.delete()
    }

    private fun convertApiParamAnnotation(psiModifierList: PsiModifierList) {
        val swaggerAnnotation =
                psiModifierList.annotations.find { it.qualifiedName == "io.swagger.annotations.ApiParam" } ?: return

        val inserted =
                psiModifierList.addAnnotation(swaggerAnnotation, "io.swagger.v3.oas.annotations.Parameter")
        val schema = elementFactory.createAnnotationFromText("@io.swagger.v3.oas.annotations.media.Schema", null)
        var hasSchema = false
        swaggerAnnotation.parameterList.attributes.forEach { attribute ->
            val value = attribute.value
            when (attribute.name) {
                "name" -> inserted.setDeclaredAttributeValue("name", value)
                "required" -> inserted.setDeclaredAttributeValue("required", value)
                "value", null -> inserted.setDeclaredAttributeValue("description", value)
                "hidden" -> inserted.setDeclaredAttributeValue("hidden", value)
                "example" -> inserted.setDeclaredAttributeValue("example", value)
                "defaultValue" -> {
                    schema.setDeclaredAttributeValue("defaultValue", value)
                    hasSchema = true
                }
                "allowableValues" -> {
                    val allowableValues = ((value as PsiLiteralExpression).value as String).split(Regex("\\s*,\\s*"))
                    val array =
                            elementFactory.createFieldFromText(
                                    allowableValues.joinToString(",", "String[] a = {", "}") { "\"$it\"" }, null
                            ).initializer
                    schema.setDeclaredAttributeValue("allowableValues", array)
                    hasSchema = true
                }
                else -> {
                    TODO("not implemented")
                }
            }
        }
        if (hasSchema) {
            inserted.setDeclaredAttributeValue("schema", schema)
            newAnnotations.add(schema)
        }
        swaggerAnnotation.delete()
    }

    private fun handleMethodParameter(parameter: PsiParameter) {
        val psiModifierList = parameter.modifierList ?: return
        convertApiParamAnnotation(psiModifierList)
    }

    private fun convertApiAnnotation(psiClass: PsiClass) {
        val modifierList = psiClass.modifierList ?: return
        val swaggerAnnotation =
                modifierList.annotations.find { it.qualifiedName == "io.swagger.annotations.Api" } ?: return

        fun addTagAnnotation(name: PsiAnnotationMemberValue): PsiAnnotation {
            return modifierList.addAnnotation(swaggerAnnotation, "io.swagger.v3.oas.annotations.tags.Tag")
                    .also { it.setDeclaredAttributeValue("name", name) }
        }

        swaggerAnnotation.parameterList.attributes.forEach { attribute ->
            val value = attribute.value
            when (attribute.name) {
                "tags" -> {
                    when (value) {
                        is PsiArrayInitializerMemberValue -> value.initializers.forEach { addTagAnnotation(it) }
                        is PsiLiteralExpression -> addTagAnnotation(value)
                        else -> TODO("implement")
                    }
                }
                "hidden" -> if ((value as PsiLiteralExpression).value == true) {
                    modifierList.addAnnotation(swaggerAnnotation, "io.swagger.v3.oas.annotations.Hidden")
                }
                else -> {
                    TODO("not implemented")
                }
            }
        }
        // delete old annotation
        swaggerAnnotation.delete()
    }

    private fun convertApiOperatorAnnotation(method: PsiMethod) {
        val modifierList = method.modifierList
        val swaggerAnnotation =
                modifierList.annotations.find { it.qualifiedName == "io.swagger.annotations.ApiOperation" }
                        ?: return

        val inserted = modifierList.addAnnotation(swaggerAnnotation, "io.swagger.v3.oas.annotations.Operation")
        swaggerAnnotation.parameterList.attributes.forEach { attribute ->
            val value = attribute.value
            when (attribute.name) {
                "value", null -> inserted.setDeclaredAttributeValue("summary", value)
                "notes" -> inserted.setDeclaredAttributeValue("description", value)
                "nickname" -> inserted.setDeclaredAttributeValue("operationId", value)
                "tags" -> inserted.setDeclaredAttributeValue("tags", value)
                else -> {
                    TODO("not implemented")
                }
            }
        }
//        val length = inserted.endOffset - inserted.startOffset
//        if (length > 140) {
//            wrapAttributes(inserted)
//        }
        // delete old annotation
        swaggerAnnotation.delete()
    }

    private fun wrapAttributes(inserted: PsiAnnotation) {
        inserted.parameterList.attributes.forEach {
            val ws = psiParser.createWhiteSpaceFromText("\n")
            it.addBefore(ws, it)
        }
        inserted.parameterList.add(psiParser.createWhiteSpaceFromText("\n"))
    }

    private fun convertApiResponsesAnnotation(method: PsiMethod) {
        val modifierList = method.modifierList
        val swaggerAnnotation =
                modifierList.annotations.find { it.qualifiedName == "io.swagger.annotations.ApiResponses" }
                        ?: return
        val values = swaggerAnnotation.parameterList.attributes.first()?.value ?: return
        if (values !is PsiArrayInitializerMemberValue) {
            TODO("implement")
        }
        var okResponse: PsiAnnotation? = null
        values.initializers.filterIsInstance<PsiAnnotation>().forEach { swaggerResponse ->
            val inserted =
                    modifierList.addAnnotation(swaggerAnnotation, "io.swagger.v3.oas.annotations.responses.ApiResponse")

            swaggerResponse.parameterList.attributes.forEach { attribute ->
                val value = attribute.value
                when (attribute.name) {
                    "code" -> {
                        val code = value?.text!!
                        val valueAsString =
                                elementFactory.createExpressionFromText("\"$code\"", null)
                        inserted.setDeclaredAttributeValue("responseCode", valueAsString)
                        if (code == "200") {
                            okResponse = inserted
                        }
                    }
                    "message" -> inserted.setDeclaredAttributeValue("description", value)
                    "response" -> {
                        val responseContainer =
                                (swaggerResponse.parameterList.attributes.find { it.name == "responseContainer" }
                                        ?.value as? PsiLiteralExpressionImpl)
                                        ?.value

                        val schema = elementFactory.createAnnotationFromText(
                                "@io.swagger.v3.oas.annotations.media.Schema",
                                null
                        )
                                .also {
                                    it.setDeclaredAttributeValue("implementation", makeGenericConcrete(method, value))
                                    newAnnotations.add(it)
                                }

                        val content = elementFactory.createAnnotationFromText(
                                "@io.swagger.v3.oas.annotations.media.Content",
                                null
                        )
                                .also { content ->
                                    val mediaType = method.getAnnotation("javax.ws.rs.Produces")
                                            ?.parameterList?.attributes?.first()?.value

                                    if (mediaType != null) {
                                        content.setDeclaredAttributeValue("mediaType", mediaType)
                                    }
                                    when (responseContainer) {
                                        "List", "Set" -> {
                                            val arraySchema = elementFactory.createAnnotationFromText(
                                                    "@io.swagger.v3.oas.annotations.media.ArraySchema",
                                                    null
                                            )
                                                    .also {
                                                        it.setDeclaredAttributeValue("schema", schema)
                                                        if (responseContainer == "Set") {
                                                            it.setDeclaredAttributeValue(
                                                                    "uniqueItems",
                                                                    elementFactory.createExpressionFromText("true", null)
                                                            )
                                                        }
                                                        newAnnotations.add(it)
                                                    }
                                            content.setDeclaredAttributeValue("array", arraySchema)
//                                            wrapAttributes(content)
                                        }
                                        "Map" -> {
                                            inserted.add(
                                                    elementFactory.createCommentFromText(
                                                            "/* TODO transform to map */",
                                                            null
                                                    )
                                            )
                                            content.setDeclaredAttributeValue("schema", schema)
                                        }
                                        null -> {
                                            content.setDeclaredAttributeValue("schema", schema)
                                        }
                                        else -> TODO("implement")
                                    }
                                    newAnnotations.add(content)
                                }

                        inserted.setDeclaredAttributeValue("content", content)
                    }
                    "responseContainer" -> {}
                    else -> {
                        TODO("not implemented")
                    }
                }
            }
//            val length = inserted.endOffset - inserted.startOffset
//            if (length > 140) {
//                wrapAttributes(inserted)
//            }
        }
        okResponse
                ?.takeIf {
                    return@takeIf when (val content = it.findAttributeValue("content")) {
                        is PsiArrayInitializerMemberValue -> content.initializers.isNullOrEmpty()
                        is PsiAnnotation -> false
                        else -> TODO("implement")
                    }
                }
                ?.let {
                    val content = elementFactory.createAnnotationFromText(
                            "@io.swagger.v3.oas.annotations.media.Content",
                            null
                    )
                            .also { content ->
                                val mediaType = method.getAnnotation("javax.ws.rs.Produces")
                                        ?.parameterList?.attributes?.first()?.value

                                if (mediaType != null) {
                                    content.setDeclaredAttributeValue("mediaType", mediaType)
                                }
                                newAnnotations.add(content)


                                val schema = elementFactory.createAnnotationFromText(
                                        "@io.swagger.v3.oas.annotations.media.Schema",
                                        null
                                )
                                        .also { schema ->
                                            if (((method.returnType as? PsiClassType)?.resolveType()?.declaration as? PsiClass)?.qualifiedName == "java.util.List") {
                                                val inner = (method.returnType as? PsiClassType)?.resolveGenerics()?.substitutor?.substitutionMap?.first()?.value?.canonicalText
                                                schema.setDeclaredAttributeValue("implementation", elementFactory.createExpressionFromText(inner + ".class", null))
                                                val arraySchema = elementFactory.createAnnotationFromText(
                                                        "@io.swagger.v3.oas.annotations.media.ArraySchema",
                                                        null
                                                )
                                                        .also { arrayAnnotation ->
                                                            arrayAnnotation.setDeclaredAttributeValue("schema", schema)
                                                            newAnnotations.add(arrayAnnotation)
                                                        }
                                                content.setDeclaredAttributeValue("array", arraySchema)
                                            } else {
                                                val v = makeGenericConcrete(method)
                                                if (v == null) {
                                                    method.addBefore(elementFactory.createCommentFromText("/* TODO check generics */", null), null)
                                                }
                                                schema.setDeclaredAttributeValue("implementation", v)
                                                content.setDeclaredAttributeValue("schema", schema)
                                            }
                                            newAnnotations.add(schema)
                                        }
                            }
                    it.setDeclaredAttributeValue("content", content)
                }
        swaggerAnnotation.delete()
    }


    private fun makeGenericConcrete(method: PsiMethod, value: PsiAnnotationMemberValue?): PsiAnnotationMemberValue? {

        val swaggerType = ((value as? PsiClassObjectAccessExpression)?.type as? PsiImmediateClassType)
                ?.resolveGenerics()?.substitutor?.substitutionMap?.first()?.value as? PsiClassType ?: return value
        val genericParameter = swaggerType.resolveGenerics().substitutor.substitutionMap
        if (genericParameter.isEmpty()) {
            return value
        }

        // a class with generic parameters was used as swagger type, this will not resolve correctly, so we need to
        // extend the generic class to make it concrete

        val returnClassType = method.returnType as PsiClassType
        val resolvedType = returnClassType.resolve()
        if (swaggerType.resolve()?.qualifiedName == resolvedType?.qualifiedName) {
            val v = makeGenericConcrete(method)
            if (v != null) {
                return v
            }
        }
        method.addBefore(elementFactory.createCommentFromText("/* TODO check generics */", null), null)
        return value
    }

    private fun makeGenericConcrete(method: PsiMethod): PsiAnnotationMemberValue? {
        val returnClassType = method.returnType as? PsiClassType ?: return null

        val genericParameter = returnClassType.resolveGenerics().substitutor.substitutionMap
        if (genericParameter.isEmpty()) {
            return null
        }

        val resolvedType = returnClassType.resolve() ?: return null

        var name = returnClassType.resolveGenerics().substitutor.substitutionMap.values
                .filterIsInstance<PsiClassType>().joinToString("") { it.name }
        name += resolvedType.name

        val createClass = elementFactory.createClass(name)
        createClass.modifierList?.setModifierProperty("static", true)
        createClass.extendsList?.add(returnClassType.psiContext!!)

        method.add(psiParser.createWhiteSpaceFromText("\n"))
        method.add(elementFactory.createCommentFromText("// TODO externalize", null))

        resolvedType.constructors.forEach {
            val derived = GenerateMembersUtil.substituteGenericMethod(it, returnClassType.resolveGenerics().substitutor, createClass)
            val generator = ConstructorBodyGenerator.INSTANCE.forLanguage(derived.language)
            if (generator != null) {
                val buffer = StringBuilder()
                generator.start(buffer, derived.name, PsiParameter.EMPTY_ARRAY)
                generator.generateSuperCallIfNeeded(buffer, derived.parameterList.parameters)
                generator.finish(buffer)
                val stub: PsiMethod = elementFactory.createMethodFromText(buffer.toString(), createClass)
                derived.body!!.replace(stub.body!!)
            }
            createClass.add(derived)
        }
        method.add(createClass)
        return elementFactory.createExpressionFromText(createClass.qualifiedName + ".class", null)
    }

    private fun PsiModifierList.addAnnotation(anchor: PsiElement, qualifiedName: String): PsiAnnotation {
        val annotation = addBefore(
                elementFactory.createAnnotationFromText("@$qualifiedName", this),
                anchor
        ) as PsiAnnotation
        newAnnotations.add(annotation)
        return annotation
    }
}
