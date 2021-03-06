/*
 * Copyright 2013 (c) MuleSoft, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.raml.jaxrs.codegen.core;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.sun.codemodel.JAnnotationArrayMember;
import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import static com.sun.codemodel.JMod.PUBLIC;
import static com.sun.codemodel.JMod.STATIC;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.mail.internet.MimeMultipart;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import static org.apache.commons.lang.StringUtils.capitalize;
import static org.apache.commons.lang.StringUtils.defaultString;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.join;
import static org.apache.commons.lang.StringUtils.strip;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang.builder.ToStringBuilder;
import static org.apache.commons.lang.builder.ToStringStyle.SHORT_PREFIX_STYLE;
import org.apache.commons.lang.math.NumberUtils;
import static org.raml.jaxrs.codegen.core.Constants.RESPONSE_HEADER_WILDCARD_SYMBOL;
import static org.raml.jaxrs.codegen.core.Names.GENERIC_PAYLOAD_ARGUMENT_NAME;
import static org.raml.jaxrs.codegen.core.Names.MULTIPLE_RESPONSE_HEADERS_ARGUMENT_NAME;
import org.raml.model.Action;
import org.raml.model.ActionType;
import org.raml.model.MimeType;
import org.raml.model.Raml;
import org.raml.model.Resource;
import org.raml.model.Response;
import org.raml.model.parameter.AbstractParam;
import org.raml.model.parameter.FormParameter;
import org.raml.model.parameter.Header;
import org.raml.model.parameter.QueryParameter;
import org.raml.model.parameter.UriParameter;
import org.raml.parser.rule.ValidationResult;
import org.raml.parser.visitor.RamlDocumentBuilder;
import org.raml.parser.visitor.RamlValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates jackson-ready models and jersey resources from RAML/JSON
 */
public class Generator {

    protected static final String DEFAULT_ANNOTATION_PARAMETER = "value";
    protected static final String GENERIC_RESPONSE_METHOD_NAME = "respond";

    private static final Logger LOGGER = LoggerFactory.getLogger(Generator.class);

    private Context context;
    private Types types;

    public Set<String> run(final URL ramlFile, final Configuration configuration) throws Exception {
        final List<ValidationResult> results = RamlValidationService.createDefault().validate(ramlFile.toString());

        if (ValidationResult.areValid(results)) {
            final FileInputStream fiReader = new FileInputStream(new File(ramlFile.toURI()));
            final String ramlBuffer = IOUtils.toString(fiReader, "UTF-8");
            return run(new RamlDocumentBuilder().build(ramlBuffer, ramlFile.toString()), configuration);
        } else {
            final List<String> validationErrors = Lists.transform(results,
                    new Function<ValidationResult, String>() {
                        @Override
                        public String apply(final ValidationResult vr) {
                            return String.format("%s %s", vr.getStartColumn(), vr.getMessage());
                        }
                    });

            throw new IllegalArgumentException("Invalid RAML definition:\n" + join(validationErrors, "\n"));
        }
    }

    protected void validate(final Configuration configuration) {
        Validate.notNull(configuration, "configuration can't be null");

        final File outputDirectory = configuration.getOutputDirectory();
        Validate.notNull(outputDirectory, "outputDirectory can't be null");

        Validate.isTrue(outputDirectory.isDirectory(), outputDirectory + " is not a pre-existing directory");
        Validate.isTrue(outputDirectory.canWrite(), outputDirectory + " can't be written to");

        if (outputDirectory.listFiles().length > 0) {
            LOGGER.warn("Directory "
                    + outputDirectory
                    + " is not empty, generation will work but pre-existing files may remain and produce "
                    + "unexpected results");
        }

        Validate.notEmpty(configuration.getBasePackageName(), "base package name can't be empty");
    }

    public Set<String> run(final Raml raml, final Configuration configuration) throws Exception {
        validate(configuration);

        context = new Context(configuration, raml);
        types = new Types(context);

        for (final Resource resource : raml.getResources().values()) {
            createResourceInterface(resource);
        }

        return context.generate();
    }

