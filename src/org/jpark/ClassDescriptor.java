package org.jpark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.awt.windows.ThemeReader;

import javax.persistence.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static org.jpark.DatabasePlatform.APOSTROPHE_CHAR;
import static org.jpark.DatabasePlatform.SEPARATE_CHAR;

/**
 * дескриптор класса сущности
 * храним поля сущности, все нужное для DDL
 */
public class ClassDescriptor
{
	private static final Logger _log = LoggerFactory.getLogger(ClassDescriptor.class.getName());

	private Class<?> _javaClass;
	private String _javaClassName;

	private DatabaseTable _table;
	private List<DatabaseField> _primaryKeyFields;
	private List<DatabaseField> _fields;

	/**
	 * кэшируем SQL запросы для типовых операций по одному ключу
	 */
	private String _simpleInsertSql;
	private String _simpleSelectSql;
	private String _simpleDeleteSql;
	private Map<String, String> _selectOneSql;

	private Constructor<?> _defaultConstructor;

	public ClassDescriptor(Class<?> clazz) throws NoSuchMethodException, IllegalArgumentException
	{
		_fields = new ArrayList<>(8);
		_primaryKeyFields = new ArrayList<>(2);

		_javaClass = clazz;
		_javaClassName = clazz.getName();
		_defaultConstructor = buildDefaultConstructorFor(_javaClass);

		// читаем данные по таблице
		Table tableAnnotation = clazz.getAnnotation(Table.class);
		if (tableAnnotation == null)
		{
			throw new IllegalArgumentException("no table annotaion for class: " + clazz.getName());
		}
		_table = new DatabaseTable();
		_table.setName(tableAnnotation.name());

		// добавим все индексы в таблицу
		final Index[] indexes = tableAnnotation.indexes();
		if (indexes.length > 0)
		{
			for (Index index : indexes)
			{
				IndexDefinition indexDefinition = new IndexDefinition();
				indexDefinition.setName(index.name());
				indexDefinition.setUnique(index.unique());
				indexDefinition.getFields().addAll(Arrays.asList(index.columnList().split(",")));
				_table.getIndexes().add(indexDefinition);
			}
		}

		// дополнительно суффикс создания таблицы
		TableExtended extendedData = clazz.getAnnotation(TableExtended.class);
		if (extendedData != null)
		{
			_table.setDeploy(extendedData.deploy());
			_table.setDropOnDeploy(extendedData.drop());
			_table.setCreateOnDeploy(extendedData.create());
			_table.setTruncateOnDeploy(extendedData.truncate());
			_table.setMigrateOnDeploy(extendedData.migrate());
			_table.setCreationSuffix(extendedData.creationSuffix());
		}

		// читаем поля класса
		for (Field field : clazz.getDeclaredFields())
		{
			field.setAccessible(true);
			// ищем аннотации колонки таблицы
			Column column = field.getAnnotation(Column.class);
			ColumnExtended columnExtended = field.getAnnotation(ColumnExtended.class);
			DatabaseField columnField = null;
			if (column != null)
			{
				columnField = new DatabaseField(field, column, columnExtended, _table);
				_fields.add(columnField);
			}

			Id id = field.getAnnotation(Id.class);
			if (id != null)
			{
				DatabaseField idField;

				// если для поля определен Id но нет определения колонки - то построим колонку по аннотации Id
				if (column == null)
				{
					idField = new DatabaseField(field, _table);
					_fields.add(idField);
				}
				else
				{
					idField = columnField;
				}
				idField.setPrimaryKey(true);
				_primaryKeyFields.add(idField);
			}
		}
	}

