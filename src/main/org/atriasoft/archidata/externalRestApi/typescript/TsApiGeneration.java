package org.atriasoft.archidata.externalRestApi.typescript;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.atriasoft.archidata.annotation.checker.GroupRead;
import org.atriasoft.archidata.externalRestApi.model.ApiGroupModel;
import org.atriasoft.archidata.externalRestApi.model.ApiModel;
import org.atriasoft.archidata.externalRestApi.model.ClassEnumModel;
import org.atriasoft.archidata.externalRestApi.model.ClassListModel;
import org.atriasoft.archidata.externalRestApi.model.ClassPaginationModel;
import org.atriasoft.archidata.externalRestApi.model.ClassMapModel;
import org.atriasoft.archidata.externalRestApi.model.ClassModel;
import org.atriasoft.archidata.externalRestApi.model.ClassObjectModel;
import org.atriasoft.archidata.externalRestApi.model.ParameterClassModel;
import org.atriasoft.archidata.externalRestApi.model.ParameterClassModelList;
import org.atriasoft.archidata.externalRestApi.model.RestTypeRequest;
import org.atriasoft.archidata.externalRestApi.typescript.ImportModel.ModeImport;
import org.atriasoft.archidata.externalRestApi.typescript.ImportModel.PairElem;
import org.atriasoft.archidata.externalRestApi.typescript.TsClassElement.DefinedPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.core.MediaType;

/**
 * Generates TypeScript API client files with REST endpoint bindings.
 */
public class TsApiGeneration {
	/** Private constructor to prevent instantiation of this utility class. */
	private TsApiGeneration() {
		// Utility class
	}

	/** Logger for this class. */
	static final Logger LOGGER = LoggerFactory.getLogger(TsApiGeneration.class);

	/**
	 * Gets the base header comment for generated TypeScript API files.
	 * @return the header string
	 */
	public static String getBaseHeader() {
		return """
				/**
				 * Interface of the server (auto-generated code)
				 */
				""";
	}

	/**
	 * Generates a TypeScript type reference for an enum model.
	 * @param valid whether validation is active
	 * @param groups the validation groups
	 * @param model the enum model
	 * @param tsGroup the group registry for resolving type references
	 * @param imports the import model to track dependencies
	 * @param partialObject whether to generate as partial type
	 * @return the TypeScript type name string
	 */
	public static String generateClassEnumModelTypescript(
			final boolean valid,
			final Class<?>[] groups,
			final ClassEnumModel model,
			final TsClassElementGroup tsGroup,
			final ImportModel imports,
			final boolean partialObject) {
		final ParameterClassModel param = tsGroup.find(false, null, model);
		imports.add(false, null, model);
		final TsClassElement tsModel = tsGroup.find(model);
		return tsModel.getTypeName();
	}

	/**
	 * Generates a TypeScript type reference for an object model.
	 * @param valid whether validation is active
	 * @param groups the validation groups
	 * @param model the object model
	 * @param tsGroup the group registry for resolving type references
	 * @param imports the import model to track dependencies
	 * @param partialObject whether to wrap in Partial type
	 * @return the TypeScript type name string
	 */
	public static String generateClassObjectModelTypescript(
			final boolean valid,
			final Class<?>[] groups,
			final ClassObjectModel model,
			final TsClassElementGroup tsGroup,
			final ImportModel imports,
			final boolean partialObject) {
		final TsClassElement tsModel = tsGroup.find(model);
		final ParameterClassModel modelImports = new ParameterClassModel(valid, groups, tsModel.models.get(0));
		imports.add(valid, groups, tsModel.models.get(0));
		String out = tsModel.getTypeName(modelImports);
		if (tsModel.nativeType == DefinedPosition.NORMAL) {
			out = modelImports.getType();
		}
		if (partialObject) {
			return "Partial<" + out + ">";
		}
		return out;
	}

	/**
	 * Generates a TypeScript type reference for a map model.
	 * @param valid whether validation is active
	 * @param groups the validation groups
	 * @param model the map model
	 * @param tsGroup the group registry for resolving type references
	 * @param imports the import model to track dependencies
	 * @param partialObject whether to generate as partial type
	 * @return the TypeScript map type string
	 */
	public static String generateClassMapModelTypescript(
			final boolean valid,
			final Class<?>[] groups,
			final ClassMapModel model,
			final TsClassElementGroup tsGroup,
			final ImportModel imports,
			final boolean partialObject) {
		final StringBuilder out = new StringBuilder();
		out.append("{[key: ");
		out.append(generateClassModelTypescript(valid, groups, model.keyModel, tsGroup, imports, partialObject));
		out.append("]: ");
		out.append(generateClassModelTypescript(valid, groups, model.valueModel, tsGroup, imports, partialObject));
		out.append(";}");
		return out.toString();
	}

