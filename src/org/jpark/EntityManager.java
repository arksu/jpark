package org.jpark;

import org.jpark.helper.IdentityWeakHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * менеджер сущностей, по мотивам JPA
 * не реализует стандарт JPA никаким боком
 * заточен только под MySQL/MariaDB
 * пока эта штука поддерживает сущности с 1 ключевым полем для инсерта/апдейта/удаления
 * либо вообще без ключевых полей для инсерта
 */
public class EntityManager
{
	private static final Logger _log = LoggerFactory.getLogger(EntityManager.class.getName());

	private Map<Class<?>, ClassDescriptor> _descriptors = new HashMap<>(4);

	private ConnectionFactory _connectionFactory;

	private Map<Object, Object> _cloneMap;

	public EntityManager()
	{
		_cloneMap = createMap();
	}

	/**
	 * фабрика для получения коннектов к базе
	 */
	public interface ConnectionFactory
	{
		Connection get();
	}

	/**
	 * передадим фабрику коннектов
	 */
	public void setConnectionFactory(ConnectionFactory factory)
	{
		_connectionFactory = factory;
	}

	public Connection beginTransaction() throws SQLException
	{
		// TODO: leak detect
		Connection connection = _connectionFactory.get();
		connection.setAutoCommit(false);
		return connection;
	}

	public void commit(Connection connection) throws SQLException
	{
		// TODO commit
		connection.setAutoCommit(true);
		connection.commit();
		connection.close();
	}

	public void rollback(Connection connection) throws SQLException
	{
		// TODO
		connection.rollback();
		connection.setAutoCommit(true);
		connection.close();
	}

	/**
	 * добавить класс сущности
	 */
	public void addEntityClass(Class<?> clazz)
	{
		ClassDescriptor descriptor = new ClassDescriptor(clazz);
		_descriptors.put(clazz, descriptor);
	}

	/**
	 * деплой сущностей в базу если необходимо
	 */
	public void deploy() throws SQLException
	{
		try (Connection c = _connectionFactory.get())
		{
			for (ClassDescriptor descriptor : _descriptors.values())
			{
				descriptor.deploy(c);
			}
		}
	}