    protected void createResourceInterface(final Resource resource) throws Exception {
        final String resourceInterfaceName = Names.buildResourceInterfaceName(resource);
        final JDefinedClass resourceInterface = context.createResourceInterface(resourceInterfaceName);
        context.setCurrentResourceInterface(resourceInterface);

        final String path = strip(resource.getRelativeUri(), "/");
        resourceInterface.annotate(Path.class).param(DEFAULT_ANNOTATION_PARAMETER,
                StringUtils.defaultIfBlank(path, "/"));

        if (isNotBlank(resource.getDescription())) {
            resourceInterface.javadoc().add(resource.getDescription());
        }

        addResourceMethods(resource, resourceInterface, path);
    }

    protected void addResourceMethods(final Resource resource,
            final JDefinedClass resourceInterface,
            final String resourceInterfacePath) throws Exception {
        for (final Action action : resource.getActions().values()) {
            if (!action.hasBody()) {
                addResourceMethods(resourceInterface, resourceInterfacePath, action, null, false);
            } else if (action.getBody().size() == 1) {
                final MimeType bodyMimeType = action.getBody().values().iterator().next();
                addResourceMethods(resourceInterface, resourceInterfacePath, action, bodyMimeType, false);
            } else {
                for (final MimeType bodyMimeType : action.getBody().values()) {
                    addResourceMethods(resourceInterface, resourceInterfacePath, action, bodyMimeType, true);
                }
            }
        }

        for (final Resource childResource : resource.getResources().values()) {
            addResourceMethods(childResource, resourceInterface, resourceInterfacePath);
        }
    }

    protected void addResourceMethods(final JDefinedClass resourceInterface,
            final String resourceInterfacePath,
            final Action action,
            final MimeType bodyMimeType,
            final boolean addBodyMimeTypeInMethodName) throws Exception {
        final Collection<MimeType> uniqueResponseMimeTypes = getUniqueResponseMimeTypes(action);

        addResourceMethod(resourceInterface, resourceInterfacePath, action, bodyMimeType,
                addBodyMimeTypeInMethodName, uniqueResponseMimeTypes);
    }

    protected void addResourceMethod(final JDefinedClass resourceInterface,
            final String resourceInterfacePath,
            final Action action,
            final MimeType bodyMimeType,
            final boolean addBodyMimeTypeInMethodName,
            final Collection<MimeType> uniqueResponseMimeTypes) throws Exception {
        final String methodName = Names.buildResourceMethodName(action,
                addBodyMimeTypeInMethodName ? bodyMimeType : null);

        final JType resourceMethodReturnType = getResourceMethodReturnType(methodName, action,
                uniqueResponseMimeTypes.isEmpty(), resourceInterface);

        // the actually created unique method name should be needed in the previous method but
        // no way of doing this :(
        final JMethod method = context.createResourceMethod(resourceInterface, methodName,
                resourceMethodReturnType);

        context.addHttpMethodAnnotation(action.getType().toString(), method);

        addParamAnnotation(resourceInterfacePath, action, method);
        addConsumesAnnotation(bodyMimeType, method);
        addProducesAnnotation(uniqueResponseMimeTypes, method);

        final JDocComment javadoc = addBaseJavaDoc(action, method);

        addPathParameters(action, method, javadoc);
        addHeaderParameters(action, method, javadoc);
        addQueryParameters(action, method, javadoc);

        addBodyParameters(action.getType(), bodyMimeType, method, javadoc);
    }

    protected JType getResourceMethodReturnType(final String methodName,
            final Action action,
            final boolean returnsVoid,
            final JDefinedClass resourceInterface) throws Exception {
        if (returnsVoid) {
            return types.getGeneratorType(void.class);
        } else {
            return createResourceMethodReturnType(methodName, action, resourceInterface);
        }
    }