	/**
	 * Generates a TypeScript type reference for a list model.
	 * @param valid whether validation is active
	 * @param groups the validation groups
	 * @param model the list model
	 * @param tsGroup the group registry for resolving type references
	 * @param imports the import model to track dependencies
	 * @param partialObject whether to generate as partial type
	 * @return the TypeScript array type string
	 */
	public static String generateClassListModelTypescript(
			final boolean valid,
			final Class<?>[] groups,
			final ClassListModel model,
			final TsClassElementGroup tsGroup,
			final ImportModel imports,
			final boolean partialObject) {
		final StringBuilder out = new StringBuilder();
		out.append(generateClassModelTypescript(valid, groups, model.valueModel, tsGroup, imports, partialObject));
		out.append("[]");
		return out.toString();
	}

	/**
	 * Generates a TypeScript {@code Pagination<T>} type reference for a pagination model.
	 *
	 * <p>The {@code Pagination<T>} type itself is exported by the {@code rest-tools} module;
	 * its import is added separately via the {@code toolImports} machinery in the caller.
	 *
	 * @param valid whether validation is active
	 * @param groups the validation groups
	 * @param model the pagination model
	 * @param tsGroup the group registry for resolving type references
	 * @param imports the import model to track dependencies
	 * @param partialObject whether to generate as partial type
	 * @return the TypeScript {@code Pagination<T>} type string
	 */
	public static String generateClassPaginationModelTypescript(
			final boolean valid,
			final Class<?>[] groups,
			final ClassPaginationModel model,
			final TsClassElementGroup tsGroup,
			final ImportModel imports,
			final boolean partialObject) {
		final StringBuilder out = new StringBuilder();
		out.append("Pagination<");
		out.append(generateClassModelTypescript(valid, groups, model.valueModel, tsGroup, imports, partialObject));
		out.append(">");
		return out.toString();
	}

	/**
	 * Generates a TypeScript type reference for any class model by dispatching to the appropriate handler.
	 * @param valid whether validation is active
	 * @param groups the validation groups
	 * @param model the class model to generate a type for
	 * @param tsGroup the group registry for resolving type references
	 * @param imports the import model to track dependencies
	 * @param partialObject whether to generate as partial type
	 * @return the TypeScript type string
	 */
	public static String generateClassModelTypescript(
			final boolean valid,
			final Class<?>[] groups,
			final ClassModel model,
			final TsClassElementGroup tsGroup,
			final ImportModel imports,
			final boolean partialObject) {
		if (model instanceof final ClassObjectModel objectModel) {
			return generateClassObjectModelTypescript(valid, groups, objectModel, tsGroup, imports, partialObject);
		}
		if (model instanceof final ClassListModel listModel) {
			return generateClassListModelTypescript(valid, groups, listModel, tsGroup, imports, partialObject);
		}
		if (model instanceof final ClassPaginationModel paginationModel) {
			return generateClassPaginationModelTypescript(valid, groups, paginationModel, tsGroup, imports,
					partialObject);
		}
		if (model instanceof final ClassMapModel mapModel) {
			return generateClassMapModelTypescript(valid, groups, mapModel, tsGroup, imports, partialObject);
		}
		if (model instanceof final ClassEnumModel enumModel) {
			return generateClassEnumModelTypescript(valid, groups, enumModel, tsGroup, imports, partialObject);
		}
		throw new RuntimeException("Impossible model:" + model);
	}

	/**
	 * Generates a TypeScript type reference for a list of models as a union type.
	 * @param models the parameter class model list
	 * @param tsGroup the group registry for resolving type references
	 * @param imports the import model to track dependencies
	 * @param partialObject whether to generate as partial type
	 * @return the TypeScript union type string, or "void" if empty
	 */
	public static String generateClassModelsTypescript(
			final ParameterClassModelList models,
			final TsClassElementGroup tsGroup,
			final ImportModel imports,
			final boolean partialObject) {
		if (models == null || models.models() == null || models.models().size() == 0) {
			return "void";
		}
		final StringBuilder out = new StringBuilder();
		boolean isFirst = true;
		for (final ClassModel model : models.models()) {
			if (isFirst) {
				isFirst = false;
			} else {
				out.append(" | ");
			}
			final String data = generateClassModelTypescript(models.valid(), models.groups(), model, tsGroup, imports,
					partialObject);
			out.append(data);
		}
		return out.toString();
	}

