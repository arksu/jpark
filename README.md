This is micro ORM framework for MySQL databases (MariaDB and other)

## Features:
- simple indexes
- id autoincrement
- most useful types of fields
- CRUD operations
- custom definitions for columns
- added extended column annotations (updateInsertId - fill inserted id in this id field)
- added extended table annotations (truncate, deploy and other after start)

## Some examples:

```java
@Entity
@Table(name = "users", indexes = {
		@Index(name = "login_uniq", columnList = "login", unique = true)
})
@TableExtended(creationSuffix = "engine=MyISAM COMMENT='users'")
public class User extends DbObject
{
	@Id
	@Column(name = "id", columnDefinition = "INT(11) NOT NULL AUTO_INCREMENT")
	@ColumnExtended(updateInsertId = true)
	private int _id;

	@Column(name = "login", columnDefinition = "VARCHAR(64) NOT NULL", nullable = false)
	private String _login;

	@Column(name = "password", columnDefinition = "VARCHAR(64) NOT NULL", nullable = false)
	private String _password;
}
```

```java
@Entity
@Table(name = "characters")
public class Character extends DbObject
{
	@Id
	@Column(name = "id", columnDefinition = "INT(11) NOT NULL AUTO_INCREMENT")
	@SerializedName("id")
	private int _id;


	@Column(name = "userId", columnDefinition = "INT(11) NOT NULL", nullable = false)
	private transient int _userId;

	@Column(name = "createTime", columnDefinition = "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP")
	private transient Timestamp _createTime;
}
```