    protected JDefinedClass createResourceMethodReturnType(final String methodName,
            final Action action,
            final JDefinedClass resourceInterface)
            throws Exception {
        final JDefinedClass responseClass = resourceInterface._class(capitalize(methodName) + "Response")
                ._extends(context.getResponseWrapperType());

        final JMethod responseClassConstructor = responseClass.constructor(JMod.PRIVATE);
        responseClassConstructor.param(javax.ws.rs.core.Response.class, "delegate");
        responseClassConstructor.body().invoke("super").arg(JExpr.ref("delegate"));

        for (final Entry<String, Response> statusCodeAndResponse : action.getResponses().entrySet()) {
            createResponseBuilderInResourceMethodReturnType(action, responseClass, statusCodeAndResponse);
        }

        createGenericResponseBuilderInResourceMethodReturnType(responseClass);

        return responseClass;
    }

    protected void createResponseBuilderInResourceMethodReturnType(final Action action,
            final JDefinedClass responseClass,
            final Entry<String, Response> statusCodeAndResponse)
            throws Exception {
        final int statusCode = NumberUtils.toInt(statusCodeAndResponse.getKey());
        final Response response = statusCodeAndResponse.getValue();

        if (!response.hasBody()) {
            createResponseBuilderInResourceMethodReturnType(responseClass, statusCode, response, null);
        } else {
            for (final MimeType mimeType : response.getBody().values()) {
                createResponseBuilderInResourceMethodReturnType(responseClass, statusCode, response, mimeType);
            }
        }
    }

    protected void createResponseBuilderInResourceMethodReturnType(final JDefinedClass responseClass,
            final int statusCode,
            final Response response,
            final MimeType responseMimeType)
            throws Exception {
        final String responseBuilderMethodName = Names.buildResponseMethodName(statusCode, responseMimeType);

        final JMethod responseBuilderMethod = responseClass.method(PUBLIC + STATIC, responseClass,
                responseBuilderMethodName);

        final JDocComment javadoc = responseBuilderMethod.javadoc();

        if (isNotBlank(response.getDescription())) {
            javadoc.add(response.getDescription());
        }

        JInvocation builderArgument = types.getGeneratorClass(javax.ws.rs.core.Response.class)
                .staticInvoke("status")
                .arg(JExpr.lit(statusCode));

        if (responseMimeType != null) {
            builderArgument = builderArgument.invoke("header")
                    .arg(HttpHeaders.CONTENT_TYPE)
                    .arg(responseMimeType.getType());
        }

        final StringBuilder freeFormHeadersDescription = new StringBuilder();

        for (final Entry<String, Header> namedHeaderParameter : response.getHeaders().entrySet()) {
            final String headerName = namedHeaderParameter.getKey();
            final Header header = namedHeaderParameter.getValue();

            if (headerName.contains(RESPONSE_HEADER_WILDCARD_SYMBOL)) {
                appendParameterJavadocDescription(header, freeFormHeadersDescription);
                continue;
            }

            final String argumentName = Names.buildVariableName(headerName);

            builderArgument = builderArgument.invoke("header").arg(headerName).arg(JExpr.ref(argumentName));

            addParameterJavaDoc(header, argumentName, javadoc);

            responseBuilderMethod.param(types.buildParameterType(header, argumentName), argumentName);
        }

        final JBlock responseBuilderMethodBody = responseBuilderMethod.body();

        final JVar builderVariable = responseBuilderMethodBody.decl(
                types.getGeneratorType(ResponseBuilder.class), "responseBuilder", builderArgument);

        if (freeFormHeadersDescription.length() > 0) {
            // generate a Map<String, List<Object>> argument for {?} headers
            final JClass listOfObjectsClass = types.getGeneratorClass(List.class).narrow(Object.class);
            final JClass headersArgument = types.getGeneratorClass(Map.class).narrow(
                    types.getGeneratorClass(String.class), listOfObjectsClass);

            builderArgument = responseBuilderMethodBody.invoke("headers")
                    .arg(JExpr.ref(MULTIPLE_RESPONSE_HEADERS_ARGUMENT_NAME))
                    .arg(builderVariable);

            final JVar param = responseBuilderMethod.param(headersArgument,
                    MULTIPLE_RESPONSE_HEADERS_ARGUMENT_NAME);

            javadoc.addParam(param).add(freeFormHeadersDescription.toString());
        }

        if (responseMimeType != null) {
            responseBuilderMethodBody.invoke(builderVariable, "entity").arg(
                    JExpr.ref(GENERIC_PAYLOAD_ARGUMENT_NAME));
            responseBuilderMethod.param(types.getResponseEntityClass(responseMimeType),
                    GENERIC_PAYLOAD_ARGUMENT_NAME);
            javadoc.addParam(GENERIC_PAYLOAD_ARGUMENT_NAME);
        }

        responseBuilderMethodBody._return(JExpr._new(responseClass).arg(builderVariable.invoke("build")));
    }

