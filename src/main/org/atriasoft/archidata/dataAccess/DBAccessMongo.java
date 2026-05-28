package org.atriasoft.archidata.dataAccess;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.atriasoft.archidata.annotation.AnnotationTools;
import org.atriasoft.archidata.annotation.AnnotationTools.FieldName;
import org.atriasoft.archidata.annotation.CreationTimestamp;
import org.atriasoft.archidata.annotation.UpdateTimestamp;
import org.atriasoft.archidata.bean.PropertyDescriptor;
import org.atriasoft.archidata.bean.TypeInfo;
import org.atriasoft.archidata.bean.exception.IntrospectionException;
import org.atriasoft.archidata.dataAccess.addOn.AddOnManyToManyDoc;
import org.atriasoft.archidata.dataAccess.addOn.AddOnManyToOneDoc;
import org.atriasoft.archidata.dataAccess.addOn.AddOnOneToManyDoc;
import org.atriasoft.archidata.dataAccess.addOn.DataAccessAddOn;
import org.atriasoft.archidata.dataAccess.model.DbClassModel;
import org.atriasoft.archidata.dataAccess.model.DbFieldAction;
import org.atriasoft.archidata.dataAccess.model.DbPropertyDescriptor;
import org.atriasoft.archidata.dataAccess.model.codec.MongoCodecFactory;
import org.atriasoft.archidata.dataAccess.model.codec.MongoFieldCodec;
import org.atriasoft.archidata.dataAccess.model.codec.MongoTypeReader;
import org.atriasoft.archidata.dataAccess.options.AccessDeletedItems;
import org.atriasoft.archidata.dataAccess.options.Condition;
import org.atriasoft.archidata.dataAccess.options.DirectData;
import org.atriasoft.archidata.dataAccess.options.DirectPrimaryKey;
import org.atriasoft.archidata.dataAccess.options.FilterOmit;
import org.atriasoft.archidata.dataAccess.options.FilterValue;
import org.atriasoft.archidata.dataAccess.options.ForceHardDelete;
import org.atriasoft.archidata.dataAccess.options.ForceReadOnlyField;
import org.atriasoft.archidata.dataAccess.options.Limit;
import org.atriasoft.archidata.dataAccess.options.OptionSpecifyType;
import org.atriasoft.archidata.dataAccess.options.OrderBy;
import org.atriasoft.archidata.dataAccess.options.OverrideTableName;
import org.atriasoft.archidata.dataAccess.options.QueryOption;
import org.atriasoft.archidata.dataAccess.options.ReadAllColumn;
import org.atriasoft.archidata.dataAccess.options.Skip;
import org.atriasoft.archidata.dataAccess.options.TransmitKey;
import org.atriasoft.archidata.db.DbConfig;
import org.atriasoft.archidata.db.DbIo;
import org.atriasoft.archidata.db.DbIoFactory;
import org.atriasoft.archidata.db.DbIoMongo;
import org.atriasoft.archidata.exception.DataAccessException;
import org.atriasoft.archidata.tools.TypeUtils;
import org.atriasoft.archidata.tools.UuidUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.InternalServerErrorException;

/**
 * Data access class that provides MongoDB database operations with a functional
 * wrapper to minimize direct database code writing.
 */
public class DBAccessMongo implements Closeable {
	static final Logger LOGGER = LoggerFactory.getLogger(DBAccessMongo.class);

	/**
	 * Tracks MongoDB operation statistics for monitoring and debugging purposes.
	 */
	public static class MongoDbStatistic {
		/** Creates a new MongoDbStatistic with all counters initialized to zero. */
		public MongoDbStatistic() {}

		/** Number of countDocuments operations executed. */
		public long countCountDocuments = 0L;
		/** Number of deleteMany operations executed. */
		public long countDeleteMany = 0L;
		/** Number of drop operations executed. */
		public long countDrop = 0L;
		/** Number of find operations executed. */
		public long countFind = 0L;
		/** Number of findOneAndUpdate operations executed. */
		public long countFindOneAndUpdate = 0L;
		/** Number of insertOne operations executed. */
		public long countInsertOne = 0L;
		/** Number of updateMany operations executed. */
		public long countUpdateMany = 0L;
		/** Number of runCommand operations executed. */
		public long countRunCommand = 0L;

		/** Logs all accumulated statistics at INFO level. */
		public void display() {
			LOGGER.info("""
					statistic on access on DB:
					    - insertOne        = {}
					    - updateMany       = {}
					    - find             = {}
					    - findOneAndUpdate = {}
					    - countDocuments   = {}
					    - deleteMany       = {}
					    - drop             = {}
					    - runCommand       = {}
					""", //
					String.format("%10d", this.countInsertOne), //
					String.format("%10d", this.countUpdateMany), //
					String.format("%10d", this.countFind), //
					String.format("%10d", this.countFindOneAndUpdate), //
					String.format("%10d", this.countCountDocuments), //
					String.format("%10d", this.countDeleteMany), //
					String.format("%10d", this.countDrop), //
					String.format("%10d", this.countRunCommand)); //
		}
	};

	/** Global statistics for all MongoDB operations performed through this class. */
	public static MongoDbStatistic statistic = new MongoDbStatistic();

	// by default we manage some add-on that permit to manage non-native model (like
	// json serialization, List of external key as String list...)
	static final List<DataAccessAddOn> addOn = new ArrayList<>();

	static {
		addOn.add(new AddOnManyToManyDoc());
		addOn.add(new AddOnManyToOneDoc());
		addOn.add(new AddOnOneToManyDoc());
		// Synchronize add-ons with the DbClassModel cache layer
		DbClassModel.setAddOns(addOn);
	}

	/**
	 * Registers a custom add-on to extend database access functionality.
	 *
	 * <p>
	 * Add-ons provide specialized handling for complex field types (e.g., OneToMany, ManyToOne).
	 * This method allows registration of custom add-ons beyond the default ones.
	 * </p>
	 *
	 * @param addOn The add-on implementation to register
	 */
	public static void addAddOn(final DataAccessAddOn addOn) {
		DBAccessMongo.addOn.add(addOn);
		// Keep DbClassModel in sync with registered add-ons
		DbClassModel.setAddOns(DBAccessMongo.addOn);
	}

	/**
	 * Creates a new DBAccessMongo instance using default database configuration.
	 *
	 * <p>
	 * The default configuration is loaded from environment variables or configuration files.
	 * </p>
	 *
	 * @return A new DBAccessMongo instance
	 * @throws InternalServerErrorException if database connection fails
	 * @throws IOException                  if I/O error occurs
	 * @throws DataAccessException          if database access configuration is invalid
	 */
	public static final DBAccessMongo createInterface()
			throws InternalServerErrorException, IOException, DataAccessException {
		return DBAccessMongo.createInterface(DbIoFactory.create());
	}

	/**
	 * Creates a new DBAccessMongo instance using the specified database configuration.
	 *
	 * @param config Custom database configuration
	 * @return A new DBAccessMongo instance
	 * @throws InternalServerErrorException if database connection fails
	 * @throws IOException                  if I/O error occurs
	 */
	public static final DBAccessMongo createInterface(final DbConfig config)
			throws InternalServerErrorException, IOException {
		return DBAccessMongo.createInterface(DbIoFactory.create(config));
	}

	/**
	 * Creates a new DBAccessMongo instance using an existing database I/O interface.
	 *
	 * @param io Existing database I/O interface
	 * @return A new DBAccessMongo instance
	 * @throws InternalServerErrorException if database connection fails
	 */
	public static final DBAccessMongo createInterface(final DbIo io) throws InternalServerErrorException {
		if (io instanceof final DbIoMongo ioMorphia) {
			try {
				return new DBAccessMongo(ioMorphia);
			} catch (final IOException e) {
				LOGGER.error("Failed to create DB interface: {}", e.getMessage(), e);
				throw new InternalServerErrorException("Fail to create DB interface.");
			}
		}
		throw new InternalServerErrorException("unknown DB interface ... ");
	}

	private final DbIoMongo db;
	private ClientSession session = null;

	/**
	 * Constructs a new DBAccessMongo instance with the specified MongoDB I/O interface.
	 *
	 * <p>
	 * This constructor opens the database connection. Use {@link #close()} to properly
	 * release resources when done.
	 * </p>
	 *
	 * @param db MongoDB I/O interface
	 * @throws IOException if connection fails
	 */
	public DBAccessMongo(final DbIoMongo db) throws IOException {
		this.db = db;
		db.open();
	}

	/**
	 * Closes the database connection and releases associated resources.
	 *
	 * <p>
	 * This method should be called when the DBAccessMongo instance is no longer needed.
	 * Typically used with try-with-resources pattern. If a transaction is still active,
	 * it will be automatically aborted before closing.
	 * </p>
	 *
	 * @throws IOException if closing connection fails
	 */
	@Override
	public void close() throws IOException {
		if (this.session != null) {
			try {
				this.session.abortTransaction();
				LOGGER.warn("Transaction was still active during close() - aborted");
			} catch (final Exception ex) {
				LOGGER.error("Failed to abort transaction during close: {}", ex.getMessage(), ex);
			} finally {
				this.session.close();
				this.session = null;
			}
		}
		this.db.close();
	}

	/**
	 * Returns the underlying MongoDB I/O interface.
	 *
	 * @return The MongoDB I/O interface
	 */
	public DbIoMongo getInterface() {
		return this.db;
	}

	/**
	 * Starts a new MongoDB transaction on this connection.
	 *
	 * <p>
	 * After calling this method, all subsequent CRUD operations on this
	 * DBAccessMongo instance will participate in the transaction until
	 * {@link #commitTransaction()} or {@link #abortTransaction()} is called.
	 * </p>
	 *
	 * <p>
	 * <strong>Important:</strong> MongoDB transactions require a replica set.
	 * Standalone MongoDB instances do not support transactions.
	 * </p>
	 *
	 * @throws DataAccessException if a transaction is already active
	 */
	public void startTransaction() throws DataAccessException {
		if (this.session != null) {
			throw new DataAccessException("A transaction is already active on this connection");
		}
		this.session = this.db.getClient().startSession();
		this.session.startTransaction();
		LOGGER.debug("Transaction started");
	}

	/**
	 * Commits the active transaction.
	 *
	 * @throws DataAccessException if no transaction is active
	 */
	public void commitTransaction() throws DataAccessException {
		if (this.session == null) {
			throw new DataAccessException("No active transaction to commit");
		}
		try {
			this.session.commitTransaction();
			LOGGER.debug("Transaction committed");
		} finally {
			this.session.close();
			this.session = null;
		}
	}

