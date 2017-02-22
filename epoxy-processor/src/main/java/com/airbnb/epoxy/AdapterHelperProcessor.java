package com.airbnb.epoxy;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.MethodSpec.Builder;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

import static com.airbnb.epoxy.ProcessorUtils.EPOXY_DIFF_ADAPTER_TYPE;
import static com.airbnb.epoxy.ProcessorUtils.EPOXY_MODEL_TYPE;
import static com.airbnb.epoxy.ProcessorUtils.isDiffAdapter;
import static com.airbnb.epoxy.ProcessorUtils.isEpoxyModel;
import static com.airbnb.epoxy.ProcessorUtils.validateFieldAccessibleViaGeneratedCode;

class AdapterHelperProcessor {
  private static final String ADAPTER_HELPER_INTERFACE = "com.airbnb.epoxy.AdapterHelper";
  private Filer filer;
  private Elements elementUtils;
  private ErrorLogger errorLogger;

  AdapterHelperProcessor(Filer filer, Elements elementUtils,
      ErrorLogger errorLogger) {
    this.filer = filer;
    this.elementUtils = elementUtils;
    this.errorLogger = errorLogger;
  }

  void process(RoundEnvironment roundEnv, List<ClassToGenerateInfo> generatedModels) {
    LinkedHashMap<TypeElement, AdapterClassInfo> adapterClassMap = new LinkedHashMap<>();

    for (Element modelFieldElement : roundEnv.getElementsAnnotatedWith(AutoModel.class)) {
      try {
        addFieldToAdapterClass(modelFieldElement, adapterClassMap);
      } catch (Exception e) {
        errorLogger.logError(e);
      }
    }

    resolveGeneratedModelNames(adapterClassMap, generatedModels);

    generateJava(adapterClassMap);
  }

  /**
   * Models in the same module as the adapter they are used in will be processed at the same time,
   * so the generated class won't yet exist. This means that we don't have any type information for
   * the generated model and can't correctly import it in the generated helper class. We can resolve
   * the FQN by looking at what models were already generated and finding matching names.
   *
   * @param generatedModels Information about the already generated models. Relies on the model
   *                        processor running first and passing us this information.
   */
  private void resolveGeneratedModelNames(
      LinkedHashMap<TypeElement, AdapterClassInfo> adapterClassMap,
      List<ClassToGenerateInfo> generatedModels) {

    for (AdapterClassInfo adapterClassInfo : adapterClassMap.values()) {
      for (AdapterModelField model : adapterClassInfo.models) {
        if (!hasFullyQualifiedName(model)) {
          model.typeName = getFullyQualifiedModelTypeName(model, generatedModels);
        }
      }
    }
  }

  /**
   * It will have a FQN if it is from a separate library and was already compiled, otherwise if it
   * is from this module we will just have the simple name.
   */
  private boolean hasFullyQualifiedName(AdapterModelField model) {
    return model.typeName.toString().contains(".");
  }

  /**
   * Returns the ClassType of the given model by finding a match in the list of generated models. If
   * no match is found the original model type is returned as a fallback.
   */
  private TypeName getFullyQualifiedModelTypeName(AdapterModelField model,
      List<ClassToGenerateInfo> generatedModels) {
    String modelName = model.typeName.toString();
    for (ClassToGenerateInfo generatedModel : generatedModels) {
      String generatedName = generatedModel.getGeneratedName().toString();
      if (generatedName.endsWith("." + modelName)) {
        return generatedModel.getGeneratedName();
      }
    }

    // Fallback to using the same name
    return model.typeName;
  }

  private void addFieldToAdapterClass(Element modelField,
      LinkedHashMap<TypeElement, AdapterClassInfo> adapterClassMap) {
    TypeElement adapterClassElement = (TypeElement) modelField.getEnclosingElement();
    AdapterClassInfo adapterClass = getOrCreateTargetClass(adapterClassMap, adapterClassElement);
    adapterClass.addModel(buildFieldInfo(modelField));
  }

  private AdapterModelField buildFieldInfo(Element modelFieldElement) {
    validateFieldAccessibleViaGeneratedCode(modelFieldElement, AutoModel.class, errorLogger);

    // TODO: (eli_hart 2/21/17) Test this
    if (!isEpoxyModel(modelFieldElement.asType())) {
      errorLogger.logError("Class with %s annotations must extend %s (%s)",
          AutoModel.class.getSimpleName(), EPOXY_MODEL_TYPE,
          modelFieldElement.getSimpleName());
    }

    return new AdapterModelField(modelFieldElement);
  }

