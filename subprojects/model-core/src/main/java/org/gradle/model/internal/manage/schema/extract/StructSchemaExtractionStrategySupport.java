/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.model.internal.manage.schema.extract;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.*;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Nullable;
import org.gradle.model.Unmanaged;
import org.gradle.model.internal.manage.schema.ModelProperty;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static org.gradle.model.internal.manage.schema.extract.ModelSchemaUtils.getOverloadedMethods;
import static org.gradle.model.internal.manage.schema.extract.ModelSchemaUtils.getOverriddenMethods;

public abstract class StructSchemaExtractionStrategySupport implements ModelSchemaExtractionStrategy {

    private final ModelSchemaAspectExtractor aspectExtractor;

    protected StructSchemaExtractionStrategySupport(ModelSchemaAspectExtractor aspectExtractor) {
        this.aspectExtractor = aspectExtractor;
    }

    protected abstract boolean isTarget(ModelType<?> type);

    public <R> void extract(final ModelSchemaExtractionContext<R> extractionContext) {
        ModelType<R> type = extractionContext.getType();
        if (!isTarget(type)) {
            return;
        }

        validateTypeHierarchy(extractionContext, type);

        List<ModelPropertyExtractionResult<?>> propertyExtractionResults = extractPropertySchemas(extractionContext, ModelSchemaUtils.getCandidateMethods(type.getRawClass()));
        List<ModelSchemaAspect> aspects = aspectExtractor.extract(extractionContext, propertyExtractionResults);

        ModelSchema<R> schema = createSchema(extractionContext, propertyExtractionResults, aspects);
        for (ModelPropertyExtractionResult<?> propertyResult : propertyExtractionResults) {
            toPropertyExtractionContext(extractionContext, propertyResult);
        }

        extractionContext.found(schema);
    }

    private <R, P> void toPropertyExtractionContext(ModelSchemaExtractionContext<R> parentContext, ModelPropertyExtractionResult<P> propertyResult) {
        ModelProperty<P> property = propertyResult.getProperty();
        parentContext.child(property.getType(), propertyDescription(parentContext, property), attachSchema(property, createPropertyValidator(parentContext, propertyResult)));
    }

    private <P> Action<? super ModelSchema<P>> attachSchema(final ModelProperty<P> property, final Action<ModelSchema<P>> propertyValidator) {
        return new Action<ModelSchema<P>>() {
            @Override
            public void execute(ModelSchema<P> propertySchema) {
                property.setSchema(propertySchema);
                propertyValidator.execute(propertySchema);
            }
        };
    }