	/**
	 * Aborts the active transaction, rolling back all changes.
	 *
	 * @throws DataAccessException if no transaction is active
	 */
	public void abortTransaction() throws DataAccessException {
		if (this.session == null) {
			throw new DataAccessException("No active transaction to abort");
		}
		try {
			this.session.abortTransaction();
			LOGGER.debug("Transaction aborted");
		} finally {
			this.session.close();
			this.session = null;
		}
	}

	/**
	 * Returns the current ClientSession, or null if no transaction is active.
	 *
	 * <p>
	 * This is used internally by {@link org.atriasoft.archidata.dataAccess.mongo.MongoLinkManager}
	 * and add-ons to pass the session to MongoDB driver calls.
	 * </p>
	 *
	 * @return The active ClientSession, or null
	 */
	public ClientSession getSession() {
		return this.session;
	}

	/**
	 * Returns whether a transaction is currently active.
	 *
	 * @return true if a transaction is active
	 */
	public boolean isTransactionActive() {
		return this.session != null;
	}

	/**
	 * Generates a query condition for matching an entity by its primary key ID.
	 *
	 * <p>
	 * This method automatically detects the ID field type and creates the appropriate
	 * condition for querying by ID.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * ObjectId userId = new ObjectId("507f1f77bcf86cd799439011");
	 * Bson condition = db.getTableIdCondition(User.class, userId, options);
	 * // condition will be: "_id" = ObjectId("507f1f77bcf86cd799439011")
	 * </pre>
	 *
	 * @param <ID_TYPE> The type of the ID (e.g., ObjectId, UUID, Long)
	 * @param clazz     The entity class
	 * @param idKey     The ID value to match
	 * @param options   Query options
	 * @return A Bson filter for matching the entity by ID
	 * @throws DataAccessException if the class has no @Id field
	 */
	public <ID_TYPE> Bson getTableIdCondition(final Class<?> clazz, final ID_TYPE idKey, final QueryOptions options)
			throws DataAccessException {
		final DbClassModel model;
		try {
			model = DbClassModel.of(clazz);
		} catch (final IntrospectionException e) {
			throw new DataAccessException("Failed to introspect class: " + clazz.getSimpleName(), e);
		}
		final DbPropertyDescriptor pkDesc = model.getPrimaryKey();
		if (pkDesc == null) {
			throw new DataAccessException(
					"The class have no annotation @Id ==> can not determine the default type searching");
		}
		if (idKey == null) {
			throw new DataAccessException("Try to identify the ID type and object was null.");
		}
		final FieldName fieldName = pkDesc.getFieldName(options);
		Class<?> typeClass = pkDesc.getProperty().getType();
		if (typeClass == Object.class) {
			final List<OptionSpecifyType> specificTypes = options.get(OptionSpecifyType.class);
			for (final OptionSpecifyType specify : specificTypes) {
				if (specify.name.equals(fieldName.inStruct())) {
					typeClass = specify.clazz;
					LOGGER.trace("Detect overwrite of typing ... '{}' => '{}'", typeClass.getCanonicalName(),
							specify.clazz.getCanonicalName());
					break;
				}
			}
		}
		if (idKey.getClass() != typeClass) {
			if (idKey.getClass() == Condition.class) {
				throw new DataAccessException(
						"Try to identify the ID type on a condition 'close' internal API error use xxxWhere(...) instead.");
			}
			throw new DataAccessException("Request update with the wrong type ...");
		}
		return Filters.eq(fieldName.inTable(), idKey);
	}

	/**
	 * Inserts multiple entities into the database.
	 *
	 * <p>
	 * Each entity is inserted individually and returned with its generated ID.
	 * The order of insertion is maintained in the returned list.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * List&lt;User&gt; users = List.of(user1, user2, user3);
	 * List&lt;User&gt; insertedUsers = db.insertMultiple(users);
	 * ObjectId firstUserId = insertedUsers.get(0).id;
	 * </pre>
	 *
	 * @param <T>     The type of the entities
	 * @param data    List of entities to insert
	 * @param options Optional query options (e.g., table selection)
	 * @return List of inserted entities with generated IDs
	 * @throws Exception if insertion operation fails
	 */
	public <T> List<T> insertMultiple(final List<T> data, final QueryOption... options) throws Exception {
		final List<T> out = new ArrayList<>();
		for (final T elem : data) {
			final T tmp = insert(elem, options);
			out.add(tmp);
		}
		return out;
	}

	/**
	 * Inserts a single entity into the database.
	 *
	 * <p>
	 * The entity is inserted and then retrieved with its generated ID and any
	 * auto-generated fields (timestamps, etc.). The primary key is automatically
	 * generated based on the field type (ObjectId, UUID, or Long).
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * User user = new User();
	 * user.name = "John Doe";
	 * user.email = "john@example.com";
	 *
	 * User insertedUser = db.insert(user);
	 * ObjectId userId = insertedUser.id; // Auto-generated ObjectId
	 * Timestamp created = insertedUser.createdAt; // Auto-generated timestamp
	 * </pre>
	 *
	 * @param <T>    The type of the entity
	 * @param data   Entity to insert
	 * @param option Optional query options (e.g., table selection, field filtering)
	 * @return The inserted entity with generated ID and auto-populated fields
	 * @throws Exception if insertion operation fails
	 */
	@SuppressWarnings("unchecked")
	public <T> T insert(final T data, final QueryOption... option) throws Exception {
		final Object insertedId = insertPrimaryKey(data, option);
		final QueryOptions options = new QueryOptions(option);
		final QueryOptions injectedOptions = new QueryOptions();
		final List<OverrideTableName> override = options.get(OverrideTableName.class);
		if (override.size() != 0) {
			injectedOptions.add(override.get(0));
		}
		final List<OptionSpecifyType> typeOptions = options.get(OptionSpecifyType.class);
		for (final OptionSpecifyType elem : typeOptions) {
			injectedOptions.add(elem);
		}
		final List<ReadAllColumn> readAllColumnOptions = options.get(ReadAllColumn.class);
		for (final ReadAllColumn elem : readAllColumnOptions) {
			injectedOptions.add(elem);
		}
		final List<AccessDeletedItems> accessDeletedItemsOptions = options.get(AccessDeletedItems.class);
		for (final AccessDeletedItems elem : accessDeletedItemsOptions) {
			injectedOptions.add(elem);
		}
		return (T) getById(data.getClass(), insertedId, injectedOptions.getAllArray());
	}

	/**
	 * Updates an entity identified by its ID with the provided data.
	 *
	 * <p>
	 * <strong>Field filtering behavior:</strong> By default, ALL fields in the data object will be updated.
	 * To update only specific fields, use the {@link FilterValue} option to explicitly specify which fields
	 * should be updated.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * ObjectId userId = new ObjectId("507f1f77bcf86cd799439011");
	 * User updateData = new User();
	 * updateData.email = "newemail@example.com";
	 *
	 * // Update all fields
	 * db.updateById(updateData, userId);
	 *
	 * // Update only editable fields
	 * db.updateById(updateData, userId, FilterValue.getEditableFieldsNames(User.class));
	 *
	 * // Update specific fields only
	 * db.updateById(updateData, userId, new FilterValue(List.of("email", "lastModified")));
	 * </pre>
	 *
	 * @param <T>       The type of the entity
	 * @param <ID_TYPE> The type of the ID (e.g., ObjectId, UUID, Long)
	 * @param data      The entity data to update
	 * @param id        The unique identifier of the entity (e.g., ObjectId)
	 * @param option    Optional query options (e.g., FilterValue, table selection)
	 * @return Number of entities updated (typically 0 or 1)
	 * @throws Exception if update operation fails
	 */
	public <T, ID_TYPE> long updateById(final T data, final ID_TYPE id, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		// Ensure FilterValue is present - if not provided, update ALL fields
		if (!options.exist(FilterValue.class)) {
			options.add(FilterValue.getEditableFieldsNames(data.getClass()));
		}
		options.add(new Condition(getTableIdCondition(data.getClass(), id, options)));
		options.add(new TransmitKey(id));
		return update(data, options);
	}

	/**
	 * Updates entities matching the specified conditions.
	 *
	 * <p>
	 * <strong>Required option:</strong> You MUST provide a {@link FilterValue} option to specify which
	 * fields should be updated. This prevents accidental full-row updates.
	 * </p>
	 *
	 * <p>
	 * <strong>Condition specification:</strong> Use {@link Condition} option to specify which entities
	 * to update. Without conditions, the operation will fail with an exception.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * User updateData = new User();
	 * updateData.status = "inactive";
	 * updateData.lastModified = Timestamp.from(Instant.now());
	 *
	 * // Update all users with status "active"
	 * long count = db.update(updateData,
	 *     new Condition(Filters.eq("status", "active")),
	 *     new FilterValue(List.of("status", "lastModified")));
	 *
	 * // Update users created by specific author
	 * ObjectId authorId = new ObjectId("507f1f77bcf86cd799439011");
	 * db.update(updateData,
	 *     new Condition(Filters.eq("authorId", authorId)),
	 *     new FilterValue(List.of("status")));
	 * </pre>
	 *
	 * @param <T>    The type of the entity
	 * @param data   The entity data containing the update values
	 * @param option Query options including FilterValue (required) and Condition
	 * @return Number of entities updated
	 * @throws Exception if update operation fails or FilterValue is missing
	 */
	public <T> long update(final T data, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		return update(data, options);
	}

	/**
	 * Retrieves a single entity matching the specified conditions (QueryOptions variant).
	 *
	 * <p>
	 * If multiple entities match the conditions, only the first one is returned.
	 * If no entity matches, returns null.
	 * </p>
	 *
	 * @param <T>     The type of the entity
	 * @param clazz   The class of the entity
	 * @param options Query options including conditions, filters, etc.
	 * @return The first matching entity or null if none found
	 * @throws Exception if retrieval operation fails
	 */
	public <T> T get(final Class<T> clazz, final QueryOptions options) throws Exception {
		options.add(new Limit(1));
		final List<T> values = gets(clazz, options);
		if (values.size() == 0) {
			return null;
		}
		return values.get(0);
	}

	/**
	 * Retrieves a single entity matching the specified conditions (internal raw version).
	 *
	 * @param clazz   The class of the entity
	 * @param options Query options
	 * @return The first matching entity as Object or null
	 * @throws Exception if retrieval operation fails
	 */
	public Object getRaw(final Class<?> clazz, final QueryOptions options) throws Exception {
		options.add(new Limit(1));
		final List<Object> values = getsRaw(clazz, options);
		if (values.size() == 0) {
			return null;
		}
		return values.get(0);
	}

