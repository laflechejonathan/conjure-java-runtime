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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.palantir.conjure.java.api.errors.ErrorType;
import com.palantir.conjure.java.api.errors.QosException;
import com.palantir.conjure.java.api.errors.RemoteException;
import com.palantir.conjure.java.api.errors.SerializableError;
import com.palantir.conjure.java.api.errors.ServiceException;
import com.palantir.conjure.java.serialization.ObjectMappers;
import com.palantir.logsafe.SafeArg;
import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.junit.DropwizardAppRule;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.ws.rs.Consumes;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

public final class ExceptionMappingTest {

    @ClassRule
    public static final DropwizardAppRule<Configuration> APP =
            new DropwizardAppRule<>(ExceptionMappersTestServer.class, "src/test/resources/test-server.yml");
    private static final Response.Status SERVER_EXCEPTION_STATUS = Status.INTERNAL_SERVER_ERROR;
    private static final Response.Status WEB_EXCEPTION_STATUS = Status.INTERNAL_SERVER_ERROR;
    private static final int REMOTE_EXCEPTION_STATUS_CODE = 400;
    private static final int REMOTE_AUTH_EXCEPTION_STATUS_CODE = 403;

    private WebTarget target;

    @Before
    public void before() {
        String endpointUri = "http://localhost:" + APP.getLocalPort();
        JerseyClientBuilder builder = new JerseyClientBuilder();
        Client client = builder.build();
        target = client.target(endpointUri);
    }

    /**
     * These tests confirm that {@link WebApplicationException}s are handled by the {@link
     * WebApplicationExceptionMapper} rather than the {@link RuntimeExceptionMapper}
     */
    @Test
    public void testForbiddenException() {
        Response response = target.path("throw-forbidden-exception").request().get();
        assertThat(response.getStatus()).isEqualTo(Status.FORBIDDEN.getStatusCode());
    }

    @Test
    public void testNotFoundException() {
        Response response = target.path("throw-not-found-exception").request().get();
        assertThat(response.getStatus()).isEqualTo(Status.NOT_FOUND.getStatusCode());
    }

    @Test
    public void testServerErrorException() {
        Response response = target.path("throw-server-error-exception").request().get();
        assertThat(response.getStatus()).isEqualTo(SERVER_EXCEPTION_STATUS.getStatusCode());
    }

    @Test
    public void testWebApplicationException() {
        Response response = target.path("throw-web-application-exception").request().get();
        assertThat(response.getStatus()).isEqualTo(WEB_EXCEPTION_STATUS.getStatusCode());
    }

    @Test
    public void testRemoteException() throws IOException {
        Response response = target.path("throw-remote-exception").request().get();
        assertThat(response.getStatus()).isEqualTo(ErrorType.INTERNAL.httpErrorCode());
        String body = readBody(response);

        SerializableError error = ObjectMappers.newClientObjectMapper().readValue(body, SerializableError.class);
        assertThat(error.errorInstanceId()).isEqualTo("errorInstanceId");
        assertThat(error.errorCode()).isEqualTo(ErrorType.INTERNAL.code().toString());
        assertThat(error.errorName()).isEqualTo(ErrorType.INTERNAL.name());
    }

    @Test
    public void testRemoteAuthException() throws IOException {
        Response response = target.path("throw-remote-auth-exception").request().get();
        assertThat(response.getStatus()).isEqualTo(ErrorType.PERMISSION_DENIED.httpErrorCode());
        String body = readBody(response);

        SerializableError error = ObjectMappers.newClientObjectMapper().readValue(body, SerializableError.class);
        assertThat(error.errorInstanceId()).isEqualTo("errorInstanceId");
        assertThat(error.errorCode()).isEqualTo(ErrorType.PERMISSION_DENIED.code().toString());
        assertThat(error.errorName()).isEqualTo(ErrorType.PERMISSION_DENIED.name());
    }

