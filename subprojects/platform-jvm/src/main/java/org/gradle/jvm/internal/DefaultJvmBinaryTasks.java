/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.jvm.internal;

import org.gradle.jvm.JvmBinaryTasks;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.tasks.api.ApiJar;
import org.gradle.platform.base.BinaryTasksCollection;
import org.gradle.platform.base.internal.BinaryTasksCollectionWrapper;

public class DefaultJvmBinaryTasks extends BinaryTasksCollectionWrapper implements JvmBinaryTasks {

    public DefaultJvmBinaryTasks(BinaryTasksCollection delegate) {
        super(delegate);
    }

    public Jar getJar() {
        return findSingleTaskWithType(Jar.class);
    }

    public ApiJar getApiJar() {
        return findSingleTaskWithType(ApiJar.class);
    }
}