    private <R> List<ModelPropertyExtractionResult<?>> extractPropertySchemas(ModelSchemaExtractionContext<R> extractionContext, Multimap<String, Method> methodsByName) {
        List<ModelPropertyExtractionResult<?>> results = Lists.newArrayList();
        Set<Method> handledMethods = Sets.newHashSet();

        List<String> methodNames = Lists.newArrayList(methodsByName.keySet());
        Collections.sort(methodNames);
        Set<String> skippedMethodNames = Sets.newHashSet();
        for (String methodName : methodNames) {
            if (skippedMethodNames.contains(methodName)) {
                continue;
            }

            Collection<Method> methods = methodsByName.get(methodName);

            List<List<Method>> overriddenMethods = getOverriddenMethods(methods);
            if (!getterOrSetter(methodName) && overriddenMethods != null) {
                handleOverriddenMethods(extractionContext, overriddenMethods);
                List<Method> flattenedOverridden = Lists.newArrayList(Iterables.concat(overriddenMethods));
                methods = Lists.newArrayList(Iterables.filter(methods, not(in(flattenedOverridden))));
                // TODO:PM mark non get/setters overridden methods accepted by child strategies as handled because they should be by default implementations, not enough context to validate this here
                handledMethods.addAll(flattenedOverridden);
                if (methods.isEmpty()) {
                    continue;
                }
            }

            List<Method> overloadedMethods = getOverloadedMethods(methods);
            if (overloadedMethods != null) {
                handleOverloadedMethods(extractionContext, overloadedMethods);
                methods = filterGetterMethods(methods);
                if (methods.isEmpty()) {
                    continue;
                }
            }

            int getterPrefixLen = getterPrefixLength(methodName);
            if (getterPrefixLen >= 0) {
                Method mostSpecificGetter = ModelSchemaUtils.findMostSpecificMethod(methods);

                String propertyNameCapitalized = methodName.substring(getterPrefixLen);
                char getterPropertyNameFirstChar = methodName.charAt(getterPrefixLen);
                if (!Character.isUpperCase(getterPropertyNameFirstChar)) {
                    if (propertyNameCapitalized.length() == 1 || (!Character.isUpperCase(methodName.charAt(getterPrefixLen + 1)))) {
                        handleInvalidGetter(extractionContext, mostSpecificGetter,
                            String.format("the %s character of the getter method name must be an uppercase character", getterPrefixLen == 2 ? "3rd" : "4th"));
                        continue;
                    }
                }

                String propertyName = StringUtils.uncapitalize(propertyNameCapitalized);
                String setterName = "set" + propertyNameCapitalized;
                Collection<Method> setterMethods = methodsByName.get(setterName);
                PropertyAccessorExtractionContext setterContext = !setterMethods.isEmpty() ? new PropertyAccessorExtractionContext(setterMethods) : null;

                String prefix = methodName.substring(0, getterPrefixLen);
                Iterable<Method> getterMethods = methods;
                if (prefix.equals("get")) {
                    String isGetterName = "is" + propertyNameCapitalized;
                    Collection<Method> isGetterMethods = methodsByName.get(isGetterName);
                    if (!isGetterMethods.isEmpty()) {
                        List<Method> overloadedIsGetterMethods = getOverloadedMethods(isGetterMethods);
                        if (overloadedIsGetterMethods != null) {
                            handleOverloadedMethods(extractionContext, overloadedIsGetterMethods);
                            isGetterMethods = filterGetterMethods(isGetterMethods);
                        }

                        if (!isGetterMethods.isEmpty()) {
                            Method mostSpecificIsGetter = ModelSchemaUtils.findMostSpecificMethod(isGetterMethods);
                            if (mostSpecificGetter.getReturnType() != boolean.class || mostSpecificIsGetter.getReturnType() != boolean.class) {
                                handleInvalidGetter(extractionContext, mostSpecificIsGetter,
                                    String.format("property '%s' has both '%s()' and '%s()' getters, but they don't both return a boolean",
                                        propertyName, isGetterName, methodName));
                                continue;
                            }
                            getterMethods = Iterables.concat(getterMethods, isGetterMethods);
                            skippedMethodNames.add(isGetterName);
                        }
                    }
                }

                PropertyAccessorExtractionContext getterContext = new PropertyAccessorExtractionContext(getterMethods);
                ModelPropertyExtractionResult<?> result = extractPropertySchema(extractionContext, propertyName, getterContext, setterContext, getterPrefixLen);
                if (result != null) {
                    results.add(result);
                    handledMethods.addAll(getterContext.getDeclaringMethods());
                    if (setterContext != null) {
                        handledMethods.addAll(setterContext.getDeclaringMethods());
                    }

                }
            }
        }

        validateAllNecessaryMethodsHandled(extractionContext, methodsByName.values(), handledMethods);
        return results;
    }

    private static boolean getterOrSetter(String methodName) {
        if (getterPrefixLength(methodName) > 0) {
            return true;
        }
        return methodName.startsWith("set") && !"set".equals(methodName);
    }

    private static Collection<Method> filterGetterMethods(Collection<Method> methods) {
        return Lists.newArrayList(Iterables.filter(methods, new Predicate<Method>() {
            @Override
            public boolean apply(Method method) {
                return method.getParameterTypes().length == 0;
            }
        }));
    }

    private static int getterPrefixLength(String methodName) {
        if (methodName.startsWith("get") && !"get".equals(methodName)) {
            return 3;
        }
        if (methodName.startsWith("is") && !"is".equals(methodName)) {
            return 2;
        }
        return -1;
    }