	/**
	 * Retrieves a single entity matching the specified conditions.
	 *
	 * <p>
	 * If multiple entities match the conditions, only the first one is returned.
	 * If no entity matches, returns null.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * // Find user by email
	 * User user = db.get(User.class,
	 *     new Condition(Filters.eq("email", "john@example.com")));
	 *
	 * // Find document created by specific author
	 * ObjectId authorId = new ObjectId("507f1f77bcf86cd799439011");
	 * Document doc = db.get(Document.class,
	 *     new Condition(Filters.eq("authorId", authorId)));
	 * </pre>
	 *
	 * @param <T>    The type of the entity
	 * @param clazz  The class of the entity
	 * @param option Query options including conditions, filters, etc.
	 * @return The first matching entity or null if none found
	 * @throws Exception if retrieval operation fails
	 */
	public <T> T get(final Class<T> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		return get(clazz, options);
	}

	/**
	 * Retrieves a single entity matching the specified conditions (internal raw version).
	 *
	 * @param clazz  The class of the entity
	 * @param option Query options
	 * @return The first matching entity as Object or null
	 * @throws Exception if retrieval operation fails
	 */
	public Object getRaw(final Class<?> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		return getRaw(clazz, options);
	}

	/**
	 * Retrieves all entities of the specified type matching the conditions.
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * // Get all active users
	 * List&lt;User&gt; users = db.gets(User.class,
	 *     new Condition(Filters.eq("status", "active")));
	 *
	 * // Get documents by author with limit
	 * ObjectId authorId = new ObjectId("507f1f77bcf86cd799439011");
	 * List&lt;Document&gt; docs = db.gets(Document.class,
	 *     new Condition(Filters.eq("authorId", authorId)),
	 *     new Limit(10),
	 *     new OrderBy("createdAt", false));
	 * </pre>
	 *
	 * @param <T>    The type of the entity
	 * @param clazz  The class of the entity
	 * @param option Query options including conditions, filters, limits, etc.
	 * @return List of matching entities (empty list if none found)
	 * @throws Exception if retrieval operation fails
	 */
	public <T> List<T> gets(final Class<T> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		return gets(clazz, options);
	}

	/**
	 * Retrieves all entities of the specified type matching the conditions (internal raw version).
	 *
	 * @param clazz  The class of the entity
	 * @param option Query options
	 * @return List of matching entities as Objects
	 * @throws Exception if retrieval operation fails
	 */
	public List<Object> getsRaw(final Class<?> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		return getsRaw(clazz, options);
	}

	/**
	 * Retrieves all entities of the specified type matching the conditions (QueryOptions variant).
	 *
	 * @param <T>     The type of the entity
	 * @param clazz   The class of the entity
	 * @param options Query options including conditions, filters, limits, etc.
	 * @return List of matching entities (empty list if none found)
	 * @throws DataAccessException if a data access error occurs
	 * @throws IOException         if an I/O error occurs
	 */
	@SuppressWarnings("unchecked")
	public <T> List<T> gets(final Class<T> clazz, final QueryOptions options) throws DataAccessException, IOException {
		final List<Object> out = getsRaw(clazz, options);
		return (List<T>) out;
	}

	/**
	 * Retrieves an entity by its unique identifier.
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * // Get user by ObjectId
	 * ObjectId userId = new ObjectId("507f1f77bcf86cd799439011");
	 * User user = db.getById(User.class, userId);
	 * if (user != null) {
	 *     System.out.println("Found: " + user.name);
	 * }
	 *
	 * // Get user with all columns (including non-readable ones)
	 * User userWithAllData = db.getById(User.class, userId, QueryOptions.READ_ALL_COLUMN);
	 * </pre>
	 *
	 * @param <T>       The type of the entity
	 * @param <ID_TYPE> The type of the ID (e.g., ObjectId, UUID, Long)
	 * @param clazz     The class of the entity
	 * @param id        The unique identifier (e.g., ObjectId)
	 * @param option    Optional query options (e.g., table selection, column filters)
	 * @return The entity with the specified ID or null if not found
	 * @throws Exception if retrieval operation fails
	 */
	public <T, ID_TYPE> T getById(final Class<T> clazz, final ID_TYPE id, final QueryOption... option)
			throws Exception {
		final QueryOptions options = new QueryOptions(option);
		options.add(new Condition(getTableIdCondition(clazz, id, options)));
		return get(clazz, options.getAllArray());
	}

	/**
	 * Retrieves all entities of the specified type (no conditions).
	 *
	 * <p>
	 * <strong>Warning:</strong> This method retrieves ALL entities from the table.
	 * Use with caution on large datasets. Consider adding pagination via QueryOptions.
	 * </p>
	 *
	 * @param <T>   The type of the entity
	 * @param clazz The class of the entity
	 * @return List of all entities (empty list if none found)
	 * @throws Exception if retrieval operation fails
	 */
	public <T> List<T> getAll(final Class<T> clazz) throws Exception {
		return gets(clazz);
	}

	/**
	 * Checks if an entity with the specified ID exists.
	 *
	 * <p>
	 * This method verifies the existence of an entity by its unique identifier.
	 * Returns true if the entity exists, false otherwise.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * ObjectId userId = new ObjectId("507f1f77bcf86cd799439011");
	 * if (db.existsById(User.class, userId)) {
	 *     System.out.println("User exists");
	 * } else {
	 *     System.out.println("User not found");
	 * }
	 * </pre>
	 *
	 * @param <ID_TYPE> The type of the ID (e.g., ObjectId, UUID, Long)
	 * @param clazz     The class of the entity
	 * @param id        The unique identifier to check (e.g., ObjectId)
	 * @param option    Optional query options (e.g., table selection)
	 * @return true if an entity with this ID exists, false otherwise
	 * @throws Exception if existence check operation fails
	 */
	public <ID_TYPE> boolean existsById(final Class<?> clazz, final ID_TYPE id, final QueryOption... option)
			throws Exception {
		final QueryOptions options = new QueryOptions(option);
		options.add(new Condition(getTableIdCondition(clazz, id, options)));
		return count(clazz, options) > 0;
	}

	/**
	 * Counts the number of entities matching the specified conditions.
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * // Count all active users
	 * long activeCount = db.count(User.class,
	 *     new Condition(Filters.eq("status", "active")));
	 *
	 * // Count documents by author
	 * ObjectId authorId = new ObjectId("507f1f77bcf86cd799439011");
	 * long docCount = db.count(Document.class,
	 *     new Condition(Filters.eq("authorId", authorId)));
	 * </pre>
	 *
	 * @param clazz  The class of the entity
	 * @param option Query options including conditions, filters, etc.
	 * @return The number of matching entities
	 * @throws Exception if count operation fails
	 */
	public long count(final Class<?> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		return count(clazz, options);
	}

	/**
	 * Deletes an entity by its unique identifier.
	 *
	 * <p>
	 * <strong>Automatic routing behavior:</strong> If the entity class is annotated with
	 * {@code @SoftDeleted}, this method performs a soft delete (marking as deleted).
	 * Otherwise, it performs a hard delete (physical removal).
	 * </p>
	 *
	 * <p>
	 * Use {@link #deleteHardById} or {@link #deleteSoftById} if you need explicit control
	 * over the delete behavior.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * ObjectId userId = new ObjectId("507f1f77bcf86cd799439011");
	 *
	 * // Automatic routing (soft delete if @SoftDeleted, hard delete otherwise)
	 * long deleted = db.deleteById(User.class, userId);
	 * if (deleted &gt; 0) {
	 *     System.out.println("User deleted");
	 * }
	 *
	 * // Explicit hard delete (ignores @SoftDeleted)
	 * db.deleteHardById(User.class, userId);
	 * </pre>
	 *
	 * @param <ID_TYPE> The type of the ID (e.g., ObjectId, UUID, Long)
	 * @param clazz     The class of the entity
	 * @param id        The unique identifier of the entity to delete (e.g., ObjectId)
	 * @param options   Optional query options
	 * @return Number of entities deleted (typically 0 or 1)
	 * @throws Exception if delete operation fails
	 */
	public <ID_TYPE> long deleteById(final Class<?> clazz, final ID_TYPE id, final QueryOption... options)
			throws Exception {
		final DbClassModel model = DbClassModel.of(clazz);
		if (model.getDeletedFieldName() != null) {
			return deleteSoftById(clazz, id, options);
		} else {
			return deleteHardById(clazz, id, options);
		}
	}

	/**
	 * Deletes entities matching the specified conditions.
	 *
	 * <p>
	 * <strong>Automatic routing behavior:</strong> If the entity class is annotated with
	 * {@code @SoftDeleted}, this method performs a soft delete (marking as deleted).
	 * Otherwise, it performs a hard delete (physical removal).
	 * </p>
	 *
	 * <p>
	 * Use {@link #deleteHard} or {@link #deleteSoft} if you need explicit control
	 * over the delete behavior.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * // Delete all inactive users
	 * long deleted = db.delete(User.class,
	 *     new Condition(Filters.eq("status", "inactive")));
	 *
	 * // Delete documents by author
	 * ObjectId authorId = new ObjectId("507f1f77bcf86cd799439011");
	 * long count = db.delete(Document.class,
	 *     new Condition(Filters.eq("authorId", authorId)));
	 * System.out.println("Deleted " + count + " documents");
	 * </pre>
	 *
	 * @param clazz  The class of the entity
	 * @param option Query options including conditions
	 * @return Number of entities deleted
	 * @throws Exception if delete operation fails
	 */
	public long delete(final Class<?> clazz, final QueryOption... option) throws Exception {
		final DbClassModel model = DbClassModel.of(clazz);
		if (model.getDeletedFieldName() != null) {
			return deleteSoft(clazz, option);
		} else {
			return deleteHard(clazz, option);
		}
	}

	/**
	 * Lists all collection names in the current database.
	 *
	 * <p>
	 * Example usage:
	 * </p>
	 * <pre>
	 * List&lt;String&gt; collections = db.listCollections("mydb");
	 * for (String collName : collections) {
	 *     System.out.println("Collection: " + collName);
	 * }
	 * </pre>
	 *
	 * @param name   Database name (not currently used in MongoDB implementation)
	 * @param option Optional query options
	 * @return List of collection names
	 * @throws InternalServerErrorException if listing fails
	 */
	public List<String> listCollections(final String name, final QueryOption... option)
			throws InternalServerErrorException {
		return this.db.getDatabase().listCollectionNames().into(new ArrayList<>());
	}

	/**
	 * Renames a MongoDB collection.
	 *
	 * <p>
	 * Example usage:
	 * </p>
	 * <pre>
	 * db.renameCollection("old_users", "users");
	 * </pre>
	 *
	 * @param source      Current collection name
	 * @param destination New collection name
	 */
	public void renameCollection(final String source, final String destination) {
		final MongoCollection<Document> previousCollection = this.db.getDatabase().getCollection(source);
		previousCollection
				.renameCollection(new com.mongodb.MongoNamespace(this.db.getDatabase().getName(), destination));
	}