	public void deploy(Connection connection) throws SQLException
	{
		// если надо деплоить таблицу
		if (_table.isDeploy())
		{
			// проверим существует ли такая таблица в базе
			boolean exists = _table.checkExists(connection);
			_log.debug("DEPLOY table: " + SEPARATE_CHAR + _table.getName() + SEPARATE_CHAR + ", exists: " + exists);

			try (Statement st = connection.createStatement())
			{
				if (exists)
				{
					// уничтожаем таблицу если надо
					if (_table.isDropOnDeploy())
					{
						String sql = "DROP TABLE " + SEPARATE_CHAR + _table.getName() + SEPARATE_CHAR;
						_log.debug("execute SQL: " + sql);
						st.execute(sql);
						exists = false;
					}
					// очистка таблицы
					else if (_table.isTruncateOnDeploy())
					{
						String sql = "TRUNCATE TABLE " + SEPARATE_CHAR + _table.getName() + SEPARATE_CHAR;
						_log.debug("execute SQL: " + sql);
						st.execute(sql);
					}
					// миграция таблицы (если не уничтожаем)
					if (!_table.isDropOnDeploy() && _table.isMigrateOnDeploy())
					{
						StringBuilder sql = new StringBuilder("SHOW COLUMNS FROM " + SEPARATE_CHAR + _table.getName() + SEPARATE_CHAR);
						_log.debug("MIGRATE. execute SQL: " + sql);
						final ResultSet rs = st.executeQuery(sql.toString());
						// поля в таблице и их типы
						Map<String, String> tableFields = new HashMap<>(8);
						ArrayList<String> enumFields = new ArrayList<>();

						sql = new StringBuilder("ALTER TABLE " + SEPARATE_CHAR + _table.getName() + SEPARATE_CHAR);
						boolean foundToDrop = false;
						// проходим все найденные поля в базе
						while (rs.next())
						{
							// получаем имя столбца
							final String fieldName = rs.getString("Field");
							final String fieldType = rs.getString("Type");
							tableFields.put(fieldName, fieldType);

							// ищем в полях сущности
							boolean found = false;
							for (DatabaseField f : _fields)
							{
								if (fieldName.equals(f.getName()))
								{
									found = true;
									break;
								}
							}
							// если в базе есть поле которого нет в сущности - надо его грохнуть
							if (!found)
							{
								if (foundToDrop)
								{
									sql.append(",");
								}
								sql.append(" DROP COLUMN ").append(SEPARATE_CHAR).append(fieldName).append(SEPARATE_CHAR);
								foundToDrop = true;
							}
							else if (fieldType.toLowerCase().startsWith("enum"))
							{
								enumFields.add(fieldName);
							}
						}
						if (foundToDrop)
						{
							final String finalSql = sql.toString();
							_log.debug("execute SQL: " + finalSql);
							st.executeUpdate(finalSql);
						}

						sql = new StringBuilder("ALTER TABLE " + SEPARATE_CHAR + _table.getName() + SEPARATE_CHAR);
						boolean foundToAdd = false;
						// по всем полям сущности
						for (DatabaseField f : _fields)
						{
							// если поля нет в базе - добавим его в базу
							if (!tableFields.containsKey(f.getName()))
							{
								if (foundToAdd)
								{
									sql.append(",");
								}
								sql.append(" ADD COLUMN ").append(f.getCreateSql());
								foundToAdd = true;
							}
							else
							{
								// поле есть и в базе и в дескрипторе
								// может это Enum?
								if (enumFields.contains(f.getName()))
								{
									final String enumType = tableFields.get(f.getName());
									updateEnum(st, enumType, f);
								}
							}
						}
						if (foundToAdd)
						{
							final String finalSql = sql.toString();
							_log.debug("execute SQL: " + finalSql);
							st.executeUpdate(finalSql);
						}
					}
				}
				// создание таблицы если надо
				if (!exists && _table.isCreateOnDeploy())
				{
					String sql = buildCreateSql();
					_log.debug("execute SQL: " + sql);
					st.executeQuery(sql);
				}
			}
		}
	}

