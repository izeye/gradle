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

package org.gradle.jvm.tasks.api.internal;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.objectweb.asm.Opcodes.ASM5;

/**
 * Extracts an "API class" from an original "runtime class" for subsequent inclusion
 * into an {@link org.gradle.jvm.tasks.api.ApiJar}.
 */
public class ApiClassExtractor {

    private static final Pattern LOCAL_CLASS_PATTERN = Pattern.compile(".+\\$[0-9]+(?:[\\p{Alnum}_$]+)?$");

    private final Set<String> exportedPackages;
    private final boolean apiIncludesPackagePrivateMembers;

    public ApiClassExtractor(Set<String> exportedPackages) {
        this.exportedPackages = exportedPackages;
        this.apiIncludesPackagePrivateMembers = exportedPackages.isEmpty();
    }

    /**
     * Indicates whether the class in the given file is a candidate for extraction to
     * an API class. Checks whether the class's package is in the list of packages
     * explicitly exported by the library (if any), and whether the class should be
     * included in the public API based on its visibility. If the list of exported
     * packages is empty (e.g. the library has not declared an explicit {@code api {...}}
     * specification, then package-private classes are included in the public API. If the
     * list of exported packages is non-empty (i.e. the library has declared an
     * {@code api {...}} specification, then package-private classes are excluded.
     *
     * <p>For these reasons, this method should be called as a test on every original
     * .class file prior to invoking processed through
     * {@link #extractApiClassFrom(File)}.</p>
     *
     * @param originalClassFile the file containing the original class to evaluate
     * @return whether the given class is a candidate for API extraction
     */
    public boolean shouldExtractApiClassFrom(File originalClassFile) throws IOException {
        if (!originalClassFile.getName().endsWith(".class")) {
            return false;
        }
        InputStream inputStream = new FileInputStream(originalClassFile);
        try {
            return shouldExtractApiClassFrom(new ClassReader(inputStream));
        } finally {
            inputStream.close();
        }
    }

    boolean shouldExtractApiClassFrom(ClassReader originalClassReader) {
        final AtomicBoolean shouldExtract = new AtomicBoolean();
        originalClassReader.accept(new ClassVisitor(ASM5) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                String originalClassName = convertAsmInternalNameToClassName(name);
                shouldExtract.set(isApiClassExtractionCandidate(access, originalClassName));
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return shouldExtract.get();
    }

    /**
     * Extracts an API class from a given original class.
     *
     * @param originalClassFile the file containing the original class
     * @return bytecode of the API class extracted from the original class
     */
    public byte[] extractApiClassFrom(File originalClassFile) throws IOException {
        InputStream inputStream = new FileInputStream(originalClassFile);
        try {
            ClassReader classReader = new ClassReader(inputStream);
            return extractApiClassFrom(classReader);
        } finally {
            inputStream.close();
        }
    }

    byte[] extractApiClassFrom(ClassReader originalClassReader) {
        ClassWriter apiClassWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        originalClassReader.accept(
            new ApiMemberSelector(new MethodStubbingApiMemberAdapter(apiClassWriter), apiIncludesPackagePrivateMembers),
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return apiClassWriter.toByteArray();
    }

    private boolean isApiClassExtractionCandidate(int access, String candidateClassName) {
        if (isLocalClass(candidateClassName)) {
            return false;
        }
        if (!ApiMemberSelector.isCandidateApiMember(access, apiIncludesPackagePrivateMembers)) {
            return false;
        }
        if (exportedPackages.isEmpty()) {
            return true;
        }
        return exportedPackages.contains(packageNameOf(candidateClassName));
    }

    private static String convertAsmInternalNameToClassName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String packageNameOf(String className) {
        return className.indexOf('.') > 0 ? className.substring(0, className.lastIndexOf('.')) : "";
    }

    // See JLS3 "Binary Compatibility" (13.1)
    private static boolean isLocalClass(String className) {
        return LOCAL_CLASS_PATTERN.matcher(className).matches();
    }
}
