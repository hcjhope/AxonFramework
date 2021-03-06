/*
 * Copyright (c) 2010-2016. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.messaging.annotation;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.Message;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.Map;
import java.util.Optional;

public class AnnotatedMessageHandlingMember<T> implements MessageHandlingMember<T> {

    private final Class<?> payloadType;
    private final int parameterCount;
    private final ParameterResolver<?>[] parameterResolvers;
    private final Executable executable;
    private final Class<? extends Message> messageType;

    public AnnotatedMessageHandlingMember(Executable executable, Class<? extends Message> messageType,
                                          Class<?> explicitPayloadType, ParameterResolverFactory parameterResolverFactory) {
        this.executable = executable;
        this.messageType = messageType;
        ReflectionUtils.ensureAccessible(this.executable);
        Parameter[] parameters = executable.getParameters();
        this.parameterCount = executable.getParameterCount();
        parameterResolvers = new ParameterResolver[parameterCount];
        Class<?> supportedPayloadType = explicitPayloadType;
        for (int i = 0; i < parameterCount; i++) {
            parameterResolvers[i] = parameterResolverFactory.createInstance(executable, parameters, i);
            if (parameterResolvers[i] == null) {
                throw new UnsupportedHandlerException("Unable to resolver parameter " + i + " (" + parameters[i].getType().getSimpleName() + ") in handler " + executable.toGenericString() + ".", executable);
            }
            if (supportedPayloadType.isAssignableFrom(parameterResolvers[i].supportedPayloadType())) {
                supportedPayloadType = parameterResolvers[i].supportedPayloadType();
            } else if (!parameterResolvers[i].supportedPayloadType().isAssignableFrom(supportedPayloadType)) {
                throw new UnsupportedHandlerException(String.format("The method %s seems to have parameters that put conflicting requirements on the payload type applicable on that method: %s vs %s", executable.toGenericString(), supportedPayloadType, parameterResolvers[i].supportedPayloadType()), executable);
            }
        }
        this.payloadType = supportedPayloadType;
    }

    @Override
    public Class<?> payloadType() {
        return payloadType;
    }

    @Override
    public int priority() {
        return parameterCount;
    }

    @Override
    public boolean canHandle(Message<?> message) {
        return typeMatches(message) && payloadType.isAssignableFrom(message.getPayloadType()) && parametersMatch(message);
    }

    protected boolean typeMatches(Message<?> message) {
        return messageType.isInstance(message);
    }

    protected boolean parametersMatch(Message<?> message) {
        for (ParameterResolver resolver : parameterResolvers) {
            if (!resolver.matches(message)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Object handle(Message<?> message, T target) throws Exception {
        try {
            if (executable instanceof Method) {
                return ((Method) executable).invoke(target, resolveParameterValues(message));
            } else if (executable instanceof Constructor) {
                return ((Constructor) executable).newInstance(resolveParameterValues(message));
            } else {
                throw new IllegalStateException("What kind of handler is this?");
            }
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
            checkAndRethrowForExceptionOrError(e);
            throw new MessageHandlerInvocationException(
                    String.format("Error handling an object of type [%s]", message.getPayloadType()), e);
        }
    }

    private void checkAndRethrowForExceptionOrError(ReflectiveOperationException e) throws Exception {
        if (e.getCause() instanceof Exception) {
            throw (Exception) e.getCause();
        } else if (e.getCause() instanceof Error) {
            throw (Error) e.getCause();
        }
    }

    private Object[] resolveParameterValues(Message<?> message) {
        Object[] params = new Object[parameterCount];
        for (int i = 0; i < parameterCount; i++) {
            params[i] = parameterResolvers[i].resolveParameterValue(message);
        }
        return params;
    }

    @Override
    public Optional<Map<String, Object>> annotationAttributes(Class<? extends Annotation> annotationType) {
        return AnnotationUtils.findAnnotationAttributes(executable, annotationType);
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return AnnotationUtils.findAnnotation(executable, annotationType) != null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <H> Optional<H> unwrap(Class<H> handlerType) {
        if (handlerType.isInstance(executable)) {
            return (Optional<H>) Optional.of(executable);
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + executable.toGenericString();
    }
}
