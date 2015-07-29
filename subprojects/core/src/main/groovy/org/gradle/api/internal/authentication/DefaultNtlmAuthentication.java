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

package org.gradle.api.internal.authentication;

import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.authentication.NtlmAuthentication;
import org.gradle.api.credentials.Credentials;

import java.util.HashSet;
import java.util.Set;

public class DefaultNtlmAuthentication extends AbstractAuthentication implements NtlmAuthentication {
    public DefaultNtlmAuthentication(String name) {
        super(name);
    }

    @Override
    public Set<Class<? extends Credentials>> getSupportedCredentials() {
        Set<Class<? extends Credentials>> supported = new HashSet<Class<? extends Credentials>>();
        supported.add(PasswordCredentials.class);
        return supported;
    }
}