package test.atriasoft.archidata.externalRestApi;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.atriasoft.archidata.annotation.method.PaginationContext;
import org.atriasoft.archidata.dataAccess.model.Pagination;
import org.atriasoft.archidata.externalRestApi.AnalyzeApi;
import org.atriasoft.archidata.externalRestApi.TsGenerateApi;
import org.atriasoft.archidata.model.OIDGenericDataSoftDelete;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

class TestTypeScriptApiGenerationPagination {
	private static final Logger LOGGER = LoggerFactory.getLogger(TestTypeScriptApiGenerationPagination.class);

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SampleItem extends OIDGenericDataSoftDelete {
		public String label;
	}

	@Path("sample-paginated")
	public static class SamplePaginatedResource {
		@GET
		public Pagination<SampleItem> list(
				@PaginationContext final org.atriasoft.archidata.dataAccess.model.PaginationContext page) {
			return null;
		}
	}

	@Test
	void paginatedEndpointGeneratesPaginationHelper() throws Exception {
		final AnalyzeApi api = new AnalyzeApi();
		api.addAllApi(List.of(SamplePaginatedResource.class));

		final Map<java.nio.file.Path, String> generation = TsGenerateApi.generateApi(api);
		for (final java.nio.file.Path file : generation.keySet()) {
			LOGGER.info("generated file: {}", file);
		}

		final String resourceTs = generation.get(Paths.get("api/sample-paginated-resource.ts"));
		Assertions.assertNotNull(resourceTs, "expected generated resource file");

		Assertions.assertTrue(resourceTs.contains("Promise<Pagination<SampleItem>>"),
				"expected the TS return type to be Pagination<SampleItem>:\n" + resourceTs);
		Assertions.assertTrue(resourceTs.contains("RESTRequestPaginatedJson"),
				"expected the paginated REST helper to be called:\n" + resourceTs);
		Assertions.assertTrue(resourceTs.contains("Pagination,"),
				"expected Pagination to be imported from rest-tools:\n" + resourceTs);
		Assertions.assertFalse(resourceTs.contains("PaginationContext"),
				"expected @PaginationContext parameter not to leak into the client signature:\n" + resourceTs);
	}
}
