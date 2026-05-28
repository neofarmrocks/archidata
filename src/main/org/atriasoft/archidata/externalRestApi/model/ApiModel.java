package org.atriasoft.archidata.externalRestApi.model;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.atriasoft.archidata.annotation.AnnotationTools;
import org.atriasoft.archidata.annotation.checker.ValidGroup;
import org.atriasoft.archidata.annotation.method.PaginationContext;
import org.atriasoft.archidata.dataAccess.model.Pagination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.validation.Valid;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.Context;

/**
 * Represents a single REST API endpoint extracted from a JAX-RS method.
 *
 * <p>Contains all metadata about the endpoint: HTTP method, path, parameters,
 * return types, consumed/produced media types, and security roles.
 */
public class ApiModel {
	/** Logger instance. */
	static final Logger LOGGER = LoggerFactory.getLogger(ApiModel.class);

	/** The class that declares this API endpoint. */
	Class<?> originClass;
	/** The method that implements this API endpoint. */
	Method orignMethod;

	/** The REST endpoint path (e.g., "/users/{id}"). */
	public String restEndPoint;
	/** The HTTP method type (GET, POST, PUT, etc.). */
	public RestTypeRequest restTypeRequest;
	/** The description of this API endpoint. */
	public String description;
	/** The group/tag of this API endpoint (from {@code @ApiDoc(group = ...)}). */
	public String group;
	/** Whether TypeScript progress tracking should be generated for this endpoint. */
	public boolean needGenerateProgress;

	/** The list of class models representing the return types. */
	public List<ClassModel> returnTypes = new ArrayList<>();
	/** The API method name. */
	public String name;
	/** Path parameters mapped by name (e.g., {@code {key}}). */
	public final Map<String, ParameterClassModelList> parameters = new HashMap<>();
	/** Header parameters mapped by name. */
	public final Map<String, ParameterClassModelList> headers = new HashMap<>();
	/** Query parameters mapped by name (e.g., {@code ?key=...}). */
	public final Map<String, ParameterClassModelList> queries = new HashMap<>();
	/** Multi-part form data parameters mapped by name. */
	public final Map<String, ParameterClassModelList> multiPartParameters = new HashMap<>();
	/** Unnamed request body elements. */
	public final List<ParameterClassModelList> unnamedElement = new ArrayList<>();

	/** The list of consumed media types for this endpoint. */
	public List<String> consumes = new ArrayList<>();
	/** The list of produced media types for this endpoint. */
	public List<String> produces = new ArrayList<>();
	/** Security roles: empty list means {@code @PermitAll}, non-empty means {@code @RolesAllowed}, {@code null} means no annotation or {@code @DenyAll}. */
	public List<String> securityRoles;

