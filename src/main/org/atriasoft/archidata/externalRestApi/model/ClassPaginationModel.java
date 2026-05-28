package org.atriasoft.archidata.externalRestApi.model;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * Represents a {@code Pagination<T>} return type.
 *
 * <p>From the JSON shape standpoint, a paginated response is identical to a
 * {@code List<T>} (the response body is a plain list of items). The
 * {@code Pagination} wrapper exists solely to signal to the code generators
 * that pagination metadata is conveyed via HTTP response headers, and that the
 * client should call the paginated helper rather than the plain JSON helper.
 *
 * <p>From the model-graph standpoint, this class behaves like
 * {@link ClassListModel} — it delegates {@code getAlls()} and
 * {@code getDependencyGroupModels()} to the item type, so the generated DTOs
 * for {@code T} are produced exactly as before.
 */
public class ClassPaginationModel extends ClassModel {

	/** The class model for the item type of the paginated list. */
	public ClassModel valueModel;

	/**
	 * Constructs a pagination model from a parameterized {@code Pagination<T>}.
	 *
	 * @param paginationType the parameterized {@code Pagination<T>} type
	 * @param previousModel the model group used to resolve {@code T}
	 * @throws IOException if the item type cannot be resolved
	 */
	public ClassPaginationModel(final ParameterizedType paginationType, final ModelGroup previousModel)
			throws IOException {
		final Type itemType = paginationType.getActualTypeArguments()[0];
		this.valueModel = getModel(itemType, previousModel);
	}

	@Override
	public String toString() {
		return "ClassPaginationModel [valueModel=" + this.valueModel + "]";
	}

	@Override
	public void analyze(final ModelGroup group) throws IOException {
		throw new IOException("Analyze can not be done at this phase for Pagination...");
	}

	@Override
	public Set<ClassModel> getAlls() {
		return this.valueModel.getAlls();
	}

	@Override
	public Set<ClassModel> getDependencyGroupModels() {
		return this.valueModel.getDependencyGroupModels();
	}
}