	/**
	 * Deletes (drops) an entire database.
	 *
	 * <p>
	 * <strong>Warning:</strong> This permanently deletes all collections and documents
	 * in the specified database. Use with caution.
	 * </p>
	 *
	 * <p>
	 * Example usage:
	 * </p>
	 * <pre>
	 * db.deleteDatabase("test_database");
	 * </pre>
	 *
	 * @param name DataBase name to delete
	 * @return true if deletion succeeds
	 */
	public boolean deleteDatabase(final String name) {
		final MongoDatabase database = this.db.getClient().getDatabase(name);
		database.drop();
		return true;
	}

	/**
	 * Creates an ascending index on a specific field in a collection.
	 *
	 * <p>
	 * Indexes improve query performance for frequently queried fields.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId field:
	 * </p>
	 * <pre>
	 * // Create index on authorId field for faster lookups
	 * db.ascendingIndex("documents", "authorId");
	 *
	 * // Create index on email field
	 * db.ascendingIndex("users", "email");
	 * </pre>
	 *
	 * @param collectionName Name of the collection
	 * @param fieldName      Name of the field to index
	 */
	public void ascendingIndex(final String collectionName, final String fieldName) {
		final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
		collection.createIndex(Indexes.ascending(fieldName));
	}

	/**
	 * Disables schema validation for an existing MongoDB collection.
	 *
	 * This method removes any JSON Schema validator associated with the specified
	 * collection and turns off server-side validation entirely. Documents can be
	 * inserted or updated without being checked against any schema.
	 *
	 * @param collection the name of the MongoDB collection to disable schema
	 *                   validation on.
	 */
	public void disableSchema(final String collection) {
		final Document command = new Document("collMod", collection)//
				.append("validator", new Document())//
				.append("validationLevel", "off");
		statistic.countRunCommand++;
		this.db.getDatabase().runCommand(command);
	}

	/**
	 * Sets or updates a JSON Schema validator for an existing MongoDB collection.
	 *
	 * The provided schema will be strictly enforced on all insert and update
	 * operations for the specified collection. This ensures that documents conform
	 * to the defined structure.
	 *
	 * @param collection the name of the MongoDB collection to apply the schema to.
	 * @param schema     a BSON Document representing the JSON Schema to enforce
	 *                   (must follow MongoDB's schema validation format).
	 */
	public void enableSchema(final String collection, final Document schema) {
		final Document command = new Document("collMod", collection)//
				.append("validator", schema)//
				.append("validationLevel", "strict");
		statistic.countRunCommand++;
		this.db.getDatabase().runCommand(command);
	}

	/**
	 * Converts a Java object into a MongoDB-compatible BSON representation.
	 *
	 * @param <T>  The type of the object to convert
	 * @param data The object to convert (may be null)
	 * @return The BSON representation of the object, or null if the input is null
	 * @throws Exception if conversion fails
	 */
	public <T> Object convertInDocument(final T data) throws Exception {
		if (data == null) {
			return null;
		}
		// Use pre-compiled codec: resolves the writer once per type (cached via TypeInfo)
		return MongoCodecFactory.buildWriter(TypeInfo.ofRaw(data.getClass())).toMongo(data);
	}

	private Object retreiveValueEnum(final Class<?> objectClass, final String temporaryString)
			throws DataAccessException {
		final Object[] arr = objectClass.getEnumConstants();
		for (final Object elem : arr) {
			if (elem.toString().equals(temporaryString)) {
				return elem;
			}
		}
		throw new DataAccessException("Enum value does not exist in the Model val='" + temporaryString + "' model="
				+ objectClass.getCanonicalName());

	}

	/**
	 * Converts a string default value to the appropriate Java type based on the field's type.
	 *
	 * @param data  The string representation of the default value
	 * @param field The target field whose type determines the conversion
	 * @return The converted value matching the field's type
	 * @throws Exception if the conversion fails or the type is unsupported
	 */
	protected Object convertDefaultField(String data, final Field field) throws Exception {
		if (data.startsWith("'") && data.endsWith("'")) {
			data = data.substring(1, data.length() - 1);
		}
		final Class<?> type = field.getType();
		if (type == Long.class || type == long.class) {
			return Long.parseLong(data);
		}
		if (type == Integer.class || type == int.class) {
			return Integer.parseInt(data);
		}
		if (type == Float.class || type == float.class) {
			return Float.parseFloat(data);
		}
		if (type == Double.class || type == double.class) {
			return Double.parseDouble(data);
		}
		if (type == Boolean.class || type == boolean.class) {
			return Boolean.parseBoolean(data);
		}
		if (type == Date.class) {
			return new Date(Long.parseLong(data));
		}
		if (type == Instant.class) {
			return Instant.parse(data);
		}
		if (type == LocalDate.class) {
			return LocalDate.parse(data);
		}
		if (type == LocalTime.class) {
			return LocalTime.parse(data);
		}
		if (type == String.class) {
			return data;
		}
		if (type.isEnum()) {
			return retreiveValueEnum(type, data);
		}
		throw new DataAccessException(
				"Request default of unknow native type " + type.getCanonicalName() + " => " + data);
	}

	/**
	 * Retrieves and atomically increments a sequence counter for generating unique Long IDs.
	 *
	 * <p>Uses a dedicated "counters" collection to store sequences per collection/field.
	 *
	 * @param collectionName The collection name used as the counter key
	 * @param fieldName      The field name for the sequence (defaults to "sequence_id" if null or empty)
	 * @return The next sequence value
	 */
	public long getNextSequenceLongValue(final String collectionName, String fieldName) {
		if (fieldName == null || fieldName.isEmpty()) {
			fieldName = "sequence_id";
		}
		// Collection "counters" to store the sequences if Ids
		final MongoCollection<Document> countersCollection = this.db.getDatabase().getCollection("counters");

		// Filter to find the specific counter for the collections
		final Document filter = new Document("_id", collectionName);

		// Update the field <fieldName> of 1
		final Document update = new Document("$inc", new Document(fieldName, 1L));

		// get the value after updated it
		final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
				.upsert(true); // create field if not exist

		// Real creation of the unique counter.
		statistic.countFindOneAndUpdate++;
		final Document updatedCounter = this.session != null
				? countersCollection.findOneAndUpdate(this.session, filter, update, options)
				: countersCollection.findOneAndUpdate(filter, update, options);

		// Return the new sequence value...
		return updatedCounter.getLong(fieldName);
	}

	/**
	 * Test variant of insertPrimaryKey that inserts data using the MongoDB codec registry directly.
	 *
	 * @param <T>    The type of the entity
	 * @param data   The entity to insert
	 * @param option Optional query options
	 * @return The generated primary key (ObjectId or Long)
	 * @throws Exception if insertion fails
	 */
	@SuppressWarnings("unchecked")
	public <T extends Object> Object insertPrimaryKey_test(final T data, final QueryOption... option) throws Exception {
		final Class<?> clazz = data.getClass();
		final QueryOptions options = new QueryOptions(option);
		final boolean directdata = options.exist(DirectData.class);
		final DbClassModel model = DbClassModel.of(clazz);
		final String collectionName = model.getTableName(options);
		Long primaryKey = null;
		try {
			// Handle primary key for Long type
			final DbPropertyDescriptor pkDesc = model.getPrimaryKey();
			if (pkDesc != null && !directdata) {
				final Class<?> pkType = pkDesc.getProperty().getType();
				if (pkType == Long.class || pkType == long.class) {
					primaryKey = getNextSequenceLongValue(collectionName, pkDesc.getProperty().getName());
					pkDesc.getProperty().setValue(data, primaryKey);
				}
			}
			// Handle creation timestamp
			final DbPropertyDescriptor createTsDesc = model.getCreationTimestamp();
			if (createTsDesc != null && !directdata) {
				createTsDesc.getProperty().setValue(data, Date.from(Instant.now()));
			}
			// Handle update timestamp
			final DbPropertyDescriptor updateTsDesc = model.getUpdateTimestamp();
			if (updateTsDesc != null && !directdata) {
				updateTsDesc.getProperty().setValue(data, Date.from(Instant.now()));
			}
			// Handle deleted field (insert with default value)
			final DbPropertyDescriptor deletedDesc = model.getDeletedField();
			if (deletedDesc != null && !directdata) {
				final String defVal = deletedDesc.getDefaultValue();
				if (defVal != null) {
					final Object defaultValue = convertDefaultField(defVal, deletedDesc.getProperty().getField());
					deletedDesc.getProperty().setValue(data, defaultValue);
				}
			}

			final MongoCollection<T> collection = this.db.getDatabase().getCollection(collectionName, (Class<T>) clazz);
			statistic.countInsertOne++;
			final InsertOneResult res = this.session != null ? collection.insertOne(this.session, data)
					: collection.insertOne(data);
			if (primaryKey != null) {
				return primaryKey;
			}
			final ObjectId insertedId = res.getInsertedId().asObjectId().getValue();
			return insertedId;
		} catch (final Exception ex) {
			LOGGER.error("Fail Mongo request: {}", ex.getMessage(), ex);
			throw new DataAccessException("Fail to Insert data in DB : " + ex.getMessage());
		}
	}