    protected void createGenericResponseBuilderInResourceMethodReturnType(final JDefinedClass responseClass)
            throws Exception {
        final String responseBuilderMethodName = GENERIC_RESPONSE_METHOD_NAME;

        final JMethod responseBuilderMethod = responseClass.method(PUBLIC + STATIC, responseClass,
                responseBuilderMethodName);

        JInvocation builderArgument = types.getGeneratorClass(javax.ws.rs.core.Response.class)
                .staticInvoke("status")
                .arg(JExpr.ref("status"));

        responseBuilderMethod.param(context.getCodeModel().parseType("int"), "status");
        responseBuilderMethod.param(context.getGeneratorType(StreamingOutput.class), GENERIC_PAYLOAD_ARGUMENT_NAME);

        final JBlock responseBuilderMethodBody = responseBuilderMethod.body();

        final JVar builderVariable = responseBuilderMethodBody.decl(
                types.getGeneratorType(ResponseBuilder.class), "responseBuilder", builderArgument);
        responseBuilderMethodBody.invoke(builderVariable, GENERIC_PAYLOAD_ARGUMENT_NAME).arg(
                JExpr.ref(GENERIC_PAYLOAD_ARGUMENT_NAME));

        responseBuilderMethodBody._return(JExpr._new(responseClass).arg(builderVariable.invoke("build")));
    }

    protected JDocComment addBaseJavaDoc(final Action action, final JMethod method) {
        final JDocComment javadoc = method.javadoc();
        if (isNotBlank(action.getDescription())) {
            javadoc.add(action.getDescription());
        }
        return javadoc;
    }

    protected void addParamAnnotation(final String resourceInterfacePath,
            final Action action,
            final JMethod method) {
        final String path = StringUtils.substringAfter(action.getResource().getUri(), resourceInterfacePath
                + "/");
        if (isNotBlank(path)) {
            method.annotate(Path.class).param(DEFAULT_ANNOTATION_PARAMETER, path);
        }
    }

    protected void addConsumesAnnotation(final MimeType bodyMimeType, final JMethod method) {
        if (bodyMimeType != null) {
            method.annotate(Consumes.class).param(DEFAULT_ANNOTATION_PARAMETER, bodyMimeType.getType());
        }
    }

    protected void addProducesAnnotation(final Collection<MimeType> uniqueResponseMimeTypes,
            final JMethod method) {
        if (uniqueResponseMimeTypes.isEmpty()) {
            return;
        }

        final JAnnotationArrayMember paramArray = method.annotate(Produces.class).paramArray(
                DEFAULT_ANNOTATION_PARAMETER);

        for (final MimeType responseMimeType : uniqueResponseMimeTypes) {
            paramArray.param(responseMimeType.getType());
        }
    }

    protected Collection<MimeType> getUniqueResponseMimeTypes(final Action action) {
        final Map<String, MimeType> responseMimeTypes = new HashMap<String, MimeType>();
        for (final Response response : action.getResponses().values()) {
            if (response.hasBody()) {
                for (final MimeType responseMimeType : response.getBody().values()) {
                    if (responseMimeType != null) {
                        responseMimeTypes.put(responseMimeType.getType(), responseMimeType);
                    }
                }
            }
        }
        return responseMimeTypes.values();
    }