  private AdapterClassInfo getOrCreateTargetClass(
      Map<TypeElement, AdapterClassInfo> adapterClassMap, TypeElement adapterClassElement) {

    if (!isDiffAdapter(adapterClassElement)) {
      errorLogger.logError("Class with %s annotations must extend %s (%s)",
          AutoModel.class.getSimpleName(), EPOXY_DIFF_ADAPTER_TYPE,
          adapterClassElement.getSimpleName());
    }

    AdapterClassInfo adapterClassInfo = adapterClassMap.get(adapterClassElement);

    if (adapterClassInfo == null) {
      adapterClassInfo = new AdapterClassInfo(elementUtils, adapterClassElement);
      adapterClassMap.put(adapterClassElement, adapterClassInfo);
    }

    return adapterClassInfo;
  }

  private void generateJava(LinkedHashMap<TypeElement, AdapterClassInfo> adapterClassMap) {
    for (Entry<TypeElement, AdapterClassInfo> adapterInfo : adapterClassMap.entrySet()) {
      try {
        generateHelperClassForAdapter(adapterInfo.getValue());
      } catch (Exception e) {
        errorLogger.logError(e);
      }
    }
  }

  private void generateHelperClassForAdapter(AdapterClassInfo adapterInfo) throws IOException {
    ClassName superclass = ClassName.get(elementUtils.getTypeElement(ADAPTER_HELPER_INTERFACE));
    ParameterizedTypeName parameterizedSuperClass =
        ParameterizedTypeName.get(superclass, adapterInfo.adapterClassType);

    // TODO: (eli_hart 2/21/17) Have constructor validate that fields are null

    TypeSpec generatedClass = TypeSpec.classBuilder(adapterInfo.generatedClassName)
        .addJavadoc("Generated file. Do not modify!")
        .addModifiers(Modifier.PUBLIC)
        .superclass(parameterizedSuperClass)
        .addMethod(buildModelsMethod(adapterInfo))
        .build();

    JavaFile.builder(adapterInfo.generatedClassName.packageName(), generatedClass)
        .build()
        .writeTo(filer);
  }

  private MethodSpec buildModelsMethod(AdapterClassInfo adapterInfo) {
    ParameterSpec adapterParam = ParameterSpec
        .builder(adapterInfo.adapterClassType, "adapter")
        .build();

    Builder builder = MethodSpec.methodBuilder("buildAutoModels")
        .addAnnotation(Override.class)
        .addParameter(adapterParam)
        .addModifiers(Modifier.PUBLIC);

    long id = -1;
    for (AdapterModelField model : adapterInfo.models) {
      builder.addStatement("adapter.$L = new $T().id($L)", model.fieldName, model.typeName, id--);
    }

    return builder.build();
  }

  private static class AdapterClassInfo {
    static final String GENERATED_HELPER_CLASS_SUFFIX = "_EpoxyHelper";
    final List<AdapterModelField> models = new ArrayList<>();
    final Elements elementUtils;
    final ClassName generatedClassName;
    final TypeName adapterClassType;

    AdapterClassInfo(Elements elementUtils, TypeElement adapterClassElement) {
      this.elementUtils = elementUtils;
      generatedClassName = getGeneratedClassName(adapterClassElement);
      adapterClassType = TypeName.get(adapterClassElement.asType());
    }

    void addModel(AdapterModelField adapterModelField) {
      models.add(adapterModelField);
    }

    private ClassName getGeneratedClassName(TypeElement adapterClass) {
      String packageName = elementUtils.getPackageOf(adapterClass).getQualifiedName().toString();

      int packageLen = packageName.length() + 1;
      String className =
          adapterClass.getQualifiedName().toString().substring(packageLen).replace('.', '$');

      return ClassName.get(packageName, className + GENERATED_HELPER_CLASS_SUFFIX);
    }

    @Override
    public String toString() {
      return "AdapterClassInfo{"
          + "models=" + models
          + ", generatedClassName=" + generatedClassName
          + ", adapterClassType=" + adapterClassType
          + '}';
    }
  }

  private static class AdapterModelField {

    String fieldName;
    TypeName typeName;

    AdapterModelField(Element element) {
      fieldName = element.getSimpleName().toString();
      typeName = TypeName.get(element.asType());
    }

    @Override
    public String toString() {
      return "AdapterModelField{"
          + "name='" + fieldName + '\''
          + ", typeName=" + typeName
          + '}';
    }
  }
}
