/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.conjure.java.server.jersey;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.inject.Inject;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NoContentException;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import org.glassfish.jersey.message.MessageBodyWorkers;

@Provider
@Produces(MediaType.WILDCARD)
public final class Java8OptionalMessageBodyWriter implements MessageBodyWriter<Optional<?>> {

    @Inject
    private javax.inject.Provider<MessageBodyWorkers> mbw;

    // Jersey ignores this
    @Override
    public long getSize(
            Optional<?> _entity,
            Class<?> _type,
            Type _genericType,
            Annotation[] _annotations,
            MediaType _mediaType) {
        return 0;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type _genericType, Annotation[] _annotations, MediaType _mediaType) {
        return Optional.class.isAssignableFrom(type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void writeTo(
            Optional<?> entity,
            Class<?> _type,
            Type genericType,
            Annotation[] annotations,
            MediaType mediaType,
            MultivaluedMap<String, Object> httpHeaders,
            OutputStream entityStream)
            throws IOException {
        if (!entity.isPresent()) {
            throw new NoContentException("Absent value for type: " + genericType);
        }

        Type innerGenericType =
                (genericType instanceof ParameterizedType)
                        ? ((ParameterizedType) genericType).getActualTypeArguments()[0]
                        : entity.get().getClass();

        MessageBodyWriter writer = mbw.get()
                .getMessageBodyWriter(entity.get().getClass(),
                        innerGenericType,
                        annotations,
                        mediaType);

        writer.writeTo(entity.get(),
                entity.get().getClass(),
                innerGenericType,
                annotations,
                mediaType,
                httpHeaders,
                entityStream);
    }
}