    @Nullable
    private <R> ModelPropertyExtractionResult<R> extractPropertySchema(final ModelSchemaExtractionContext<?> extractionContext, String propertyName, PropertyAccessorExtractionContext getterContext, PropertyAccessorExtractionContext setterContext, int getterPrefixLen) {
        // Take the most specific declaration of the getter
        Method mostSpecificGetter = getterContext.getMostSpecificDeclaration();
        if (mostSpecificGetter.getParameterTypes().length != 0) {
            handleInvalidGetter(extractionContext, mostSpecificGetter, "getter methods cannot take parameters");
            return null;
        }

        if (mostSpecificGetter.getReturnType() != boolean.class && getterPrefixLen == 2) {
            handleInvalidGetter(extractionContext, mostSpecificGetter, "getter method name must start with 'get'");
            return null;
        }

        ModelProperty.StateManagementType stateManagementType = determineStateManagementType(extractionContext, getterContext);
        final ModelType<R> returnType = ModelType.returnType(mostSpecificGetter);

        WeaklyTypeReferencingMethod<?, Void> setterRef;
        if (setterContext != null) {
            validateSetter(extractionContext, returnType, getterContext, setterContext);
            Method mostSpecificDeclaration = setterContext.getMostSpecificDeclaration();
            setterRef = WeaklyTypeReferencingMethod.of(ModelType.of(mostSpecificDeclaration.getDeclaringClass()), ModelType.of(void.class), mostSpecificDeclaration);
        } else {
            setterRef = null;
        }

        ImmutableSet<ModelType<?>> declaringClasses = ImmutableSet.copyOf(Iterables.transform(getterContext.getDeclaringMethods(), new Function<Method, ModelType<?>>() {
            public ModelType<?> apply(Method input) {
                return ModelType.of(input.getDeclaringClass());
            }
        }));

        List<WeaklyTypeReferencingMethod<?, R>> getterRefs = Lists.newArrayList(Iterables.transform(getterContext.getGetters(), new Function<Method, WeaklyTypeReferencingMethod<?, R>>() {
            @Override
            public WeaklyTypeReferencingMethod<?, R> apply(Method getter) {
                return WeaklyTypeReferencingMethod.of(ModelType.of(getter.getDeclaringClass()), returnType, getter);
            }
        }));

        boolean declaredAsHavingUnmanagedType = getterContext.getAnnotation(Unmanaged.class) != null;
        return new ModelPropertyExtractionResult<R>(
            new ModelProperty<R>(returnType, propertyName, stateManagementType, declaringClasses, getterRefs, setterRef, declaredAsHavingUnmanagedType),
            getterContext,
            setterContext
        );
    }

    protected abstract void validateAllNecessaryMethodsHandled(ModelSchemaExtractionContext<?> extractionContext, Collection<Method> allMethods, final Set<Method> handledMethods);

    protected abstract <R> void validateTypeHierarchy(ModelSchemaExtractionContext<R> extractionContext, ModelType<R> type);

    protected abstract void handleInvalidGetter(ModelSchemaExtractionContext<?> extractionContext, Method getter, String message);

    protected abstract void handleOverriddenMethods(ModelSchemaExtractionContext<?> extractionContext, List<List<Method>> overriddenMethods);

    protected abstract void handleOverloadedMethods(ModelSchemaExtractionContext<?> extractionContext, Collection<Method> overloadedMethods);

    protected abstract ModelProperty.StateManagementType determineStateManagementType(ModelSchemaExtractionContext<?> extractionContext, PropertyAccessorExtractionContext getterContext);

    protected abstract <R> ModelSchema<R> createSchema(ModelSchemaExtractionContext<R> extractionContext, Iterable<ModelPropertyExtractionResult<?>> propertyResults, Iterable<ModelSchemaAspect> aspects);

    protected abstract <P> Action<ModelSchema<P>> createPropertyValidator(ModelSchemaExtractionContext<?> extractionContext, ModelPropertyExtractionResult<P> propertyResult);

    private String propertyDescription(ModelSchemaExtractionContext<?> parentContext, ModelProperty<?> property) {
        if (property.getDeclaredBy().size() == 1 && property.getDeclaredBy().contains(parentContext.getType())) {
            return String.format("property '%s'", property.getName());
        } else {
            ImmutableSortedSet<String> declaredBy = ImmutableSortedSet.copyOf(Iterables.transform(property.getDeclaredBy(), Functions.toStringFunction()));
            return String.format("property '%s' declared by %s", property.getName(), Joiner.on(", ").join(declaredBy));
        }
    }

    protected abstract void validateSetter(ModelSchemaExtractionContext<?> extractionContext, ModelType<?> propertyType, PropertyAccessorExtractionContext getterContext, PropertyAccessorExtractionContext setterContext);
}