	/**
	 * Inserts an entity into the database and returns its generated primary key.
	 *
	 * <p>Handles auto-generation of primary keys (ObjectId, UUID, or Long), creation and
	 * update timestamps, default values for deleted fields, and add-on field processing.
	 *
	 * @param <T>    The type of the entity
	 * @param data   The entity to insert
	 * @param option Optional query options (e.g., DirectData, DirectPrimaryKey)
	 * @return The generated primary key
	 * @throws Exception if insertion fails
	 */
	@SuppressWarnings("unchecked")
	public <T> Object insertPrimaryKey(final T data, final QueryOption... option) throws Exception {
		final Class<?> clazz = data.getClass();
		final QueryOptions options = new QueryOptions(option);
		final boolean directdata = options.exist(DirectData.class);
		final boolean directPrimaryKey = options.exist(DirectPrimaryKey.class);
		final DbClassModel model = DbClassModel.of(clazz);

		final String collectionName = model.getTableName(options);
		Object uniqueId = null;
		// real add in the BDD:
		try {
			final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
			final Document docSet = new Document();
			final Document docUnSet = new Document();

			// Iterate all fields in declaration order to preserve Document field ordering
			for (final DbPropertyDescriptor desc : model.getAllFields()) {
				final DbFieldAction action = desc.getAction();
				final FieldName fieldName = desc.getFieldName(options);
				final PropertyDescriptor prop = desc.getProperty();
				final Field field = prop.getField();

				switch (action) {
					case PRIMARY_KEY: {
						if (!directdata && !directPrimaryKey) {
							if (prop.getValue(data) != null) {
								throw new DataAccessException(
										"Unexpected comportment, try to add an object with a primary key. use option 'DirectPrimaryKey.class' to do that");
							}
							final Class<?> pkType = prop.getType();
							if (pkType == ObjectId.class) {
								uniqueId = new ObjectId();
								docSet.append(fieldName.inTable(), uniqueId);
							} else if (pkType == UUID.class) {
								uniqueId = UuidUtils.nextUUID();
								docSet.append(fieldName.inTable(), uniqueId);
							} else if (pkType == Long.class || pkType == long.class) {
								final long id = getNextSequenceLongValue(collectionName, fieldName.inTable());
								uniqueId = id;
								docSet.append(fieldName.inTable(), id);
							} else {
								throw new DataAccessException("TODO: Manage the ID primary key for type: "
										+ clazz.getCanonicalName() + " => " + pkType);
							}
						} else {
							final Object primaryKeyValue = prop.getValue(data);
							if (primaryKeyValue == null) {
								throw new DataAccessException(
										"Fail to Insert data in DB.. when use 'DirectData.class' or 'DirectPrimaryKey.class' you need to provide a primary key...");
							}
							uniqueId = primaryKeyValue;
							docSet.append(fieldName.inTable(), primaryKeyValue);
						}
						break;
					}
					case CREATION_TIMESTAMP:
					case UPDATE_TIMESTAMP: {
						if (!directdata) {
							docSet.append(fieldName.inTable(), Date.from(Instant.now()));
						}
						break;
					}
					case DELETED: {
						if (!directdata) {
							final String defVal = desc.getDefaultValue();
							if (defVal != null) {
								final Object defaultValue = convertDefaultField(defVal, field);
								docSet.append(fieldName.inTable(), defaultValue);
							}
						}
						break;
					}
					case ADDON: {
						if (!desc.canInsert()) {
							break;
						}
						desc.getAddOn().insertData(this, desc, data, options, docSet, docUnSet);
						break;
					}
					case NOT_READ:
					case NORMAL:
					default: {
						final MongoFieldCodec codec = desc.getCodec();
						if (codec == null) {
							break;
						}
						Object currentInsertValue = prop.getValue(data);

						if (currentInsertValue == null && field != null && !field.getClass().isPrimitive()) {
							final String defVal = desc.getDefaultValue();
							if (defVal == null) {
								break;
							}
							currentInsertValue = convertDefaultField(defVal, field);
						}

						final Class<?> type = prop.getType();
						if (!type.isPrimitive()) {
							if (prop.getValue(data) == null) {
								if (currentInsertValue != null) {
									docSet.append(fieldName.inTable(), currentInsertValue);
								}
								break;
							}
						}
						codec.writeToDoc(null, fieldName.inTable(), data, docSet, null);
						break;
					}
				}
			}

			statistic.countInsertOne++;
			final InsertOneResult result = this.session != null ? collection.insertOne(this.session, docSet)
					: collection.insertOne(docSet);
		} catch (final Exception ex) {
			LOGGER.error("Fail Mongo request: {} ({})", ex.getMessage(), ex.getClass().getSimpleName(), ex);
			throw new DataAccessException("Fail to Insert data in DB : " + ex.getMessage(), ex);
		}
		final List<LazyGetter> asyncActions = new ArrayList<>();
		for (final DbPropertyDescriptor desc : model.getAsyncInsertFields()) {
			final Object fieldValue = desc.getProperty().getValue(data);
			desc.getAddOn().asyncInsert(this, clazz, uniqueId, desc, fieldValue, asyncActions, options);
		}
		List<LazyGetter> actionsAsync = asyncActions;
		for (int kkk = 0; kkk < 500 && actionsAsync.size() != 0; kkk++) {
			final List<LazyGetter> actionsAsyncNew = new ArrayList<>();
			for (final LazyGetter action : actionsAsync) {
				action.doRequest(actionsAsyncNew);
			}
			actionsAsync = actionsAsyncNew;
		}
		return uniqueId;
	}

	/**
	 * Updates entities matching the specified conditions (QueryOptions variant).
	 *
	 * <p>
	 * <strong>Required option:</strong> You MUST provide a {@link FilterValue} option to specify which
	 * fields should be updated. This prevents accidental full-row updates.
	 * </p>
	 *
	 * <p>
	 * <strong>Condition specification:</strong> Use {@link Condition} option to specify which entities
	 * to update. Without conditions, the operation will fail with an exception.
	 * </p>
	 *
	 * @param <T>     The type of the entity
	 * @param data    The entity data containing the update values
	 * @param options Query options including FilterValue (required) and Condition
	 * @return Number of entities updated
	 * @throws Exception if update operation fails or FilterValue is missing
	 */
	public <T> long update(final T data, QueryOptions options) throws Exception {
		final Class<?> clazz = data.getClass();
		if (options == null) {
			options = new QueryOptions();
		}
		final boolean directdata = options.exist(DirectData.class);
		final boolean forceReadOnlyField = options.exist(ForceReadOnlyField.class);
		final Condition condition = conditionFusionOrEmpty(options, true);
		final List<FilterValue> filterKeys = options != null ? options.get(FilterValue.class) : new ArrayList<>();
		if (filterKeys.size() != 1) {
			throw new DataAccessException("request a gets without/or with more 1 FilterValue of values");
		}
		final FilterValue filterKey = filterKeys.get(0);
		final List<FilterOmit> filterOmitKeys = options != null ? options.get(FilterOmit.class) : new ArrayList<>();
		FilterOmit filterOmitKey = null;
		if (filterOmitKeys.size() > 1) {
			throw new DataAccessException("request a gets without/or with more 1 FilterOmit of values");
		} else if (filterOmitKeys.size() == 1) {
			filterOmitKey = filterOmitKeys.get(0);
		}

		final DbClassModel model = DbClassModel.of(clazz);
		final List<LazyGetter> asyncActions = new ArrayList<>();

		// Some mode need to get the previous data to perform a correct update...
		Object previousData = null;
		if (model.needsPreviousDataForUpdate()) {
			final List<TransmitKey> transmitKey = options.get(TransmitKey.class);
			previousData = this.getById(data.getClass(), transmitKey.get(0).getKey(), new AccessDeletedItems(),
					new ReadAllColumn());
		}

		// real add in the BDD:
		try {
			final String collectionName = model.getTableName(options);
			final String deletedFieldName = model.getDeletedFieldName();
			final Bson filters = condition.getFilter(collectionName, options, deletedFieldName);
			final Document docSet = new Document();
			final Document docUnSet = new Document();

			// --- Handle update timestamp (unconditionally, not subject to filter) ---
			final DbPropertyDescriptor updateTsDesc = model.getUpdateTimestamp();
			if (updateTsDesc != null && !directdata) {
				final FieldName fieldName = updateTsDesc.getFieldName(options);
				docSet.append(fieldName.inTable(), Date.from(Instant.now()));
			}

			// --- Handle addon fields ---
			for (final DbPropertyDescriptor desc : model.getAddonFields()) {
				final FieldName fieldName = desc.getFieldName(options);
				// Apply filter/omit logic
				if (filterOmitKey != null && filterOmitKey.getValues().contains(fieldName.inStruct())) {
					continue;
				}
				if (!filterKey.getValues().contains(fieldName.inStruct())) {
					continue;
				}
				if (!forceReadOnlyField && desc.isApiReadOnly()) {
					continue;
				}
				if (desc.isAsyncUpdate()) {
					final List<TransmitKey> transmitKey = options.get(TransmitKey.class);
					if (transmitKey.size() != 1) {
						throw new DataAccessException(
								"Fail to transmit Key to update the async update... (must have only 1)");
					}
					desc.getAddOn().asyncUpdate(this, previousData, transmitKey.get(0).getKey(), desc,
							desc.getProperty().getValue(data), asyncActions, options);
				}
				if (!desc.canInsert()) {
					continue;
				}
				desc.getAddOn().insertData(this, desc, data, options, docSet, docUnSet);
			}

			// --- Handle regular fields (via pre-compiled codecs) ---
			for (final DbPropertyDescriptor desc : model.getRegularFields()) {
				final FieldName fieldName = desc.getFieldName(options);
				// Apply filter/omit logic
				if (filterOmitKey != null && filterOmitKey.getValues().contains(fieldName.inStruct())) {
					continue;
				}
				if (!filterKey.getValues().contains(fieldName.inStruct())) {
					continue;
				}
				if (!forceReadOnlyField && desc.isApiReadOnly()) {
					continue;
				}
				final MongoFieldCodec codec = desc.getCodec();
				if (codec == null) {
					continue;
				}
				final PropertyDescriptor prop = desc.getProperty();
				if (!prop.getType().isPrimitive()) {
					final Object tmp = prop.getValue(data);
					if (tmp == null && desc.getDefaultValue() != null) {
						continue;
					}
				}
				codec.writeToDoc(null, fieldName.inTable(), data, docSet, docUnSet);
			}

			// Do the query ...
			final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
			final Document actions = new Document();
			if (!docSet.isEmpty()) {
				actions.append("$set", docSet);
			}
			if (!docUnSet.isEmpty()) {
				actions.append("$unset", docUnSet);
			}

			statistic.countUpdateMany++;
			final UpdateResult ret = this.session != null ? collection.updateMany(this.session, filters, actions)
					: collection.updateMany(filters, actions);
			List<LazyGetter> actionsAsync = asyncActions;
			for (int kkk = 0; kkk < 500 && actionsAsync.size() != 0; kkk++) {
				final List<LazyGetter> actionsAsyncNew = new ArrayList<>();
				for (final LazyGetter action : actionsAsync) {
					action.doRequest(actionsAsyncNew);
				}
				actionsAsync = actionsAsyncNew;
			}
			return ret.getModifiedCount();
		} catch (final Exception ex) {
			LOGGER.error("Error in update: {}", ex.getMessage(), ex);
			throw ex;
		}
	}

	/**
	 * Generates the list of field names to include in a MongoDB projection.
	 *
	 * @param clazz   The entity class
	 * @param options Query options that may affect field selection
	 * @return List of database field names to include in the query projection
	 * @throws Exception if class introspection fails
	 */
	public List<String> generateSelectField(final Class<?> clazz, final QueryOptions options) throws Exception {
		final boolean readAllFields = QueryOptions.readAllColumn(options);
		final DbClassModel dbModel = DbClassModel.of(clazz);
		return dbModel.generateSelectFields(readAllFields, options);
	}