    protected void addBodyParameters(ActionType type, final MimeType bodyMimeType,
            final JMethod method,
            final JDocComment javadoc) throws Exception {
        if (bodyMimeType == null && !hasBody(type)) {
            return;
        } else if (bodyMimeType != null && MediaType.APPLICATION_FORM_URLENCODED.equals(bodyMimeType.getType())) {
            addFormParameters(bodyMimeType, method, javadoc);
        } else if (bodyMimeType != null && MediaType.MULTIPART_FORM_DATA.equals(bodyMimeType.getType())) {
            // use a "catch all" javax.mail.internet.MimeMultipart parameter
            addCatchAllFormParametersArgument(bodyMimeType, method, javadoc,
                    types.getGeneratorType(MimeMultipart.class));
        } else {
            addPlainBodyArgument(bodyMimeType, method, javadoc);
        }
    }

    protected boolean hasBody(ActionType type) {
        return type == ActionType.PUT || type == ActionType.POST || type == ActionType.PATCH;
    }

    protected void addPathParameters(final Action action, final JMethod method, final JDocComment javadoc)
            throws Exception {
        Resource resource = action.getResource();

        while (resource != null) {
            for (final Entry<String, UriParameter> namedUriParameter : resource.getUriParameters().entrySet()) {
                addParameter(namedUriParameter.getKey(), namedUriParameter.getValue(), PathParam.class, method,
                        javadoc);
            }

            resource = resource.getParentResource();
        }
    }

    protected void addHeaderParameters(final Action action, final JMethod method, final JDocComment javadoc)
            throws Exception {
        for (final Entry<String, Header> namedHeaderParameter : action.getHeaders().entrySet()) {
            addParameter(namedHeaderParameter.getKey(), namedHeaderParameter.getValue(), HeaderParam.class,
                    method, javadoc);
        }
    }

    protected void addQueryParameters(final Action action, final JMethod method, final JDocComment javadoc)
            throws Exception {
        for (final Entry<String, QueryParameter> namedQueryParameter : action.getQueryParameters().entrySet()) {
            addParameter(namedQueryParameter.getKey(), namedQueryParameter.getValue(), QueryParam.class,
                    method, javadoc);
        }
    }

    protected void addFormParameters(final MimeType bodyMimeType,
            final JMethod method,
            final JDocComment javadoc) throws Exception {
        if (hasAMultiTypeFormParameter(bodyMimeType)) {
            // use a "catch all" MultivaluedMap<String, String> parameter
            final JClass type = types.getGeneratorClass(MultivaluedMap.class).narrow(String.class,
                    String.class);

            addCatchAllFormParametersArgument(bodyMimeType, method, javadoc, type);
        } else {
            for (final Entry<String, List<FormParameter>> namedFormParameters : bodyMimeType.getFormParameters()
                    .entrySet()) {
                addParameter(namedFormParameters.getKey(), namedFormParameters.getValue().get(0),
                        FormParam.class, method, javadoc);
            }
        }
    }

    protected void addCatchAllFormParametersArgument(final MimeType bodyMimeType,
            final JMethod method,
            final JDocComment javadoc,
            final JType argumentType) {
        method.param(argumentType, GENERIC_PAYLOAD_ARGUMENT_NAME);

        // build a javadoc text out of all the params
        for (final Entry<String, List<FormParameter>> namedFormParameters : bodyMimeType.getFormParameters()
                .entrySet()) {
            final StringBuilder sb = new StringBuilder();
            sb.append(namedFormParameters.getKey()).append(": ");

            for (final FormParameter formParameter : namedFormParameters.getValue()) {
                appendParameterJavadocDescription(formParameter, sb);
            }

            javadoc.addParam(GENERIC_PAYLOAD_ARGUMENT_NAME).add(sb.toString());
        }
    }

