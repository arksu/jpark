package org.jpark;

import javax.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.jpark.DatabasePlatform.APOSTROPHE_CHAR;
import static org.jpark.DatabasePlatform.SEPARATE_CHAR;

/**
 * описание поля в бд
 */
public class DatabaseField
{
	/**
	 * Variables used for generating DDL
	 **/
//	protected int _scale;
//	protected int _length;
//	protected int _precision;
//	protected boolean _isUnique;

	private boolean _isInsertable;
	private boolean _isNullable;
	private boolean _isUpdatable;
	private boolean _isPrimaryKey;
	private String _columnDefinition;

	private Class<?> _type;

	private final boolean _isEnum;
	private final Map<String, Object> _enumConstants;

	/**
	 * Column name of the field.
	 */
	private String _name;

	/**
	 * PERF: Cache fully qualified table.field-name.
	 */
	private String _qualifiedName;

	/**
	 * Fields table (encapsulates name + creator).
	 */
	private DatabaseTable _table;

	/**
	 * обновляем ключевое поле после инсерта (только 1 ключевое поле auto increment)
	 */
	private boolean _isUpdateInsertId;

	private Field _field;

	public DatabaseField(Field field, Column annotation, ColumnExtended extendedAnnotation, DatabaseTable table)
	{
		_field = field;
		_type = _field.getType();
		_isEnum = _type.isEnum();
		if (_isEnum)
		{
			_enumConstants = new HashMap<>();
			for (Object e : _type.getEnumConstants())
			{
				_enumConstants.put(((Enum<?>) e).name(), e);
			}
		}
		else
		{
			_enumConstants = null;
		}
		_name = annotation.name();
		if (_name.length() == 0)
		{
			_name = _field.getName().toUpperCase();
		}
		_table = table;
		_qualifiedName = table.getName() + "." + _name;
		_isNullable = annotation.nullable();
		_isUpdatable = annotation.updatable();
		_isInsertable = annotation.insertable();
		_columnDefinition = annotation.columnDefinition();
		_isPrimaryKey = false;
		if (extendedAnnotation != null)
		{
			_isUpdateInsertId = extendedAnnotation.updateInsertId();
		}
		else
		{
			_isUpdateInsertId = false;
		}
	}

	public DatabaseField(Field field, DatabaseTable table)
	{
		_field = field;
		_type = _field.getType();
		_isEnum = _type.isEnum();
		if (_isEnum)
		{
			_enumConstants = new HashMap<>();
			for (Object e : _type.getEnumConstants())
			{
				_enumConstants.put(((Enum<?>) e).name(), e);
			}
		}
		else
		{
			_enumConstants = null;
		}
		_name = _field.getName().toUpperCase();
		_qualifiedName = table.getName() + "." + _name;
		_isNullable = false;
		_isUpdatable = true;
		_isInsertable = true;
		_table = table;
		_isPrimaryKey = false;
	}

	public DatabaseTable getTable()
	{
		return _table;
	}

	public String getName()
	{
		return _name;
	}

	public void setName(String name)
	{
		_name = name;
	}

	public Field getField()
	{
		return _field;
	}

	public Class<?> getType()
	{
		return _type;
	}

	public boolean isEnum()
	{
		return _isEnum;
	}

	public Map<String, Object> getEnumConstants()
	{
		return _enumConstants;
	}

	public String getQualifiedName()
	{
		return _qualifiedName;
	}

	public boolean isNullable()
	{
		return _isNullable;
	}

	public String getColumnDefinition()
	{
		return _columnDefinition;
	}

	public boolean isPrimaryKey()
	{
		return _isPrimaryKey;
	}

	public void setPrimaryKey(boolean primaryKey)
	{
		_isPrimaryKey = primaryKey;
		if (primaryKey)
		{
			_isUpdatable = false;
		}
	}

	public boolean isUpdatable()
	{
		return _isUpdatable;
	}

	public boolean isInsertable()
	{
		return _isInsertable;
	}

	public boolean isUpdateInsertId()
	{
		return _isUpdateInsertId;
	}

	public String getCreateSql() throws SQLException
	{
		StringBuilder s = new StringBuilder(SEPARATE_CHAR + _name + SEPARATE_CHAR);
		s.append(" ");
		// если у нас явно определен тип колонки тупо прокинем его
		if (_columnDefinition != null && _columnDefinition.length() != 0)
		{
			s.append(_columnDefinition);
		}
		else
		{
			if (_type.isEnum())
			{
				s.append("ENUM(");
				try
				{
					Method method = _type.getDeclaredMethod("values");
					Object[] obj = (Object[]) method.invoke(null);

					for (int i = 0; i < obj.length; i++)
					{
						s.append(APOSTROPHE_CHAR);
						s.append(obj[i].toString());
						s.append(APOSTROPHE_CHAR);

						if ((i + 1) < obj.length)
						{
							s.append(", ");
						}
					}
				}
				catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e)
				{
					throw new SQLException("Wrong Enum field <" + _type.getSimpleName() + ">");
				}
				s.append(")");
			}
			else
			{
				// иначе надо определить тип колонки по типу поля в сущности
				// TODO
				String stype = ConversionManager.getFieldTypeDefinition(_type);
				s.append(stype);
			}
			if (_isNullable)
			{
				s.append(" NULL");
			}
			else
			{
				s.append(" NOT NULL");
			}
		}
		return s.toString();
	}

	@Override
	public String toString()
	{
		return _table.getName() + "." + _name + " - " + _type;
	}
}