	/**
	 * сохранить/обновить сущность в базу
	 */
	public void persist(Object entity)
	{
		try (Connection connection = _connectionFactory.get())
		{
			persist(entity, connection);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	public void persist(Object entity, Connection connection)
	{
		ClassDescriptor descriptor = getDescriptor(entity);
		if (descriptor == null)
		{
			throw new IllegalArgumentException("Not entity object, no class descriptor");
		}

		// ищем среди обслуживаемых сущностей такую
		Object clone = _cloneMap.get(entity);

		// если нашли - значит надо делать дифф и писать в базу апдейт
		if (clone != null)
		{
			if (entity != clone)
			{
				if (descriptor.getPrimaryKeyFields().size() == 0)
				{
					throw new RuntimeException("No primary key for entity");
				}

				_log.debug("entity FOUND, UPDATE");

				try
				{
					// не можем получить простой запрос поскольку смотрим на дифф полей объекта
					// и запрос зависит от реально изменившихся полей объекта, поэтому и кэшировать запрос не можем
					StringBuilder sql = new StringBuilder("UPDATE ");
					sql.append(descriptor.getTable().getName())
					   .append(" SET ");

					int updatedCount = 0;
					List<Object> changes = null;
					final List<DatabaseField> fields = descriptor.getFields();
					for (int i = 0; i < fields.size(); i++)
					{
						final DatabaseField dbField = fields.get(i);
						final Field field = dbField.getField();
						final Object firstValue = field.get(entity);
						final Object secondValue = field.get(clone);

						if (!DatabasePlatform.compareObjectValues(firstValue, secondValue))
						{
							if (dbField.isPrimaryKey())
							{
								throw new RuntimeException("Update primary key");
							}
							if (!dbField.isUpdatable())
							{
								throw new RuntimeException("Field <" + dbField.getName() + "> is not updatable");
							}
							if (updatedCount > 0)
							{
								sql.append(", ");
							}
							updatedCount++;
							sql.append(dbField.getName())
							   .append("=?");
							if (changes == null)
							{
								changes = new ArrayList<>();
							}
							changes.add(firstValue);
						}
					}
					sql.append(" WHERE ");
					for (int i = 0; i < descriptor.getPrimaryKeyFields().size(); i++)
					{
						sql.append(descriptor.getPrimaryKeyFields().get(i).getName())
						   .append("=?");
					}

					if (updatedCount > 0)
					{
						final String rawSql = sql.toString();
						try (PreparedStatement ps = connection.prepareStatement(rawSql))
						{
							_log.debug("execute update SQL " + entity.toString() + ": " + rawSql);

							int index = 0;
							while (index < changes.size())
							{
								index++;
								DatabasePlatform.setParameterValue(changes.get(index - 1), ps, index);
							}

							for (int i = 0; i < descriptor.getPrimaryKeyFields().size(); i++)
							{
								index++;
								Object val = descriptor.getPrimaryKeyFields().get(i).getField().get(entity);
								DatabasePlatform.setParameterValue(val, ps, index);
							}
							ps.executeUpdate();
						}
					}
					else
					{
						_log.debug("updatedCount=" + updatedCount + " no update needed, skip");
					}
				}
				catch (IllegalAccessException e)
				{
					throw new RuntimeException("IllegalAccessException", e);
				}
				catch (SQLException e)
				{
					throw new RuntimeException("SQLException", e);
				}
			}
		}
		else
		{
			// не нашли. создаем новый объект в базе
			// формируем SQL запрос на инсерт
			_log.debug("entity NOT found, INSERT");

			try
			{
				// будем писать в сущность сгенерированного ид только если у нас одно ключевое поле
				boolean isGeneratedOneKey = descriptor.getPrimaryKeyFields().size() == 1 && descriptor.getPrimaryKeyFields().get(0).isUpdateInsertId();

				try (PreparedStatement ps = isGeneratedOneKey ?
						connection.prepareStatement(descriptor.getSimpleInsertSql(), Statement.RETURN_GENERATED_KEYS) :
						connection.prepareStatement(descriptor.getSimpleInsertSql()))
				{
					// создадим клона для сохранения диффа
					if (isGeneratedOneKey)
					{
						clone = descriptor.buildNewInstance();
					}

					// проходим по всем полям дескриптора
					final List<DatabaseField> fields = descriptor.getFields();
					int index = 0;
					for (int i = 0; i < fields.size(); i++)
					{
						final DatabaseField field = fields.get(i);
						if (field.isInsertable())
						{
							index++;
							Object val = field.getField().get(entity);
							DatabasePlatform.setParameterValue(val, ps, index);

							if (isGeneratedOneKey)
							{
								field.getField().set(clone, DatabasePlatform.buildCloneValue(val));
							}
						}
					}

					_log.debug("execute insert SQL " + entity.toString() + ": " + descriptor.getSimpleInsertSql());
					int affectedRows = ps.executeUpdate();

					if (isGeneratedOneKey)
					{
						if (affectedRows == 0)
						{
							throw new SQLException("Insert failed, no affected rows");
						}
						try (ResultSet generatedKeys = ps.getGeneratedKeys())
						{
							if (generatedKeys.next())
							{
								final DatabaseField field = descriptor.getPrimaryKeyFields().get(0);
								final Object val = DatabasePlatform.getObjectThroughOptimizedDataConversion(generatedKeys, field, 1);

								ps.close();
								field.getField().set(entity, val);
								field.getField().set(clone, DatabasePlatform.buildCloneValue(val));

								// добавим в мапу только если реально получили ид после инсерта и обновили в сущности
								_cloneMap.put(entity, clone);
							}
							else
							{
								throw new SQLException("Insert user failed, no ID obtained.");
							}
						}
					}
				}
			}
			catch (IllegalAccessException e)
			{
				throw new RuntimeException("IllegalAccessException", e);
			}
			catch (SQLException e)
			{
				throw new RuntimeException("SQLException", e);
			}
		}
	}

	/**
	 * искать и загрузить сущность по ключевому полю (id)
	 */
	public <T> T findById(Class<T> entityClass, Object primaryKeyValue)
	{
		try (Connection connection = _connectionFactory.get())
		{
			return findById(entityClass, connection, primaryKeyValue);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	public <T> T findById(Class<T> entityClass, Connection connection, Object primaryKeyValue)
	{
		ClassDescriptor descriptor = _descriptors.get(entityClass);
		if (descriptor == null)
		{
			throw new IllegalArgumentException("Not entity object, no class descriptor");
		}

		try
		{
			try (PreparedStatement ps = connection.prepareStatement(descriptor.getSimpleSelectSql()))
			{
				DatabasePlatform.setParameterValue(primaryKeyValue, ps, 1);
				_log.debug("execute select SQL " + entityClass.getName() + ": " + descriptor.getSimpleSelectSql());
				final ResultSet resultSet = ps.executeQuery();

				if (!resultSet.next())
				{
					return null;
				}

				// создаем объект дефолтным конструктором
				final Object workingCopy = descriptor.buildNewInstance();
				final Object clone = descriptor.buildNewInstance();

				final List<DatabaseField> fields = descriptor.getFields();
				// проходим по поляем объекта через дескриптор
				for (int i = 0; i < fields.size(); i++)
				{
					final DatabaseField field = fields.get(i);

					// получаем значения полей
					final Object val = DatabasePlatform.getObjectThroughOptimizedDataConversion(resultSet, field, i + 1);

					// пишем их в поля клона, используя buildCloneValue, т.е. значения тоже клоним если надо
					field.getField().set(workingCopy, val);
					field.getField().set(clone, DatabasePlatform.buildCloneValue(val));
				}

				// запоминаем клона в мапе
				_cloneMap.put(workingCopy, clone);

				return (T) workingCopy;
			}
		}
		catch (IllegalAccessException e)
		{
			throw new RuntimeException("IllegalAccessException", e);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	public <T> T findOne(Class<T> entityClass, String field, Object primaryKeyValue)
	{
		try (Connection connection = _connectionFactory.get())
		{
			return findOne(entityClass, connection, field, primaryKeyValue);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	public <T> T findOne(Class<T> entityClass, Connection connection, String field, Object primaryKeyValue)
	{
		ClassDescriptor descriptor = _descriptors.get(entityClass);
		if (descriptor == null)
		{
			throw new IllegalArgumentException("Not entity object, no class descriptor");
		}

		try
		{
			final String sql = descriptor.getSelectOneSql(field);
			try (PreparedStatement ps = connection.prepareStatement(sql))
			{
				DatabasePlatform.setParameterValue(primaryKeyValue, ps, 1);
				_log.debug("execute select SQL " + entityClass.getName() + ": " + sql);
				final ResultSet resultSet = ps.executeQuery();

				if (!resultSet.next())
				{
					return null;
				}

				// создаем объект дефолтным конструктором
				final Object workingCopy = descriptor.buildNewInstance();
				final Object clone = descriptor.buildNewInstance();

				final List<DatabaseField> fields = descriptor.getFields();
				// проходим по поляем объекта через дескриптор
				for (int i = 0; i < fields.size(); i++)
				{
					final DatabaseField f = fields.get(i);

					// получаем значения полей
					final Object val = DatabasePlatform.getObjectThroughOptimizedDataConversion(resultSet, f, i + 1);

					// пишем их в поля клона, используя buildCloneValue, т.е. значения тоже клоним если надо
					f.getField().set(workingCopy, val);
					f.getField().set(clone, DatabasePlatform.buildCloneValue(val));
				}

				// запоминаем клона в мапе
				_cloneMap.put(workingCopy, clone);

				return (T) workingCopy;
			}
		}
		catch (IllegalAccessException e)
		{
			throw new RuntimeException("IllegalAccessException", e);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	/**
	 * найти сущности используя прямой SQL запрос
	 */
	public <T> List<T> findAll(Class<T> entityClass, String sql, Object... params)
	{
		try (Connection connection = _connectionFactory.get())
		{
			return findAll(entityClass, connection, sql, params);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	public <T> List<T> findAll(Class<T> entityClass, Connection connection, String sql, Object... params)
	{
		ClassDescriptor descriptor = _descriptors.get(entityClass);
		if (descriptor == null)
		{
			throw new IllegalArgumentException("Not entity object, no class descriptor");
		}

		try
		{
			try (PreparedStatement ps = connection.prepareStatement(sql))
			{
				List<T> result = new ArrayList<>();

				for (int i = 0; i < params.length; i++)
				{
					DatabasePlatform.setParameterValue(params[i], ps, i + 1);
				}

				_log.debug("execute select SQL " + entityClass.getName() + ": " + sql);
				final ResultSet resultSet = ps.executeQuery();

				final List<DatabaseField> fields = descriptor.getFields();
				while (resultSet.next())
				{
					// создаем объект дефолтным конструктором
					final Object workingCopy = descriptor.buildNewInstance();
					final Object clone = descriptor.buildNewInstance();

					// проходим по поляем объекта через дескриптор
					for (int i = 0; i < fields.size(); i++)
					{
						final DatabaseField field = fields.get(i);

						// получаем значения полей
						final Object val = DatabasePlatform.getObjectThroughOptimizedDataConversion(resultSet, field, i + 1);

						// пишем их в поля клона, используя buildCloneValue, т.е. значения тоже клоним если надо
						field.getField().set(workingCopy, val);
						field.getField().set(clone, DatabasePlatform.buildCloneValue(val));

					}
					// запоминаем клона в мапе
					_cloneMap.put(workingCopy, clone);

					result.add(((T) workingCopy));
				}

				return result;
			}
		}
		catch (IllegalAccessException e)
		{
			throw new RuntimeException("IllegalAccessException", e);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	/**
	 * перезагрузить управляемую сущность из базы
	 */
	public void refresh(Object entity)
	{
		try (Connection connection = _connectionFactory.get())
		{
			refresh(entity, connection);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	public void refresh(Object entity, Connection connection)
	{
		Object clone = _cloneMap.get(entity);
		if (clone != null)
		{
			ClassDescriptor descriptor = getDescriptor(entity);
			if (descriptor == null)
			{
				throw new IllegalArgumentException("Not entity object, no class descriptor");
			}
			final List<DatabaseField> pkFields = descriptor.getPrimaryKeyFields();
			if (pkFields.size() != 1)
			{
				throw new RuntimeException("Wrong PK size");
			}

			try
			{
				final Object primaryKeyValue = pkFields.get(0).getField().get(entity);

				try (PreparedStatement ps = connection.prepareStatement(descriptor.getSimpleSelectSql()))
				{
					DatabasePlatform.setParameterValue(primaryKeyValue, ps, 1);
					_log.debug("execute refresh SQL " + entity + ": " + descriptor.getSimpleSelectSql());
					final ResultSet resultSet = ps.executeQuery();

					if (!resultSet.next())
					{
						throw new RuntimeException("Select return has no data");
					}

					final List<DatabaseField> fields = descriptor.getFields();
					// проходим по поляем объекта через дескриптор
					for (int i = 0; i < fields.size(); i++)
					{
						final DatabaseField field = fields.get(i);

						// получаем значения полей
						final Object val = DatabasePlatform.getObjectThroughOptimizedDataConversion(resultSet, field, i + 1);

						// пишем их в поля клона, используя buildCloneValue, т.е. значения тоже клоним если надо
						field.getField().set(entity, val);
						field.getField().set(clone, DatabasePlatform.buildCloneValue(val));
					}

					// запоминаем клона в мапе
					_cloneMap.put(entity, clone);
				}
			}
			catch (IllegalAccessException e)
			{
				throw new RuntimeException("IllegalAccessException", e);
			}
			catch (SQLException e)
			{
				throw new RuntimeException("SQLException", e);
			}
		}
		else
		{
			throw new RuntimeException("Could not refresh not managed entity");
		}
	}

	/**
	 * удалить сущность из базы
	 * сущность может быть и не управляемой - тогда надо в ней проставить только Id поля и передать сюда,
	 * будет удалена по id
	 */
	public void remove(Object entity)
	{
		try (Connection connection = _connectionFactory.get())
		{
			remove(entity, connection);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	public void remove(Object entity, Connection connection)
	{
		ClassDescriptor descriptor = getDescriptor(entity);
		if (descriptor == null)
		{
			throw new IllegalArgumentException("Not entity object, no class descriptor");
		}

		final List<DatabaseField> pkFields = descriptor.getPrimaryKeyFields();
		if (pkFields.size() != 1)
		{
			throw new RuntimeException("Wrong PK size");
		}

		try
		{
			final Object primaryKeyValue = pkFields.get(0).getField().get(entity);

			try (PreparedStatement ps = connection.prepareStatement(descriptor.getSimpleDeleteSql()))
			{
				DatabasePlatform.setParameterValue(primaryKeyValue, ps, 1);
				_log.debug("execute delete SQL " + entity + ": " + descriptor.getSimpleDeleteSql());

				ps.executeQuery();
				_cloneMap.remove(entity);
			}
		}
		catch (IllegalAccessException e)
		{
			throw new RuntimeException("IllegalAccessException", e);
		}
		catch (SQLException e)
		{
			throw new RuntimeException("SQLException", e);
		}
	}

	public void detach(Object entity)
	{
		_cloneMap.remove(entity);
	}

	public boolean contains(Object entity)
	{
		return _cloneMap.containsKey(entity);
	}

	public void clear()
	{
		_cloneMap.clear();
	}

	private ClassDescriptor getDescriptor(Object entity)
	{
		if (entity == null)
		{
			return null;
		}
		return _descriptors.get(entity.getClass());
	}

	private Map<Object, Object> createMap()
	{
		return new IdentityWeakHashMap<>();
	}
}