	/**
	 * Merges all {@link Condition} options into a single condition using logical AND.
	 *
	 * <p>If no conditions are present and {@code throwIfEmpty} is true, throws an exception.
	 * Otherwise returns an empty condition.
	 *
	 * @param options      The query options containing zero or more conditions
	 * @param throwIfEmpty If true, throws a {@link DataAccessException} when no conditions are provided
	 * @return The merged condition
	 * @throws DataAccessException if throwIfEmpty is true and no conditions are present
	 */
	public Condition conditionFusionOrEmpty(final QueryOptions options, final boolean throwIfEmpty)
			throws DataAccessException {
		if (options == null) {
			return new Condition();
		}
		final List<Condition> conditions = options.get(Condition.class);
		if (conditions.size() == 0) {
			if (throwIfEmpty) {
				throw new DataAccessException("request a gets without any condition");
			} else {
				return new Condition();
			}
		}
		Condition condition = null;
		if (conditions.size() == 1) {
			condition = conditions.get(0);
		} else {
			condition = new Condition(Filters.and(conditions.stream().map(Condition::getFilter).toList()));
		}
		return condition;
	}

	/**
	 * Retrieves all entities of the specified type matching the conditions (raw QueryOptions variant).
	 *
	 * @param clazz   The class of the entity
	 * @param options Query options including conditions, filters, limits, etc.
	 * @return List of matching entities as Objects
	 * @throws DataAccessException if a data access error occurs
	 * @throws IOException         if an I/O error occurs
	 */
	public List<Object> getsRaw(final Class<?> clazz, final QueryOptions options)
			throws DataAccessException, IOException {
		final Condition condition = conditionFusionOrEmpty(options, false);
		final List<LazyGetter> lazyCall = new ArrayList<>();
		final DbClassModel model;
		try {
			model = DbClassModel.of(clazz);
		} catch (final IntrospectionException e) {
			throw new DataAccessException("Failed to introspect class: " + clazz.getSimpleName(), e);
		}
		final String deletedFieldName = model.getDeletedFieldName();
		final String collectionName = model.getTableName(options);
		final List<Object> outs = new ArrayList<>();
		final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
		try {
			// Generate the filtering of the data:
			final Bson filters = condition.getFilter(collectionName, options, deletedFieldName);
			if (filters != null) {
				LOGGER.trace("filter = {}",
						filters.toBsonDocument().toJson(JsonWriterSettings.builder().indent(true).build()));
			} else {
				LOGGER.trace("filter = None");
			}
			FindIterable<Document> retFind = null;
			statistic.countFind++;
			if (filters != null) {
				// LOGGER.debug("getsWhere Find filter: {}", filters.toBsonDocument().toJson());
				retFind = this.session != null ? collection.find(this.session, filters) : collection.find(filters);
			} else {
				retFind = this.session != null ? collection.find(this.session) : collection.find();
			}
			final List<OrderBy> orders = options.get(OrderBy.class);
			if (orders.size() != 0) {
				final Document sorts = new Document();
				for (final OrderBy order : orders) {
					order.generateSort(sorts);
				}
				retFind = retFind.sort(sorts);
			}

			final List<Skip> skips = options.get(Skip.class);
			if (skips.size() == 1) {
				retFind = retFind.skip((int) skips.get(0).getValue());
			} else if (skips.size() > 1) {
				throw new DataAccessException("Request with multiple 'skip'...");
			}

			final List<Limit> limits = options.get(Limit.class);
			if (limits.size() == 1) {
				retFind = retFind.limit((int) limits.get(0).getValue());
			} else if (limits.size() > 1) {
				throw new DataAccessException("Request with multiple 'limit'...");
			}
			// Select values to read
			final List<String> listFields = generateSelectField(clazz, options);
			listFields.add("_id");
			retFind = retFind.projection(Projections.include(listFields.toArray(new String[0])));

			LOGGER.trace("GetsWhere ...");
			final LazyGetterCollector batchCollector = new LazyGetterCollector();
			final MongoCursor<Document> cursor = retFind.iterator();
			try (cursor) {
				while (cursor.hasNext()) {
					final Document doc = cursor.next();
					LOGGER.trace(" - receive data from DB: {}",
							doc.toJson(JsonWriterSettings.builder().indent(true).build()));
					final Object data = createObjectFromDocument(doc, clazz, options, lazyCall, batchCollector);
					outs.add(data);
				}
				// Add batched lazy getters (entity-reference fields grouped by target entity)
				if (!batchCollector.isEmpty()) {
					lazyCall.addAll(batchCollector.buildLazyGetters(this));
				}
				// LOGGER.trace("Async calls: {}", lazyCall.size());
				List<LazyGetter> actionsAsync = lazyCall;
				for (int kkk = 0; kkk < 500 && actionsAsync.size() != 0; kkk++) {
					final List<LazyGetter> actionsAsyncNew = new ArrayList<>();
					for (final LazyGetter action : actionsAsync) {
						action.doRequest(actionsAsyncNew);
					}
					actionsAsync = actionsAsyncNew;
				}
			}
		} catch (final Exception ex) {
			LOGGER.error("Failed to retrieve data: {}", ex.getMessage(), ex);
			throw new DataAccessException("Catch an Exception: " + ex.getMessage());
		}
		return outs;
	}

	/**
	 * Creates a Java object from a MongoDB document using the entity class model and codecs.
	 *
	 * <p>Handles POJO construction, add-on fields (ManyToOne, OneToMany, etc.), and type overrides.
	 *
	 * @param doc            The MongoDB document (or scalar value) to convert
	 * @param type           The target Java type (Class or ParameterizedType)
	 * @param options        Query options affecting field selection and type overrides
	 * @param lazyCall       List to collect deferred entity-reference loading actions
	 * @param batchCollector Collector for batching entity-reference loads across rows
	 * @return The constructed Java object, or null if the document is null
	 * @throws Exception if object construction fails
	 */
	public Object createObjectFromDocument(
			final Object doc,
			final Type type,
			final QueryOptions options,
			final List<LazyGetter> lazyCall,
			final LazyGetterCollector batchCollector) throws Exception {
		if (doc == null) {
			return null;
		}
		// For non-Class types (ParameterizedType: List, Set, Map, etc.) use pre-compiled codec
		if (!(type instanceof final Class<?> clazz)) {
			return MongoCodecFactory.buildReader(TypeInfo.fromType(type)).fromMongo(doc);
		}
		// For scalar types (non-Document), use pre-compiled codec
		if (!(doc instanceof final Document documentModel)) {
			return MongoCodecFactory.buildReader(TypeInfo.ofRaw(clazz)).fromMongo(doc);
		}
		// POJO path: Document → Java object with AddOn/OptionSpecifyType support
		final List<OptionSpecifyType> specificTypes = options.get(OptionSpecifyType.class);
		final boolean readAllfields = QueryOptions.readAllColumn(options);
		final DbClassModel dbModel = DbClassModel.of(clazz);

		// Create instance via ClassModel's default constructor
		final Object data = dbModel.getClassModel().newInstance();

		for (final DbPropertyDescriptor desc : dbModel.getAllFields()) {
			if (!readAllfields && desc.isNotRead()) {
				continue;
			}
			if (desc.getAction() == DbFieldAction.ADDON) {
				if (!desc.canRetrieve()) {
					continue;
				}
				desc.getAddOn().fillFromDoc(this, documentModel, desc, data, options, lazyCall, batchCollector);
			} else {
				final MongoFieldCodec codec = desc.getCodec();
				if (codec == null) {
					continue;
				}
				final FieldName fieldName = desc.getFieldName(options);

				// Check for OptionSpecifyType override (rare runtime type change)
				MongoTypeReader overrideReader = null;
				for (final OptionSpecifyType specify : specificTypes) {
					if (specify.name.equals(desc.getProperty().getName())) {
						TypeInfo overrideType;
						if (specify.isList) {
							if (desc.getProperty().getTypeInfo().isList()) {
								overrideType = new TypeInfo(List.class, specify.clazz, null,
										TypeUtils.listOf(specify.clazz));
								overrideReader = MongoCodecFactory.buildReader(overrideType);
							}
						} else if (desc.getProperty().getType() == Object.class) {
							overrideType = TypeInfo.ofRaw(specify.clazz);
							overrideReader = MongoCodecFactory.buildReader(overrideType);
						}
						break;
					}
				}

				if (overrideReader != null) {
					// Rare path: runtime type override
					codec.readFromDoc(documentModel, fieldName.inTable(), overrideReader, data);
				} else {
					// Fast path: pre-compiled codec
					codec.readFromDoc(documentModel, fieldName.inTable(), data);
				}
			}
		}
		return data;
	}

	/**
	 * Counts the number of entities matching the specified conditions (QueryOptions variant).
	 *
	 * @param clazz   The class of the entity
	 * @param options Query options including conditions, filters, etc.
	 * @return The number of matching entities
	 * @throws Exception if count operation fails
	 */
	public long count(final Class<?> clazz, final QueryOptions options) throws Exception {
		final Condition condition = conditionFusionOrEmpty(options, false);
		final DbClassModel model = DbClassModel.of(clazz);
		final String deletedFieldName = model.getDeletedFieldName();
		final String collectionName = model.getTableName(options);
		final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
		try {
			// Generate the filtering of the data:
			final Bson filters = condition.getFilter(collectionName, options, deletedFieldName);
			statistic.countCountDocuments++;
			if (filters != null) {
				return this.session != null ? collection.countDocuments(this.session, filters)
						: collection.countDocuments(filters);
			}
			return this.session != null ? collection.countDocuments(this.session) : collection.countDocuments();
		} catch (final Exception ex) {
			LOGGER.error("Failed to count documents: {}", ex.getMessage(), ex);
			throw new DataAccessException("Catch an Exception: " + ex.getMessage());
		}
	}

	/**
	 * Performs a hard (physical) delete of an entity by its unique identifier.
	 *
	 * <p>
	 * <strong>Warning:</strong> This permanently removes the entity from the database,
	 * regardless of whether the class is annotated with {@code @SoftDeleted}.
	 * The data cannot be recovered after this operation.
	 * </p>
	 *
	 * @param <ID_TYPE> The type of the ID
	 * @param clazz     The class of the entity
	 * @param id        The unique identifier of the entity to delete
	 * @param option    Optional query options
	 * @return Number of entities deleted (typically 0 or 1)
	 * @throws Exception if delete operation fails
	 */
	public <ID_TYPE> long deleteHardById(final Class<?> clazz, final ID_TYPE id, final QueryOption... option)
			throws Exception {
		final QueryOptions options = new QueryOptions(option);
		options.add(new Condition(getTableIdCondition(clazz, id, options)));
		return deleteHard(clazz, options.getAllArray());
	}