    protected void addPlainBodyArgument(final MimeType bodyMimeType,
            final JMethod method,
            final JDocComment javadoc) throws IOException {
        method.param(types.getRequestEntityClass(bodyMimeType), GENERIC_PAYLOAD_ARGUMENT_NAME);

        javadoc.addParam(GENERIC_PAYLOAD_ARGUMENT_NAME).add("the request body");
    }

    protected boolean hasAMultiTypeFormParameter(final MimeType bodyMimeType) {
        for (final List<FormParameter> formParameters : bodyMimeType.getFormParameters().values()) {
            if (formParameters.size() > 1) {
                return true;
            }
        }
        return false;
    }

    protected void addParameter(final String name,
            final AbstractParam parameter,
            final Class<? extends Annotation> annotationClass,
            final JMethod method,
            final JDocComment javadoc) throws Exception {
        final String argumentName = Names.buildVariableName(name);

        final JVar argumentVariable = method.param(types.buildParameterType(parameter, argumentName),
                argumentName);

        argumentVariable.annotate(annotationClass).param(DEFAULT_ANNOTATION_PARAMETER, name);

        if (parameter.getDefaultValue() != null) {
            argumentVariable.annotate(DefaultValue.class).param(DEFAULT_ANNOTATION_PARAMETER,
                    parameter.getDefaultValue());
        }

        if (context.getConfiguration().isUseJsr303Annotations()) {
            addJsr303Annotations(parameter, argumentVariable);
        }

        addParameterJavaDoc(parameter, argumentVariable.name(), javadoc);
    }

    protected void addJsr303Annotations(final AbstractParam parameter, final JVar argumentVariable) {
        if (isNotBlank(parameter.getPattern())) {
            LOGGER.info("Pattern constraint ignored for parameter: "
                    + ToStringBuilder.reflectionToString(parameter, SHORT_PREFIX_STYLE));
        }

        final Integer minLength = parameter.getMinLength();
        final Integer maxLength = parameter.getMaxLength();
        if ((minLength != null) || (maxLength != null)) {
            final JAnnotationUse sizeAnnotation = argumentVariable.annotate(Size.class);

            if (minLength != null) {
                sizeAnnotation.param("min", minLength);
            }

            if (maxLength != null) {
                sizeAnnotation.param("max", maxLength);
            }
        }

        final BigDecimal minimum = parameter.getMinimum();
        if (minimum != null) {
            addMinMaxConstraint(parameter, "minimum", Min.class, minimum, argumentVariable);
        }

        final BigDecimal maximum = parameter.getMinimum();
        if (maximum != null) {
            addMinMaxConstraint(parameter, "maximum", Max.class, maximum, argumentVariable);
        }

        if (parameter.isRequired()) {
            argumentVariable.annotate(NotNull.class);
        }
    }

    protected void addMinMaxConstraint(final AbstractParam parameter,
            final String name,
            final Class<? extends Annotation> clazz,
            final BigDecimal value,
            final JVar argumentVariable) {
        try {
            final long boundary = value.longValueExact();
            argumentVariable.annotate(clazz).param(DEFAULT_ANNOTATION_PARAMETER, boundary);
        } catch (final ArithmeticException ae) {
            LOGGER.info("Non integer " + name + " constraint ignored for parameter: "
                    + ToStringBuilder.reflectionToString(parameter, SHORT_PREFIX_STYLE));
        }
    }

    protected void addParameterJavaDoc(final AbstractParam parameter,
            final String parameterName,
            final JDocComment javadoc) {
        javadoc.addParam(parameterName).add(
                defaultString(parameter.getDescription()));
    }

    protected void appendParameterJavadocDescription(final AbstractParam param, final StringBuilder sb) {
        if (isNotBlank(param.getDisplayName())) {
            sb.append(param.getDisplayName());
        }

        if (isNotBlank(param.getDescription())) {
            if (sb.length() > 0) {
                sb.append(" - ");
            }
            sb.append(param.getDescription());
        }

        sb.append("<br/>\n");
    }

    protected Context getContext() {
        return context;
    }

    protected Types getTypes() {
        return types;
    }
}