	private void updateEnum(Statement st, String enumType, DatabaseField f) throws SQLException
	{
		// получаем список всех значений в базе
		String[] list = enumType.substring(enumType.indexOf("(") + 1, enumType.indexOf(")")).split(",");

		boolean enumEquals;
		try
		{
			// получаем список всех значений в дескрипторе
			Method method = f.getType().getDeclaredMethod("values");
			Object[] obj = (Object[]) method.invoke(null);
			String[] elist = new String[obj.length];

			for (int i = 0; i < obj.length; i++)
			{
				String e = APOSTROPHE_CHAR + obj[i].toString() + APOSTROPHE_CHAR;
				elist[i] = e;
			}
			// сравним оба списка
			enumEquals = Arrays.equals(list, elist);
			// если енамы НЕ равны надо обновить в базе
			if (!enumEquals)
			{
				StringBuilder sqlEnum = new StringBuilder("ALTER TABLE " + SEPARATE_CHAR + _table.getName() + SEPARATE_CHAR + " CHANGE " + f.getName() + " " + f.getName() + " ENUM(");
				for (int i = 0; i < elist.length; i++)
				{
					sqlEnum.append(elist[i]);
					if ((i + 1) < elist.length)
					{
						sqlEnum.append(", ");
					}
				}
				sqlEnum.append(")");
				String finalSql = sqlEnum.toString();
				_log.debug("execute SQL: " + finalSql);
				st.executeUpdate(finalSql);
			}
		}
		catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e)
		{
			throw new SQLException("Wrong Enum field <" + f.getName() + ">");
		}
	}

	private String buildCreateSql() throws SQLException
	{
		StringBuilder sql = new StringBuilder("CREATE TABLE " + SEPARATE_CHAR + _table.getName() + SEPARATE_CHAR + " (");
		boolean isFirst = true;
		for (DatabaseField field : _fields)
		{
			if (!isFirst)
			{
				sql.append(", ");
			}
			sql.append(field.getCreateSql());
			isFirst = false;
		}

		if (_primaryKeyFields.size() > 0)
		{
			sql.append(", PRIMARY KEY (");
			isFirst = true;
			for (DatabaseField f : _primaryKeyFields)
			{
				if (!isFirst)
				{
					sql.append(", ");
				}
				sql.append(f.getName());
				isFirst = false;
			}
			sql.append(")");
		}

		if (_table.haveIndexes())
		{
			for (int i = 0; i < _table.getIndexes().size(); i++)
			{
				final IndexDefinition index = _table.getIndexes().get(i);
				if (index.isUnique())
				{
					sql.append(", UNIQUE KEY ");
				}
				else
				{
					sql.append(", KEY ");
				}
				String indexName = index.getName();
				if (indexName == null || indexName.length() == 0)
				{
					indexName = _table.getName() + "_uniq" + (i + 1);
				}
				sql.append(SEPARATE_CHAR).append(indexName).append(SEPARATE_CHAR).append(" (");
				for (int j = 0; j < index.getFields().size(); j++)
				{
					sql.append(index.getFields().get(j));
					if ((j + 1) < index.getFields().size())
					{
						sql.append(", ");
					}
				}
				sql.append(")");
			}
		}

		sql.append(")");
		if (_table.getCreationSuffix() != null && _table.getCreationSuffix().length() > 0)
		{
			sql.append(" ");
			sql.append(_table.getCreationSuffix());
		}

		return sql.toString();
	}

	public String getSimpleInsertSql()
	{
		if (_simpleInsertSql == null)
		{
			StringBuilder sql = new StringBuilder("INSERT INTO ");
			sql.append(_table.getName());
			sql.append(" (");

			for (int i = 0; i < _fields.size(); i++)
			{
				final DatabaseField f = _fields.get(i);
				if (f.isInsertable())
				{
					sql.append(f.getName());
					if ((i + 1) < _fields.size())
					{
						sql.append(", ");
					}
				}
			}

			sql.append(") VALUES (");

			for (int i = 0; i < _fields.size(); i++)
			{
				if (_fields.get(i).isInsertable())
				{
					sql.append("?");
					if ((i + 1) < _fields.size())
					{
						sql.append(", ");
					}
				}
			}

			sql.append(")");
			_simpleInsertSql = sql.toString();
		}
		return _simpleInsertSql;
	}

	public String getSimpleSelectSql()
	{
		if (_simpleSelectSql == null)
		{
			StringBuilder sql = new StringBuilder("SELECT ");

			for (int i = 0; i < _fields.size(); i++)
			{
				sql.append(_fields.get(i).getName());
				if ((i + 1) < _fields.size())
				{
					sql.append(", ");
				}
			}
			sql.append(" FROM ")
			   .append(_table.getName())
			   .append(" WHERE ");

			// в этом методе ищем по 1 ключевому полю
			if (_primaryKeyFields.size() != 1)
			{
				throw new IllegalArgumentException("Wrong PK fields size, must be only 1 PK field");
			}
			sql.append(_primaryKeyFields.get(0).getName());
			sql.append("=?");
			_simpleSelectSql = sql.toString();
		}
		return _simpleSelectSql;
	}

	public String getSelectOneSql(String field)
	{
		if (_selectOneSql == null)
		{
			_selectOneSql = new HashMap<>();
		}

		String result = _selectOneSql.get(field);

		if (result == null)
		{
			StringBuilder sql = new StringBuilder("SELECT ");

			boolean found = false;
			for (int i = 0; i < _fields.size(); i++)
			{
				final String fname = _fields.get(i).getName();
				if (field.equals(fname))
				{
					found = true;
				}
				sql.append(fname);
				if ((i + 1) < _fields.size())
				{
					sql.append(", ");
				}
			}
			if (!found)
			{
				throw new RuntimeException("No such field in entity");
			}
			sql.append(" FROM ")
			   .append(_table.getName())
			   .append(" WHERE ");

			sql.append(field);
			sql.append("=?");
			result = sql.toString();
			_selectOneSql.put(field, result);
		}
		return result;
	}

	public String getSimpleDeleteSql()
	{
		if (_simpleDeleteSql == null)
		{
			StringBuilder sql = new StringBuilder("DELETE FROM ");
			sql.append(_table.getName())
			   .append(" WHERE ");
			if (_primaryKeyFields.size() != 1)
			{
				throw new IllegalArgumentException("Wrong PK fields size, must be only 1 PK field");
			}
			sql.append(_primaryKeyFields.get(0).getName());
			sql.append("=?");

			_simpleDeleteSql = sql.toString();
		}
		return _simpleDeleteSql;
	}

	public Class<?> getJavaClass()
	{
		return _javaClass;
	}

	public String getJavaClassName()
	{
		return _javaClassName;
	}

	public DatabaseTable getTable()
	{
		return _table;
	}

	public List<DatabaseField> getFields()
	{
		return _fields;
	}

	public List<DatabaseField> getPrimaryKeyFields()
	{
		return _primaryKeyFields;
	}

	/**
	 * Build and return the default (zero-argument) constructor for the specified class.
	 */
	protected Constructor<?> buildDefaultConstructorFor(Class<?> javaClass) throws NoSuchMethodException
	{
		Constructor<?> result = javaClass.getDeclaredConstructor();
		result.setAccessible(true);
		return result;
	}

	public Object buildNewInstance()
	{
		try
		{
			return _defaultConstructor.newInstance();
		}
		catch (InstantiationException e)
		{
			throw new RuntimeException("InstantiationException", e);
		}
		catch (IllegalAccessException e)
		{
			throw new RuntimeException("IllegalAccessException", e);
		}
		catch (InvocationTargetException e)
		{
			throw new RuntimeException("InvocationTargetException", e);
		}
	}
}