	private void updateReturnTypes(final Method method, final ModelGroup previousModel) throws Exception {
		// get return type from the user specification:
		final Class<?>[] returnTypeModel = ApiTool.apiAnnotationGetAsyncType(method);
		// LOGGER.info("Get return Type async = {}", returnTypeModel);
		if (returnTypeModel != null) {
			if (returnTypeModel.length == 0) {
				throw new IOException("Create a @AsyncType with empty elements ...");
			}
			for (final Class<?> clazz : returnTypeModel) {
				if (clazz == Void.class || clazz == void.class) {
					this.returnTypes.add(previousModel.add(Void.class));
				} else if (clazz == List.class) {
					throw new IOException("Unmanaged List.class in @AsyncType.");
				} else if (clazz == Map.class) {
					throw new IOException("Unmanaged Map.class in @AsyncType.");
				} else {
					this.returnTypes.add(previousModel.add(clazz));
				}
			}
			if (this.returnTypes.size() == 0) {
				this.produces.clear();
			}
			return;
		}

		final Class<?> returnTypeModelRaw = method.getReturnType();
		LOGGER.trace("Get return Type RAW = {}", returnTypeModelRaw.getCanonicalName());
		if (returnTypeModelRaw == Map.class) {
			LOGGER.trace("Model Map");
			final Type listType = method.getGenericReturnType();
			final ClassModel modelGenerated = ClassModel.getModel(listType, previousModel);
			this.returnTypes.add(modelGenerated);
			LOGGER.trace("Model Map ==> {}", modelGenerated);
			return;
		} else if (returnTypeModelRaw == List.class) {
			LOGGER.trace("Model List");
			final Type listType = method.getGenericReturnType();
			final ClassModel modelGenerated = ClassModel.getModel(listType, previousModel);
			this.returnTypes.add(modelGenerated);
			LOGGER.trace("Model List ==> {}", modelGenerated);
			return;
		} else if (returnTypeModelRaw == Pagination.class) {
			LOGGER.trace("Model Pagination");
			final Type paginationType = method.getGenericReturnType();
			final ClassModel modelGenerated = ClassModel.getModel(paginationType, previousModel);
			this.returnTypes.add(modelGenerated);
			LOGGER.trace("Model Pagination ==> {}", modelGenerated);
			return;
		} else {
			LOGGER.trace("Model Object");
			this.returnTypes.add(previousModel.add(returnTypeModelRaw));
		}
		LOGGER.trace("List of returns elements:");
		for (final ClassModel elem : this.returnTypes) {
			LOGGER.trace("    - {}", elem);
		}
		if (this.returnTypes.size() == 0) {
			this.produces.clear();
		}
	}

	/**
	 * Removes constraint patterns in `{param: constraint}` path segments. Keeps
	 * only `{param}`.
	 *
	 * @param path the original REST path
	 * @return cleaned path with param constraints removed
	 */
	public static String stripPathParamConstraints(final String path) {
		final boolean endsWithSlash = path.endsWith("/") && !path.equals("/");
		final String cleaned = Arrays.stream(path.split("/")).map(segment -> {
			if (segment.startsWith("{") && segment.endsWith("}")) {
				final int colonIndex = segment.indexOf(':');
				if (colonIndex != -1) {
					return "{" + segment.substring(1, colonIndex) + "}";
				}
			}
			return segment;
		}).collect(Collectors.joining("/"));
		if (cleaned.isEmpty()) {
			return "/";
		}
		return endsWithSlash && !cleaned.endsWith("/") ? cleaned + "/" : cleaned;
	}

