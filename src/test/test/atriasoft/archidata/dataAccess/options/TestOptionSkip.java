package test.atriasoft.archidata.dataAccess.options;

import java.io.IOException;
import java.util.List;

import org.atriasoft.archidata.dataAccess.options.Limit;
import org.atriasoft.archidata.dataAccess.options.OrderBy;
import org.atriasoft.archidata.dataAccess.options.OrderItem;
import org.atriasoft.archidata.dataAccess.options.Skip;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import jakarta.persistence.Id;
import test.atriasoft.archidata.ConfigureDb;
import test.atriasoft.archidata.StepwiseExtension;

@ExtendWith(StepwiseExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestOptionSkip {

	public static class Model {
		@Id
		public ObjectId _id = null;
		public String name;
	}

	private static final int TOTAL = 10;

	@BeforeAll
	static void setup() throws Exception {
		ConfigureDb.configure();
		for (int i = 0; i < TOTAL; i++) {
			final Model row = new Model();
			row.name = String.format("item-%02d", i);
			ConfigureDb.da.insert(row);
		}
	}

	@AfterAll
	static void cleanup() throws IOException {
		ConfigureDb.clear();
	}

	@Order(1)
	@Test
	void rejectsNegativeSkip() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new Skip(-1L));
	}

	@Order(2)
	@Test
	void zeroSkipKeepsAllItems() throws Exception {
		final List<Model> rows = ConfigureDb.da.gets(Model.class, new Skip(0L),
				new OrderBy(new OrderItem("name", OrderItem.Order.ASC)));
		Assertions.assertEquals(TOTAL, rows.size());
		Assertions.assertEquals("item-00", rows.get(0).name);
	}

	@Order(3)
	@Test
	void skipCombinedWithLimitYieldsSlice() throws Exception {
		final List<Model> rows = ConfigureDb.da.gets(Model.class, new Skip(3L), new Limit(2L),
				new OrderBy(new OrderItem("name", OrderItem.Order.ASC)));
		Assertions.assertEquals(2, rows.size());
		Assertions.assertEquals("item-03", rows.get(0).name);
		Assertions.assertEquals("item-04", rows.get(1).name);
	}

	@Order(4)
	@Test
	void skipBeyondCollectionReturnsEmpty() throws Exception {
		final List<Model> rows = ConfigureDb.da.gets(Model.class, new Skip(TOTAL + 10L), new Limit(5L),
				new OrderBy(new OrderItem("name", OrderItem.Order.ASC)));
		Assertions.assertEquals(0, rows.size());
	}
}