	/**
	 * Capitalizes the first letter of a string.
	 * @param str the string to capitalize
	 * @return the string with its first letter capitalized, or the original if null/empty
	 */
	public static String capitalizeFirstLetter(final String str) {
		if (str == null || str.isEmpty()) {
			return str;
		}
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}

	/**
	 * Generates a TypeScript API client file for a resource group.
	 * @param element the API group model containing endpoint definitions
	 * @param tsGroup the group registry for resolving type references
	 * @param generation the map of file paths to generated content
	 */
	public static void generateApiFile(
			final ApiGroupModel element,
			final TsClassElementGroup tsGroup,
			final Map<Path, String> generation) {
		final StringBuilder data = new StringBuilder();

		data.append("export namespace ");
		data.append(element.name);
		data.append(" {");
		final ImportModel imports = new ImportModel();
		final Set<String> toolImports = new HashSet<>();

		// TODO: alphabetical order ...

		for (final ApiModel interfaceElement : element.interfaces) {
			final List<String> consumes = interfaceElement.consumes;
			final List<String> produces = interfaceElement.produces;
			final boolean needGenerateProgress = interfaceElement.needGenerateProgress;
			final String returnModelNameIfComplex = capitalizeFirstLetter(interfaceElement.name) + "TypeReturn";
			final String returnComplexModel = TsClassElement.generateLocalModel(returnModelNameIfComplex,
					interfaceElement.returnTypes, tsGroup, imports);
			if (returnComplexModel != null) {
				data.append("\n\n");
				data.append(returnComplexModel.replaceAll("(?m)^", "\t"));
				for (final ClassModel elem : interfaceElement.returnTypes) {
					// TODO maybe need to update this with the type of zod requested (like update, create ...)
					for (final ClassModel elem2 : interfaceElement.returnTypes) {
						imports.addCheck(elem2);
					}
				}
			}
			if (interfaceElement.description != null) {
				data.append("\n\t/**\n\t * ");
				data.append(interfaceElement.description);
				data.append("\n\t */");
			}
			data.append("\n\texport function ");
			data.append(interfaceElement.name);
			data.append("({\n\t\t\trestConfig,");
			if (!interfaceElement.queries.isEmpty()) {
				data.append("\n\t\t\tqueries,");
			}
			if (!interfaceElement.parameters.isEmpty()) {
				data.append("\n\t\t\tparams,");
			}
			if (produces != null && produces.size() > 1) {
				data.append("\n\t\t\tproduce,");
			}
			if (interfaceElement.unnamedElement.size() == 1 || interfaceElement.multiPartParameters.size() != 0) {
				data.append("\n\t\t\tdata,");
			}
			if (!interfaceElement.headers.isEmpty()) {
				data.append("\n\t\t\theaders,");
			}
			if (needGenerateProgress) {
				data.append("\n\t\t\tcallbacks,");
			}
			data.append("\n\t\t}: {");
			data.append("\n\t\trestConfig: RESTConfig,");
			toolImports.add("RESTConfig");
			if (!interfaceElement.queries.isEmpty()) {
				data.append("\n\t\tqueries: {");
				for (final Entry<String, ParameterClassModelList> queryEntry : interfaceElement.queries.entrySet()) {
					data.append("\n\t\t\t");
					data.append(queryEntry.getKey());
					data.append("?: ");
					data.append(generateClassModelsTypescript(queryEntry.getValue(), tsGroup, imports, false));
					data.append(",");
				}
				data.append("\n\t\t},");
			}
			if (!interfaceElement.parameters.isEmpty()) {
				data.append("\n\t\tparams: {");
				for (final Entry<String, ParameterClassModelList> paramEntry : interfaceElement.parameters.entrySet()) {
					data.append("\n\t\t\t");
					data.append(paramEntry.getKey());
					data.append(": ");
					data.append(generateClassModelsTypescript(paramEntry.getValue(), tsGroup, imports, false));
					data.append(",");
				}
				data.append("\n\t\t},");
			}
			if (interfaceElement.unnamedElement.size() == 1) {
				data.append("\n\t\tdata: ");
				data.append(generateClassModelsTypescript(interfaceElement.unnamedElement.get(0), tsGroup, imports,
						interfaceElement.restTypeRequest == RestTypeRequest.PATCH));

				data.append(",");
			} else if (interfaceElement.multiPartParameters.size() != 0) {
				data.append("\n\t\tdata: {");
				for (final Entry<String, ParameterClassModelList> pathEntry : interfaceElement.multiPartParameters
						.entrySet()) {
					data.append("\n\t\t\t");
					data.append(pathEntry.getKey());
					if (pathEntry.getValue().optional()) {
						data.append("?");
					}
					data.append(": ");
					data.append(generateClassModelsTypescript(pathEntry.getValue(), tsGroup, imports,
							interfaceElement.restTypeRequest == RestTypeRequest.PATCH));

					data.append(",");
				}
				data.append("\n\t\t},");
			}
			if (!interfaceElement.headers.isEmpty()) {
				data.append("\n\t\theaders?: {");
				for (final Entry<String, ParameterClassModelList> headerEntry : interfaceElement.headers.entrySet()) {
					data.append("\n\t\t\t");
					data.append(headerEntry.getKey());
					if (headerEntry.getValue().optional()) {
						data.append("?");
					}
					data.append(": ");
					data.append(generateClassModelsTypescript(headerEntry.getValue(), tsGroup, imports, false));
					data.append(",");
				}
				data.append("\n\t\t},");
			}
			if (produces != null && produces.size() > 1) {
				data.append("\n\t\tproduce: ");
				String isFist = null;
				for (final String elem : produces) {
					String lastElement = null;

					if (MediaType.APPLICATION_JSON.equals(elem)) {
						lastElement = "HTTPMimeType.JSON";
						toolImports.add("HTTPMimeType");
					}
					if (MediaType.MULTIPART_FORM_DATA.equals(elem)) {
						lastElement = "HTTPMimeType.MULTIPART";
						toolImports.add("HTTPMimeType");
					}
					if (lastElement != null) {
						if (isFist == null) {
							isFist = lastElement;
						} else {
							data.append(" | ");
						}
						data.append(lastElement);
					} else {
						LOGGER.error("Unmanaged model type: {}", elem);
					}
				}
				data.append(",");
			}
			if (needGenerateProgress) {
				data.append("\n\t\tcallbacks?: RESTCallbacks,");
				toolImports.add("RESTCallbacks");
			}
			final boolean isPaginated = interfaceElement.returnTypes.stream()
					.anyMatch(ClassPaginationModel.class::isInstance);
			data.append("\n\t}): Promise<");
			if (returnComplexModel != null) {
				data.append(returnModelNameIfComplex);
				data.append("> {");
				data.append("\n\t\treturn RESTRequestJson({");
				toolImports.add("RESTRequestJson");
			} else {
				final String returnType = generateClassModelsTypescript(new ParameterClassModelList(true,
						new Class<?>[] { GroupRead.class }, interfaceElement.returnTypes, false), tsGroup, imports,
						false);
				data.append(returnType);
				data.append("> {");
				if ("void".equals(returnType)) {
					data.append("\n\t\treturn RESTRequestVoid({");
					toolImports.add("RESTRequestVoid");
				} else if (isPaginated) {
					for (final ClassModel elem : interfaceElement.returnTypes) {
						imports.addCheck(elem);
					}
					data.append("\n\t\treturn RESTRequestPaginatedJson({");
					toolImports.add("RESTRequestPaginatedJson");
					toolImports.add("Pagination");
				} else {
					for (final ClassModel elem : interfaceElement.returnTypes) {
						imports.addCheck(elem);
					}
					data.append("\n\t\treturn RESTRequestJson({");
					toolImports.add("RESTRequestJson");
				}
			}
			data.append("\n\t\t\trestModel: {");
			data.append("\n\t\t\t\tendPoint: \"");
			data.append(interfaceElement.restEndPoint);
			data.append("\",");
			data.append("\n\t\t\t\trequestType: HTTPRequestModel.");
			toolImports.add("HTTPRequestModel");
			data.append(interfaceElement.restTypeRequest);
			data.append(",");
			if (consumes != null) {
				for (final String elem : consumes) {
					if (MediaType.APPLICATION_JSON.equals(elem)) {
						data.append("\n\t\t\t\tcontentType: HTTPMimeType.JSON,");
						toolImports.add("HTTPMimeType");
						break;
					} else if (MediaType.MULTIPART_FORM_DATA.equals(elem)) {
						data.append("\n\t\t\t\tcontentType: HTTPMimeType.MULTIPART,");
						toolImports.add("HTTPMimeType");
						break;
					} else if (MediaType.TEXT_PLAIN.equals(elem)) {
						data.append("\n\t\t\t\tcontentType: HTTPMimeType.TEXT_PLAIN,");
						toolImports.add("HTTPMimeType");
						break;
					}
				}
			} else if (RestTypeRequest.DELETE.equals(interfaceElement.restTypeRequest)) {
				data.append("\n\t\t\t\tcontentType: HTTPMimeType.TEXT_PLAIN,");
				toolImports.add("HTTPMimeType");
			}
			if (produces != null) {
				if (produces.size() > 1) {
					data.append("\n\t\t\t\taccept: produce,");
				} else {
					final String returnType = generateClassModelsTypescript(new ParameterClassModelList(true,
							new Class<?>[] { GroupRead.class }, interfaceElement.returnTypes, false), tsGroup, imports,
							false);
					if (!"void".equals(returnType)) {
						for (final String elem : produces) {
							if (MediaType.APPLICATION_JSON.equals(elem)) {
								data.append("\n\t\t\t\taccept: HTTPMimeType.JSON,");
								toolImports.add("HTTPMimeType");
								break;
							}
						}
					}
				}
			}
			data.append("\n\t\t\t},");
			data.append("\n\t\t\trestConfig,");
			if (!interfaceElement.parameters.isEmpty()) {
				data.append("\n\t\t\tparams,");
			}
			if (!interfaceElement.queries.isEmpty()) {
				data.append("\n\t\t\tqueries,");
			}
			if (interfaceElement.unnamedElement.size() == 1) {
				data.append("\n\t\t\tdata,");
			} else if (interfaceElement.multiPartParameters.size() != 0) {
				data.append("\n\t\t\tdata,");
			}
			if (needGenerateProgress) {
				data.append("\n\t\t\tcallbacks,");
			}
			if (!interfaceElement.headers.isEmpty()) {
				data.append("\n\t\t\theaders,");
			}
			data.append("\n\t\t}");
			if ("void".equals(returnModelNameIfComplex)) {
				// nothing to do...
			} else if (returnComplexModel != null) {
				data.append(", is");
				data.append(returnModelNameIfComplex);
			} else if (isPaginated) {
				// Pagination<T> body is the plain item list; runtime body check
				// is omitted in this version (Pagination<T> ships without a list
				// checker today). The helper accepts an undefined checker.
			} else {
				final TsClassElement retType = tsGroup.find(interfaceElement.returnTypes.get(0));
				if (retType.getCheckType() != null) {
					if ("isvoid".equals(retType.getCheckType())) {
						// nothing to do...
					} else if (retType.nativeType != DefinedPosition.NATIVE) {
						data.append(", ");
						data.append(retType.getCheckType());
						imports.add(true, new Class<?>[] { GroupRead.class }, interfaceElement.returnTypes.get(0));
					}
				}
			}
			data.append(");");
			data.append("\n\t};");
		}
		data.append("\n}\n");
		final StringBuilder out = new StringBuilder();
		out.append(getBaseHeader());
		final List<String> toolImportsList = new ArrayList<>(toolImports);
		Collections.sort(toolImportsList);
		if (toolImportsList.size() != 0) {
			out.append("import {");
			for (final String elem : toolImportsList) {
				out.append("\n\t");
				out.append(elem);
				out.append(",");
			}
			out.append("\n} from \"../rest-tools\";\n");
		}
		if (imports.hasZodImport()) {
			out.append("import { z as zod } from \"zod\"\n");
		}
		final Set<String> finalImportSet = new TreeSet<>();
		for (final Entry<ClassModel, Set<PairElem>> importElem : imports.data.entrySet()) {
			final TsClassElement tsModel = tsGroup.find(importElem.getKey());
			if (tsModel == null) {
				LOGGER.trace("Fail to get ts object ...");
				continue;
			}
			if (tsModel.nativeType == DefinedPosition.NATIVE) {
				continue;
			}
			for (final PairElem pair : importElem.getValue()) {
				final ParameterClassModel modelSpecialized = new ParameterClassModel(pair.valid(), pair.groups(),
						importElem.getKey());
				switch (pair.mode()) {
					case ModeImport.IS:
						finalImportSet.add(tsModel.getCheckType(modelSpecialized));
						break;
					case ModeImport.TYPE:
						finalImportSet.add(tsModel.getTypeName(modelSpecialized));
						break;
					case ModeImport.ZOD:
						finalImportSet.add(tsModel.getZodName(modelSpecialized));
						break;
				}
			}
		}
		if (finalImportSet.size() != 0) {
			out.append("import {");
			for (final String elem : finalImportSet) {
				out.append("\n\t");
				out.append(elem);
				out.append(",");
			}
			out.append("\n} from \"../model\";\n");
		}
		out.append("\n");
		out.append(data.toString());
		final String fileName = TsClassElement.determineFileName(element.name);
		generation.put(Paths.get("api").resolve(fileName + ".ts"), out.toString());
	}

}