	/**
	 * Executes pre-delete actions for add-on fields (e.g., cascade deletes, link cleanup).
	 *
	 * @param clazz  The entity class being deleted
	 * @param option Query options used to locate the entities being deleted
	 * @throws Exception if any pre-delete action fails
	 */
	public void actionOnDelete(final Class<?> clazz, final QueryOption... option) throws Exception {
		final List<LazyGetter> lazyCall = new ArrayList<>();
		final DbClassModel model = DbClassModel.of(clazz);
		// Use pre-computed delete action fields
		final List<DbPropertyDescriptor> deleteActionFields = model.getDeleteActionFields();
		List<Object> previousData = null;
		if (model.needsPreviousDataForDelete()) {
			final QueryOptions options = new QueryOptions(option);
			options.add(new AccessDeletedItems());
			options.add(new ReadAllColumn());
			previousData = this.getsRaw(clazz, options);
		}
		for (final DbPropertyDescriptor desc : deleteActionFields) {
			desc.getAddOn().onDelete(this, clazz, desc, previousData, lazyCall);
		}
		List<LazyGetter> actionsAsync = lazyCall;
		for (int kkk = 0; kkk < 500 && actionsAsync.size() != 0; kkk++) {
			final List<LazyGetter> actionsAsyncNew = new ArrayList<>();
			for (final LazyGetter action : actionsAsync) {
				action.doRequest(actionsAsyncNew);
			}
			actionsAsync = actionsAsyncNew;
		}
	}

	/**
	 * Performs a hard (physical) delete of entities matching the specified conditions.
	 *
	 * <p>
	 * <strong>Warning:</strong> This permanently removes matching entities from the database,
	 * regardless of whether the class is annotated with {@code @SoftDeleted}.
	 * The data cannot be recovered after this operation.
	 * </p>
	 *
	 * <p>
	 * <strong>Required:</strong> You MUST provide conditions via {@link Condition} option.
	 * Attempting to delete without conditions will throw an exception to prevent accidental
	 * deletion of all records.
	 * </p>
	 *
	 * @param clazz  The class of the entity
	 * @param option Query options including conditions (required)
	 * @return Number of entities deleted
	 * @throws Exception if delete operation fails or no conditions provided
	 */
	public long deleteHard(final Class<?> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		// ForceHardDelete implies AccessDeletedItems: when physically removing records,
		// we must be able to find already soft-deleted records.
		if (options.exist(ForceHardDelete.class) && !options.exist(AccessDeletedItems.class)) {
			options.add(new AccessDeletedItems());
		}
		final Condition condition = conditionFusionOrEmpty(options, true);
		final DbClassModel model = DbClassModel.of(clazz);
		final String collectionName = model.getTableName(options);
		final String deletedFieldName = model.getDeletedFieldName();
		final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
		final Bson filters = condition.getFilter(collectionName, options, deletedFieldName);

		// If the entity has an @DataAsyncHardDeleted field and ForceHardDelete is not requested,
		// perform an async hard delete (set hardDeleted=true and deleted=true) instead of physical removal.
		final String asyncHardDeletedFieldName = model.getAsyncHardDeletedFieldName();
		if (asyncHardDeletedFieldName != null && !options.exist(ForceHardDelete.class)) {
			final DbPropertyDescriptor updateTsDesc = model.getUpdateTimestamp();
			final Document setFields = new Document(asyncHardDeletedFieldName, true);
			if (deletedFieldName != null) {
				setFields.append(deletedFieldName, true);
			}
			if (updateTsDesc != null) {
				setFields.append(updateTsDesc.getDbFieldName(), Date.from(Instant.now()));
			}
			final Document actions = new Document("$set", setFields);
			if (filters == null) {
				throw new DataAccessException("Too dangerous to delete element with no filter values !!!");
			}
			statistic.countUpdateMany++;
			final UpdateResult ret = this.session != null ? collection.updateMany(this.session, filters, actions)
					: collection.updateMany(filters, actions);
			return ret.getModifiedCount();
		}

		actionOnDelete(clazz, option);

		DeleteResult retFind;
		if (filters != null) {
			statistic.countDeleteMany++;
			retFind = this.session != null ? collection.deleteMany(this.session, filters)
					: collection.deleteMany(filters);
		} else {
			throw new DataAccessException("Too dangerous to delete element with no filter values !!!");
		}
		return retFind.getDeletedCount();
	}

	/**
	 * Performs a soft delete of an entity by its unique identifier.
	 *
	 * <p>
	 * Soft delete marks the entity as deleted without physically removing it from the database.
	 * The entity will be excluded from normal queries unless {@code AccessDeletedItems} option is used.
	 * </p>
	 *
	 * <p>
	 * <strong>Requirement:</strong> The entity class must be annotated with {@code @SoftDeleted}
	 * and have a corresponding deleted flag field.
	 * </p>
	 *
	 * @param <ID_TYPE> The type of the ID
	 * @param clazz     The class of the entity
	 * @param id        The unique identifier of the entity to soft delete
	 * @param option    Optional query options
	 * @return Number of entities soft deleted (typically 0 or 1)
	 * @throws Exception if soft delete operation fails
	 */
	public <ID_TYPE> long deleteSoftById(final Class<?> clazz, final ID_TYPE id, final QueryOption... option)
			throws Exception {
		final QueryOptions options = new QueryOptions(option);
		options.add(new Condition(getTableIdCondition(clazz, id, options)));
		return deleteSoft(clazz, options.getAllArray());
	}

	/**
	 * Performs a soft delete of entities matching the specified conditions.
	 *
	 * <p>
	 * Soft delete marks matching entities as deleted without physically removing them from the database.
	 * These entities will be excluded from normal queries unless {@code AccessDeletedItems} option is used.
	 * </p>
	 *
	 * <p>
	 * <strong>Requirement:</strong> The entity class must be annotated with {@code @SoftDeleted}
	 * and have a corresponding deleted flag field.
	 * </p>
	 *
	 * <p>
	 * <strong>Required:</strong> You MUST provide conditions via {@link Condition} option.
	 * </p>
	 *
	 * @param clazz  The class of the entity
	 * @param option Query options including conditions (required)
	 * @return Number of entities soft deleted
	 * @throws Exception if soft delete operation fails or no conditions provided
	 */
	public long deleteSoft(final Class<?> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		final Condition condition = conditionFusionOrEmpty(options, true);
		final DbClassModel model = DbClassModel.of(clazz);
		final String collectionName = model.getTableName(options);
		final String deletedFieldName = model.getDeletedFieldName();
		final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
		final Bson filters = condition.getFilter(collectionName, options, deletedFieldName);
		final DbPropertyDescriptor updateTsDesc = model.getUpdateTimestamp();
		Document actions = null;
		if (updateTsDesc == null) {
			actions = new Document("$set", new Document(deletedFieldName, true));
		} else {
			actions = new Document("$set", new Document(deletedFieldName, true).append(updateTsDesc.getDbFieldName(),
					Date.from(Instant.now())));
		}
		actionOnDelete(clazz, option);
		statistic.countUpdateMany++;
		final UpdateResult ret = this.session != null ? collection.updateMany(this.session, filters, actions)
				: collection.updateMany(filters, actions);
		return ret.getModifiedCount();
	}

	/**
	 * Restores (un-deletes) a soft-deleted entity by its unique identifier.
	 *
	 * <p>
	 * This method clears the soft delete flag, making the entity visible in normal queries again.
	 * Only works for entities that were previously soft deleted.
	 * </p>
	 *
	 * <p>
	 * <strong>Requirement:</strong> The entity class must be annotated with {@code @SoftDeleted}.
	 * </p>
	 *
	 * @param <ID_TYPE> The type of the ID
	 * @param clazz     The class of the entity
	 * @param id        The unique identifier of the entity to restore
	 * @return Number of entities restored (typically 0 or 1)
	 * @throws DataAccessException if the class has no deleted field or restore fails
	 */
	public <ID_TYPE> long restoreById(final Class<?> clazz, final ID_TYPE id) throws DataAccessException {
		return restore(clazz, new Condition(getTableIdCondition(clazz, id, new QueryOptions())));
	}

	/**
	 * Restores (un-deletes) a soft-deleted entity by its unique identifier.
	 *
	 * <p>
	 * This method clears the soft delete flag, making the entity visible in normal queries again.
	 * Only works for entities that were previously soft deleted.
	 * </p>
	 *
	 * <p>
	 * <strong>Requirement:</strong> The entity class must be annotated with {@code @SoftDeleted}.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * ObjectId userId = new ObjectId("507f1f77bcf86cd799439011");
	 *
	 * // Soft delete a user
	 * db.deleteSoftById(User.class, userId);
	 *
	 * // Later, restore the user
	 * long restored = db.restoreById(User.class, userId);
	 * if (restored &gt; 0) {
	 *     System.out.println("User restored successfully");
	 * }
	 *
	 * // User is now visible in normal queries again
	 * User user = db.getById(User.class, userId);
	 * </pre>
	 *
	 * @param <ID_TYPE> The type of the ID (e.g., ObjectId, UUID, Long)
	 * @param clazz     The class of the entity
	 * @param id        The unique identifier of the entity to restore (e.g., ObjectId)
	 * @param option    Optional query options
	 * @return Number of entities restored (typically 0 or 1)
	 * @throws DataAccessException if the class has no deleted field or restore fails
	 */
	public <ID_TYPE> long restoreById(final Class<?> clazz, final ID_TYPE id, final QueryOption... option)
			throws DataAccessException {
		final QueryOptions options = new QueryOptions(option);
		options.add(new Condition(getTableIdCondition(clazz, id, options)));
		return restore(clazz, options.getAllArray());
	}

	/**
	 * Restores (un-deletes) soft-deleted entities matching the specified conditions.
	 *
	 * <p>
	 * This method clears the soft delete flag for matching entities, making them visible
	 * in normal queries again. Only works for entities that were previously soft deleted.
	 * </p>
	 *
	 * <p>
	 * <strong>Requirement:</strong> The entity class must be annotated with {@code @SoftDeleted}.
	 * You MUST provide conditions via {@link Condition} option.
	 * </p>
	 *
	 * @param clazz  The class of the entity
	 * @param option Query options including conditions (required)
	 * @return Number of entities restored
	 * @throws DataAccessException if the class has no deleted field, no conditions provided, or restore fails
	 */
	public long restore(final Class<?> clazz, final QueryOption... option) throws DataAccessException {
		final QueryOptions options = new QueryOptions(option);
		final Condition condition = conditionFusionOrEmpty(options, true);
		final DbClassModel model;
		try {
			model = DbClassModel.of(clazz);
		} catch (final IntrospectionException e) {
			throw new DataAccessException("Failed to introspect class: " + clazz.getSimpleName(), e);
		}
		final String collectionName = model.getTableName(options);
		final String deletedFieldName = model.getDeletedFieldName();
		if (deletedFieldName == null) {
			throw new DataAccessException("The class " + clazz.getCanonicalName() + " has no deleted field");
		}
		final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
		final Bson filters = condition.getFilter(collectionName, options, deletedFieldName);
		final Document actions = new Document("$set", new Document(deletedFieldName, false));
		statistic.countUpdateMany++;
		final UpdateResult ret = this.session != null ? collection.updateMany(this.session, filters, actions)
				: collection.updateMany(filters, actions);
		return ret.getModifiedCount();
	}

