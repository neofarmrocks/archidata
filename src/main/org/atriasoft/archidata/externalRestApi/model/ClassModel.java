package org.atriasoft.archidata.externalRestApi.model;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.atriasoft.archidata.annotation.apiGenerator.ApiGenerationMode;
import org.atriasoft.archidata.dataAccess.model.Pagination;
import org.atriasoft.archidata.tools.AnnotationCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class representing a data model extracted from a Java class for API generation.
 *
 * <p>Subclasses represent different kinds of models: objects ({@link ClassObjectModel}),
 * enums ({@link ClassEnumModel}), lists ({@link ClassListModel}), and maps ({@link ClassMapModel}).
 */
public abstract class ClassModel {
	/** Logger instance. */
	private static final Logger LOGGER = LoggerFactory.getLogger(ClassModel.class);
	/** Whether this model has already been analyzed. */
	protected boolean analyzeDone = false;
	/** The original Java class this model was created from. */
	protected Class<?> originClasses = null;
	/** The API generation mode controlling read/create/update visibility. */
	protected ApiGenerationMode apiGenerationMode = AnnotationCreator.createAnnotation(ApiGenerationMode.class);
	/** The set of models this model depends on. */
	protected Set<ClassModel> dependencyModels = new HashSet<>();

	/**
	 * Returns the original Java class this model was created from.
	 * @return the origin class
	 */
	public Class<?> getOriginClasses() {
		return this.originClasses;
	}

	/**
	 * Returns the API generation mode for this model.
	 * @return the API generation mode annotation
	 */
	public ApiGenerationMode getApiGenerationMode() {
		return this.apiGenerationMode;
	}

	protected boolean isCompatible(final Class<?> clazz) {
		return this.originClasses == clazz;
	}

	/**
	 * Returns the set of models this model depends on.
	 * @return the dependency models
	 * @deprecated use {@link #getDependencyGroupModels()} instead
	 */
	@Deprecated
	public Set<ClassModel> getDependencyModels() {
		return this.dependencyModels;
	}

	/**
	 * Returns the set of dependency group models for this model.
	 * @return the dependency group models
	 */
	public abstract Set<ClassModel> getDependencyGroupModels();

	/**
	 * Resolves a {@link ClassModel} for the given type, handling parameterized types (List, Map).
	 * @param type the Java type to resolve
	 * @param previousModel the model group for looking up or creating models
	 * @return the resolved class model
	 * @throws IOException if the type cannot be resolved
	 */
	public static ClassModel getModel(final Type type, final ModelGroup previousModel) throws IOException {
		if (type instanceof final ParameterizedType paramType) {
			final Type[] typeArguments = paramType.getActualTypeArguments();
			final Type rawType = paramType.getRawType();
			if (rawType instanceof final Class<?> rawClass) {
				if (List.class.isAssignableFrom(rawClass)) {
					return new ClassListModel(typeArguments[0], previousModel);
				}
				if (Map.class.isAssignableFrom(rawClass)) {
					return new ClassMapModel(typeArguments[0], typeArguments[1], previousModel);
				}
				if (Pagination.class.isAssignableFrom(rawClass)) {
					return new ClassPaginationModel(paramType, previousModel);
				}
			}
			throw new IOException("Fail to manage parametrized type... '" + rawType + "'");
		}
		return previousModel.add((Class<?>) type);
	}

	/**
	 * Resolves a {@link ClassModel} for the given class and its parameterized type.
	 * @param clazz the raw class
	 * @param parameterizedType the generic type information
	 * @param previousModel the model group for looking up or creating models
	 * @return the resolved class model
	 * @throws IOException if the type cannot be resolved
	 */
	public static ClassModel getModelBase(
			final Class<?> clazz,
			final Type parameterizedType,
			final ModelGroup previousModel) throws IOException {
		/*
		if (clazz == List.class) {
			return new ClassListModel((ParameterizedType) parameterizedType, previousModel);
		}
		if (clazz == Map.class) {
			return new ClassMapModel((ParameterizedType) parameterizedType, previousModel);
		}
		return previousModel.add(clazz);
		*/
		return getModel(parameterizedType, previousModel);
	}

	/**
	 * Resolves a {@link ClassModel} for the given class (non-parameterized).
	 * @param type the Java class to resolve
	 * @param previousModel the model group for looking up or creating models
	 * @return the resolved class model
	 * @throws IOException if the type is a raw List or Map (requires parameterized type)
	 */
	public static ClassModel getModel(final Class<?> type, final ModelGroup previousModel) throws IOException {
		if (type == List.class) {
			throw new IOException("Fail to manage parametrized type...");
		}
		if (type == Map.class) {
			throw new IOException("Fail to manage parametrized type...");
		}
		return previousModel.add(type);
	}

	/**
	 * Analyzes this model, resolving its fields, dependencies, and inheritance.
	 * @param group the model group used for resolving referenced types
	 * @throws Exception if analysis fails
	 */
	public abstract void analyze(final ModelGroup group) throws Exception;

	/**
	 * Returns all class models that this model encompasses (including itself).
	 * @return the set of all constituent models
	 */
	public abstract Set<ClassModel> getAlls();

	/**
	 * Returns the list of field names that are read-only (not creatable and not updatable).
	 * @return the read-only field names
	 */
	public List<String> getReadOnlyFields() {
		return List.of();
	}

	/**
	 * Returns the list of field names that are allowed during creation.
	 * @return the creatable field names
	 */
	public List<String> getCreateFields() {
		return List.of();
	}

	/**
	 * Returns the list of field names that are allowed during updates.
	 * @return the updatable field names
	 */
	public List<String> getUpdateFields() {
		return List.of();
	}

}