    @Test
    public void testServiceException() throws IOException {
        Response response = target.path("throw-service-exception").request().get();
        assertThat(response.getStatus()).isEqualTo(ErrorType.INVALID_ARGUMENT.httpErrorCode());
        String body = readBody(response);

        SerializableError error = ObjectMappers.newClientObjectMapper().readValue(body, SerializableError.class);
        assertThat(error.errorCode()).isEqualTo(ErrorType.INVALID_ARGUMENT.code().toString());
        assertThat(error.errorName()).isEqualTo(ErrorType.INVALID_ARGUMENT.name());

        Map<String, Object> rawError =
                ObjectMappers.newClientObjectMapper().readValue(body, new TypeReference<Map<String, Object>>() {});
        assertThat(rawError).containsEntry("errorCode", ErrorType.INVALID_ARGUMENT.code().toString());
        assertThat(rawError).containsEntry("errorName", ErrorType.INVALID_ARGUMENT.name());
        assertThat(rawError).containsEntry("parameters", ImmutableMap.of("arg", "value"));
    }

    @Test
    public void testQosException() {
        Response response = target.path("throw-qos-retry-foo-exception").request().get();

        assertThat(response.getStatus()).isEqualTo(308);
        assertThat(response.getHeaderString("Location")).isEqualTo("http://foo");
    }

    @Test
    public void testAssertionErrorIsJsonException() throws IOException {
        Response response = target.path("throw-assertion-error").request().get();
        assertThat(response.getStatus()).isEqualTo(SERVER_EXCEPTION_STATUS.getStatusCode());
        String body = readBody(response);

        SerializableError error = ObjectMappers.newClientObjectMapper().readValue(body, SerializableError.class);
        assertThat(error.errorCode()).isEqualTo(ErrorType.INTERNAL.code().toString());
        assertThat(error.errorName()).isEqualTo(ErrorType.INTERNAL.name());
    }

    private String readBody(Response response) throws IOException {
        return new String(ByteStreams.toByteArray(response.readEntity(InputStream.class)), StandardCharsets.UTF_8);
    }

    public static class ExceptionMappersTestServer extends Application<Configuration> {
        @Override
        public final void run(Configuration _config, final Environment env) throws Exception {
            env.jersey().register(ConjureJerseyFeature.INSTANCE);
            env.jersey().register(new ExceptionTestResource());
        }
    }

    public static final class ExceptionTestResource implements ExceptionTestService {
        @Override
        public String throwForbiddenException() {
            throw new ForbiddenException();
        }

        @Override
        public String throwNotFoundException() {
            throw new NotFoundException();
        }

        @Override
        public String throwServerErrorException() {
            throw new ServerErrorException(SERVER_EXCEPTION_STATUS);
        }

        @Override
        public String throwWebApplicationException() {
            throw new WebApplicationException(WEB_EXCEPTION_STATUS);
        }

        @Override
        public String throwRemoteException() {
            throw new RemoteException(SerializableError.builder()
                    .errorInstanceId("errorInstanceId")
                    .errorCode("errorCode")
                    .errorName("errorName")
                    .build(),
                    REMOTE_EXCEPTION_STATUS_CODE);
        }

        @Override
        public String throwRemoteAuthException() {
            throw new RemoteException(SerializableError.builder()
                    .errorInstanceId("errorInstanceId")
                    .errorCode("errorCode")
                    .errorName("errorName")
                    .build(),
                    REMOTE_AUTH_EXCEPTION_STATUS_CODE);
        }

        @Override
        public String throwServiceException() {
            throw new ServiceException(ErrorType.INVALID_ARGUMENT, SafeArg.of("arg", "value"));
        }

        @Override
        public String throwQosRetryFooException() {
            try {
                throw QosException.retryOther(new URL("http://foo"));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String throwAssertionError() {
            throw new AssertionError();
        }
    }

    @Path("/")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public interface ExceptionTestService {
        @GET
        @Path("/throw-forbidden-exception")
        String throwForbiddenException();

        @GET
        @Path("/throw-not-found-exception")
        String throwNotFoundException();

        @GET
        @Path("/throw-server-error-exception")
        String throwServerErrorException();

        @GET
        @Path("/throw-web-application-exception")
        String throwWebApplicationException();

        @GET
        @Path("/throw-remote-exception")
        String throwRemoteException();

        @GET
        @Path("/throw-remote-auth-exception")
        String throwRemoteAuthException();

        @GET
        @Path("/throw-service-exception")
        String throwServiceException();

        @GET
        @Path("/throw-qos-retry-foo-exception")
        String throwQosRetryFooException();

        @GET
        @Path("/throw-assertion-error")
        String throwAssertionError();
    }
}