	/**
	 * Constructs an API model by introspecting the given method and its annotations.
	 * @param clazz the JAX-RS resource class
	 * @param method the method representing the endpoint
	 * @param baseRestEndPoint the base REST path from the class-level {@code @Path}
	 * @param consume the default consumed media types from the class
	 * @param produce the default produced media types from the class
	 * @param previousModel the model group for resolving parameter and return types
	 * @throws Exception if introspection or type resolution fails
	 */
	public ApiModel(final Class<?> clazz, final Method method, final String baseRestEndPoint,
			final List<String> consume, final List<String> produce, final ModelGroup previousModel) throws Exception {
		this.originClass = clazz;
		this.orignMethod = method;

		String tmpPath = ApiTool.apiAnnotationGetPath(method);
		if (tmpPath == null) {
			tmpPath = "";
		}
		this.restEndPoint = stripPathParamConstraints(baseRestEndPoint + "/" + tmpPath);
		this.restTypeRequest = ApiTool.apiAnnotationGetTypeRequest2(method);
		this.name = method.getName();

		this.description = ApiTool.apiAnnotationGetOperationDescription(method);
		this.group = ApiTool.apiAnnotationGetOperationGroup(method);
		this.consumes = ApiTool.apiAnnotationGetConsumes2(consume, method);
		this.produces = ApiTool.apiAnnotationProduces2(produce, method);
		LOGGER.trace("    [{}] {} => {}/{}", baseRestEndPoint, this.name, this.restEndPoint);
		this.needGenerateProgress = ApiTool.apiAnnotationTypeScriptProgress(method);
		this.securityRoles = ApiTool.apiAnnotationGetSecurityRoles(clazz, method);

		updateReturnTypes(method, previousModel);
		LOGGER.trace("         return: {}", this.returnTypes.size());
		for (final ClassModel elem : this.returnTypes) {
			LOGGER.trace("             - {}", elem);
		}

		// LOGGER.info(" Parameters:");
		Parameter[] parameters = method.getParameters();
		for (int iii = 0; iii < parameters.length; iii++) {
			Parameter parameter = parameters[iii];
			// Security context are internal parameter (not available from API)
			if (ApiTool.apiAnnotationIsContext(method, iii)) {
				continue;
			}
			final Class<?> parameterType = parameter.getType();
			final List<ClassModel> parameterModel = new ArrayList<>();
			final Class<?>[] asyncType = ApiTool.apiAnnotationGetAsyncType(method, iii);
			if (asyncType != null) {
				for (final Class<?> elem : asyncType) {
					final ClassModel modelGenerated = ClassModel.getModel(elem, previousModel);
					parameterModel.add(modelGenerated);
				}
			} else if (parameterType == List.class) {
				final Type parameterrizedType = parameter.getParameterizedType();
				final ClassModel modelGenerated = ClassModel.getModel(parameterrizedType, previousModel);
				parameterModel.add(modelGenerated);
			} else if (parameterType == Map.class) {
				final Type parameterrizedType = parameter.getParameterizedType();
				final ClassModel modelGenerated = ClassModel.getModel(parameterrizedType, previousModel);
				parameterModel.add(modelGenerated);
			} else {
				parameterModel.add(previousModel.add(parameterType));
			}
			final Context contextAnnotation = AnnotationTools.get(parameter, Context.class);
			final PaginationContext paginationContextAnnotation = AnnotationTools.get(parameter,
					PaginationContext.class);
			final HeaderParam headerParam = AnnotationTools.get(parameter, HeaderParam.class);
			final Valid validParam = AnnotationTools.get(parameter, Valid.class);
			final ValidGroup validGroupParam = AnnotationTools.get(parameter, ValidGroup.class);
			final String pathParam = ApiTool.apiAnnotationGetPathParam(method, iii);
			final String queryParam = ApiTool.apiAnnotationGetQueryParam(method, iii);
			final String formDataParam = ApiTool.apiAnnotationGetFormDataParam(method, iii);
			final boolean apiInputOptional = ApiTool.apiAnnotationGetApiInputOptional(method, iii);
			if (queryParam != null) {
				if (!this.queries.containsKey(queryParam)) {
					this.queries.put(queryParam,
							new ParameterClassModelList(validParam, validGroupParam, parameterModel, apiInputOptional));
				}
			} else if (pathParam != null) {
				if (!this.parameters.containsKey(pathParam)) {
					this.parameters.put(pathParam,
							new ParameterClassModelList(validParam, validGroupParam, parameterModel, apiInputOptional));
				}
			} else if (formDataParam != null) {
				if (!this.multiPartParameters.containsKey(formDataParam)) {
					this.multiPartParameters.put(formDataParam,
							new ParameterClassModelList(validParam, validGroupParam, parameterModel, apiInputOptional));
				}
			} else if (contextAnnotation != null) {
				// out of scope parameters
			} else if (paginationContextAnnotation != null) {
				// @PaginationContext: resolved server-side from request headers / query params,
				// not exposed to the generated client signature.
			} else if (headerParam != null) {
				if (!this.headers.containsKey(headerParam.value())) {
					this.headers.put(headerParam.value(),
							new ParameterClassModelList(validParam, validGroupParam, parameterModel, apiInputOptional));
				}
			} else {
				this.unnamedElement.add(
						new ParameterClassModelList(validParam, validGroupParam, parameterModel, apiInputOptional));
			}
		}
		if (this.unnamedElement.size() > 1) {
			throw new IOException("Can not parse the API, enmpty element is more than 1 in " + this.name);
		}
	}
}