	/**
	 * Drops (deletes) the entire collection for the specified entity class.
	 *
	 * <p>
	 * <strong>Warning:</strong> This permanently removes the collection and all its documents.
	 * This operation cannot be undone. Use with extreme caution.
	 * </p>
	 *
	 * <p>
	 * Example usage:
	 * </p>
	 * <pre>
	 * // Drop the entire users collection
	 * db.drop(User.class);
	 * </pre>
	 *
	 * @param clazz  The entity class whose collection will be dropped
	 * @param option Optional query options (e.g., table name override)
	 * @throws Exception if drop operation fails
	 */
	public void drop(final Class<?> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		final String collectionName = DbClassModel.of(clazz).getTableName(options);
		final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
		statistic.countDrop++;
		collection.drop();
	}

	/**
	 * Deletes all documents from the collection for the specified entity class.
	 *
	 * <p>
	 * Unlike {@link #drop(Class, QueryOption...)}, this keeps the collection structure
	 * (indexes, schema) but removes all documents. This is a hard delete that cannot be undone.
	 * </p>
	 *
	 * <p>
	 * Example usage:
	 * </p>
	 * <pre>
	 * // Remove all user documents but keep the collection
	 * db.cleanAll(User.class);
	 * </pre>
	 *
	 * @param clazz  The entity class whose documents will be deleted
	 * @param option Optional query options (e.g., table name override)
	 * @throws Exception if clean operation fails
	 */
	public void cleanAll(final Class<?> clazz, final QueryOption... option) throws Exception {
		final QueryOptions options = new QueryOptions(option);
		final String collectionName = DbClassModel.of(clazz).getTableName(options);
		final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
		statistic.countDeleteMany++;
		if (this.session != null) {
			collection.deleteMany(this.session, new Document());
		} else {
			collection.deleteMany(new Document());
		}
	}

	// =======================================================================
	// -- BSON Direct Access API
	// =======================================================================

	/**
	 * Inserts a BSON Document directly into the specified collection.
	 *
	 * <p>
	 * This method bypasses the object mapping layer and inserts a raw BSON document
	 * directly into MongoDB. This is useful for advanced use cases where you need
	 * full control over the document structure.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * Document doc = new Document()
	 *     .append("_id", new ObjectId())
	 *     .append("name", "John Doe")
	 *     .append("email", "john@example.com")
	 *     .append("age", 30);
	 *
	 * ObjectId insertedId = db.insertBsonDocument("users", doc);
	 * </pre>
	 *
	 * @param collectionName Name of the collection to insert into
	 * @param document       BSON Document to insert
	 * @return The ObjectId of the inserted document (auto-generated if not provided)
	 * @throws DataAccessException if insertion fails
	 */
	public ObjectId insertBsonDocument(final String collectionName, final Document document)
			throws DataAccessException {
		try {
			final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
			statistic.countInsertOne++;
			final InsertOneResult result = this.session != null ? collection.insertOne(this.session, document)
					: collection.insertOne(document);
			final Object insertedId = result.getInsertedId();
			if (insertedId != null && insertedId instanceof org.bson.BsonObjectId) {
				return ((org.bson.BsonObjectId) insertedId).getValue();
			}
			// If no ID was returned, check if the document has one
			final Object docId = document.get("_id");
			if (docId instanceof ObjectId) {
				return (ObjectId) docId;
			}
			throw new DataAccessException("Failed to retrieve inserted document ID");
		} catch (final DataAccessException ex) {
			throw ex;
		} catch (final Exception ex) {
			LOGGER.error("Failed to insert BSON document: {}", ex.getMessage(), ex);
			throw new DataAccessException("Failed to insert BSON document: " + ex.getMessage());
		}
	}

	/**
	 * Retrieves a single BSON Document from the specified collection matching the given conditions.
	 *
	 * <p>
	 * This method bypasses the object mapping layer and returns a raw BSON document
	 * directly from MongoDB. This is useful for advanced use cases where you need
	 * full control over the document structure.
	 * </p>
	 *
	 * <p>
	 * Example usage with ObjectId:
	 * </p>
	 * <pre>
	 * ObjectId userId = new ObjectId("507f1f77bcf86cd799439011");
	 *
	 * // Get document by ID
	 * Document userDoc = db.getBsonDocument("users",
	 *     new Condition(Filters.eq("_id", userId)));
	 *
	 * if (userDoc != null) {
	 *     String name = userDoc.getString("name");
	 *     Integer age = userDoc.getInteger("age");
	 * }
	 * </pre>
	 *
	 * @param collectionName Name of the collection to query
	 * @param options        Query options including conditions for filtering
	 * @return The first matching BSON Document or null if not found
	 * @throws DataAccessException if retrieval fails
	 */
	public Document getBsonDocument(final String collectionName, final QueryOption... options)
			throws DataAccessException {
		try {
			final QueryOptions queryOptions = new QueryOptions(options);
			final Condition condition = conditionFusionOrEmpty(queryOptions, false);
			final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
			final Bson filters = condition.getFilter(collectionName, queryOptions, null);
			statistic.countFind++;
			final FindIterable<Document> cursor;
			if (filters != null) {
				cursor = this.session != null ? collection.find(this.session, filters) : collection.find(filters);
			} else {
				cursor = this.session != null ? collection.find(this.session) : collection.find();
			}
			try (MongoCursor<Document> iterator = cursor.iterator()) {
				if (iterator.hasNext()) {
					return iterator.next();
				}
			}
			return null;
		} catch (final Exception ex) {
			LOGGER.error("Failed to retrieve BSON document: {}", ex.getMessage(), ex);
			throw new DataAccessException("Failed to retrieve BSON document: " + ex.getMessage());
		}
	}

	/**
	 * Retrieves multiple BSON Documents from the specified collection matching the given conditions.
	 *
	 * <p>
	 * This method bypasses the object mapping layer and returns raw BSON documents
	 * directly from MongoDB. This is useful for advanced use cases where you need
	 * full control over the document structure.
	 * </p>
	 *
	 * <p>
	 * Example usage:
	 * </p>
	 * <pre>
	 * // Get all users older than 25
	 * List&lt;Document&gt; users = db.getBsonDocuments("users",
	 *     new Condition(Filters.gt("age", 25)),
	 *     new OrderBy("name", true),
	 *     new Limit(10));
	 *
	 * for (Document user : users) {
	 *     System.out.println(user.toJson());
	 * }
	 * </pre>
	 *
	 * @param collectionName Name of the collection to query
	 * @param options        Query options including conditions, ordering, limits
	 * @return List of matching BSON Documents (empty list if none found)
	 * @throws DataAccessException if retrieval fails
	 */
	public List<Document> getBsonDocuments(final String collectionName, final QueryOption... options)
			throws DataAccessException {
		try {
			final QueryOptions queryOptions = new QueryOptions(options);
			final Condition condition = conditionFusionOrEmpty(queryOptions, false);
			final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
			final Bson filters = condition.getFilter(collectionName, queryOptions, null);
			statistic.countFind++;
			FindIterable<Document> cursor;
			if (filters != null) {
				cursor = this.session != null ? collection.find(this.session, filters) : collection.find(filters);
			} else {
				cursor = this.session != null ? collection.find(this.session) : collection.find();
			}

			// Apply ordering
			final List<OrderBy> orders = queryOptions.get(OrderBy.class);
			if (!orders.isEmpty()) {
				final Document sorts = new Document();
				for (final OrderBy order : orders) {
					order.generateSort(sorts);
				}
				cursor = cursor.sort(sorts);
			}

			// Apply limit
			final List<Limit> limits = queryOptions.get(Limit.class);
			if (!limits.isEmpty()) {
				cursor = cursor.limit((int) limits.get(0).getValue());
			}

			final List<Document> results = new ArrayList<>();
			try (MongoCursor<Document> iterator = cursor.iterator()) {
				while (iterator.hasNext()) {
					results.add(iterator.next());
				}
			}
			return results;
		} catch (final Exception ex) {
			LOGGER.error("Failed to retrieve BSON documents: {}", ex.getMessage(), ex);
			throw new DataAccessException("Failed to retrieve BSON documents: " + ex.getMessage());
		}
	}

	/**
	 * Updates BSON Documents in the specified collection matching the given conditions.
	 *
	 * <p>
	 * This method bypasses the object mapping layer and updates documents directly
	 * using MongoDB update operators. This is useful for advanced use cases where you need
	 * full control over the update operation.
	 * </p>
	 *
	 * <p>
	 * Example usage:
	 * </p>
	 * <pre>
	 * // Update all users older than 25, set status to "active"
	 * Document updateOps = new Document("$set",
	 *     new Document("status", "active")
	 *         .append("updatedAt", new Date()));
	 *
	 * long count = db.updateBsonDocuments("users", updateOps,
	 *     new Condition(Filters.gt("age", 25)));
	 *
	 * System.out.println("Updated " + count + " documents");
	 * </pre>
	 *
	 * <p>
	 * <strong>Note:</strong> The updateDocument should use MongoDB update operators like
	 * $set, $inc, $push, etc. See MongoDB documentation for available operators.
	 * </p>
	 *
	 * @param collectionName Name of the collection to update
	 * @param updateDocument BSON Document containing MongoDB update operators
	 * @param options        Query options including conditions for filtering which documents to update
	 * @return Number of documents modified
	 * @throws DataAccessException if update fails
	 */
	public long updateBsonDocuments(
			final String collectionName,
			final Document updateDocument,
			final QueryOption... options) throws DataAccessException {
		try {
			final QueryOptions queryOptions = new QueryOptions(options);
			final Condition condition = conditionFusionOrEmpty(queryOptions, false);
			final MongoCollection<Document> collection = this.db.getDatabase().getCollection(collectionName);
			final Bson filters = condition.getFilter(collectionName, queryOptions, null);
			statistic.countUpdateMany++;
			if (filters != null) {
				final UpdateResult result = this.session != null
						? collection.updateMany(this.session, filters, updateDocument)
						: collection.updateMany(filters, updateDocument);
				return result.getModifiedCount();
			}
			// If no filter, update all documents
			final UpdateResult result = this.session != null
					? collection.updateMany(this.session, new Document(), updateDocument)
					: collection.updateMany(new Document(), updateDocument);
			return result.getModifiedCount();
		} catch (final Exception ex) {
			LOGGER.error("Failed to update BSON documents: {}", ex.getMessage(), ex);
			throw new DataAccessException("Failed to update BSON documents: " + ex.getMessage());
		}
	}

}
