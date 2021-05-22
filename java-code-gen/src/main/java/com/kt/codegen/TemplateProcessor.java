package com.kt.codegen;


import com.google.auto.service.AutoService;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.kt.codegen.CodeGenerationHelper.FIRST_UPPER;
import static com.kt.codegen.CodeGenerationHelper.FQ_TO_CLASS;
import static com.kt.codegen.CodeGenerationHelper.FQ_TO_PACKAGE;
import static com.kt.codegen.CodeGenerationHelper.SOURCE_CLASS_NAME_PLACEHOLDER;
import static com.kt.codegen.CodeGenerationHelper.findSourceDirectory;
import static com.kt.codegen.CodeGenerationHelper.indexOfRegex;
import static com.kt.codegen.CodeGenerationHelper.readSourceCode;
import static com.kt.codegen.CodeGenerationHelper.removeAnnotation;
import static com.kt.codegen.CodeGenerationHelper.removeImport;
import static com.kt.codegen.CodeGenerationHelper.replace;
import static com.kt.codegen.CodeGenerationHelper.skipBrackets;
import static com.kt.codegen.CodeGenerationHelper.writeFile;
import static java.util.stream.Collectors.joining;
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.NOTE;


@SupportedAnnotationTypes("com.kt.codegen.Template")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@AutoService(Processor.class)
public class TemplateProcessor extends AbstractProcessor {
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Messager messager = processingEnv.getMessager();

        for (Element element : roundEnv.getElementsAnnotatedWith(Template.class)) {
            try {
                process(element, messager);
            } catch (CodeGenerationException ex) {
                messager.printMessage(ERROR, ex.getMessage());
            }
        }
        return true;
    }

    private void process(Element element, Messager messager) throws CodeGenerationException {
        TypeElement sourceClass = (TypeElement) element;
        messager.printMessage(NOTE, "Instantiating class templates for " + sourceClass.getQualifiedName());
        Template template = sourceClass.getAnnotation(Template.class);

        // read source file
        Path sourceDir = findSourceDirectory(template.relativeSourceDir(), messager);
        String sourceCode = readSourceCode(sourceDir, sourceClass, messager);
        String fullyQualifiedSourceClassName = sourceClass.getQualifiedName().toString();

        // read desired concrete types and make sure they are consistent
        List<? extends TypeParameterElement> typeParameters = sourceClass.getTypeParameters();
        Instantiate[] instantiations = template.value();
        if (instantiations.length == 0) {
            throw new CodeGenerationException("Must provide at least one concrete type in types1");
        }

        // generate concrete template instantiation source files
        String[] typeParameterNames = typeParameters.stream().map(Object::toString).toArray(String[]::new);
        for (Instantiate instantiation : instantiations) {
            TypeMirror[] concreteTypes = getTypes(instantiation, messager);
            String[] concreteTypeNames = Stream.of(concreteTypes)
                                               .map(TypeMirror::toString)
                                               .map(FQ_TO_CLASS)
                                               .toArray(String[]::new);
            if (concreteTypeNames.length != typeParameters.size()) {
                throw new CodeGenerationException("Expected " + typeParameters.size() + " type parameters, got " + concreteTypes);
            }
            messager.printMessage(NOTE, "Instantiating " + sourceClass.getQualifiedName() + " for " + Arrays.toString(concreteTypeNames));
            String typeNames = Arrays.stream(concreteTypeNames)
                                     .map(FIRST_UPPER)
                                     .collect(joining(""));

            String fullyQualifiedConcreteClassName = template.append()
                    ? fullyQualifiedSourceClassName + typeNames
                    : FQ_TO_PACKAGE.apply(fullyQualifiedSourceClassName) + "." + typeNames + FQ_TO_CLASS.apply(fullyQualifiedSourceClassName);

            String targetCode = generateTarget(
                    fullyQualifiedSourceClassName,
                    fullyQualifiedConcreteClassName,
                    sourceCode,
                    typeParameterNames,
                    concreteTypeNames,
                    instantiation.replace());

            writeFile(targetCode, fullyQualifiedConcreteClassName, processingEnv);
        }
    }

    private static  TypeMirror[] getTypes(Instantiate instantiations, Messager messager) {
        // uses this trick:
        // https://stackoverflow.com/questions/7687829/java-6-annotation-processing-getting-a-class-from-an-annotation/52793839#52793839
        try {
            instantiations.value();
        } catch (MirroredTypesException mtex) {
            List<? extends TypeMirror> typeMirrors = mtex.getTypeMirrors();
            return typeMirrors.isEmpty() ? null : typeMirrors.toArray(TypeMirror[]::new);
        }
        throw new IllegalStateException("Cannot get to here");
    }

    private static String generateTarget(
            String fullyQualifiedSourceClassName,
            String fullyQualifiedConcreteClassName,
            String sourceCode,
            String[] typeParameterNames,
            String[] concreteTypes,
            Replace[] replacements) throws CodeGenerationException {
        assert typeParameterNames.length == concreteTypes.length;

        String sourceClassName = FQ_TO_CLASS.apply(fullyQualifiedSourceClassName);
        String targetClassName = FQ_TO_CLASS.apply(fullyQualifiedConcreteClassName);

        String targetCode = sourceCode;

        targetCode = replace(replacements, targetCode, fullyQualifiedSourceClassName);

        targetCode = removeImport(targetCode, Template.class.getName());
        targetCode = removeImport(targetCode, Instantiate.class.getName());
        targetCode = removeImport(targetCode, Replace.class.getName());

        targetCode = removeAnnotation(targetCode, Template.class, fullyQualifiedSourceClassName);

        targetCode = replaceGenericWithConcreteClassDeclaration(targetCode, sourceClassName, targetClassName, fullyQualifiedSourceClassName);
        for (int i = 0; i < typeParameterNames.length; i++) {
            String typeParam = typeParameterNames[i];
            String concreteType = concreteTypes[i];
            targetCode = targetCode.replaceAll("\\b" + FQ_TO_CLASS.apply(typeParam) + "\\b", concreteType);
        }

        targetCode = targetCode.replaceAll("\\b" + sourceClassName + "\\b", targetClassName);
        targetCode = "// generated from " + fullyQualifiedSourceClassName + "\n" + targetCode;

        targetCode = targetCode.replace(SOURCE_CLASS_NAME_PLACEHOLDER, fullyQualifiedSourceClassName);

        return targetCode;
    }

    private static String replaceGenericWithConcreteClassDeclaration(
            String code,
            String sourceClassName,
            String targetClassName,
            String fullyQualifiedSourceClassName) throws CodeGenerationException {
        String classDeclarationStartRegex = sourceClassName + "\\s*<";
        int annotationStartIndex = indexOfRegex(code, classDeclarationStartRegex);
        if (annotationStartIndex == -1) {
            throw new CodeGenerationException("Class declaration not found");
        }

        String targetClassDeclaration = targetClassName + "<";
        code = CodeGenerationHelper.replace(
            code,
            classDeclarationStartRegex,
            targetClassDeclaration,
            true,
            fullyQualifiedSourceClassName);
        int startIndex = code.indexOf(targetClassDeclaration);
        code = skipBrackets('<', '>', code, startIndex + targetClassDeclaration.length() - 1);
        return code;
    }
}