# DB & Custom directories backups (Work In Progress)
Performs database backup and custom directories backups.

## Pattern
`Strategy pattern` is widely used to facilitate easier extension of database adapters.

Each strategy implementation must have: `@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)`

Strategies are based on:

```java
public enum DBType {
 MYSQL(DBTypeStrategyConstants.MYSQL_STRATEGY);

 private final String strategyName;

 DBType(String strategyName) {
  this.strategyName = strategyName;
 }

 @Override
 public String toString() {
  return this.strategyName;
 }

 public interface DBTypeStrategyConstants {
  String MYSQL_STRATEGY = "mysqlStrategy";
 }
}
```

```java
public interface BackupStrategy {
 DBType dbType();

 void backup(DBConfig databaseConfig, String destination, List<String> otherDirsToBackup);
}
```

Creates new `BackupStrategy` implementation from the application context on demand

```java
@Slf4j
public final class Helpers {
 public static BackupStrategy createNewBackupStrategyInstance(ApplicationContext applicationContext, DBType dbType) {
  Map<DBType, Function<ApplicationContext, BackupStrategy>> backupInstances = new HashMap<>();
  Arrays.stream(DBType.values())
          .forEach(dbTypeEnum -> backupInstances
                  .put(dbTypeEnum,
                          (appContext) -> appContext.getBean(dbTypeEnum.toString(), BackupStrategy.class)));
  return backupInstances.get(dbType).apply(applicationContext);
 }
}
```

Wrapper object for new `BackupStrategy`. Calls `createInstance` method to create a new instance.

```java
@RequiredArgsConstructor
public class BackupStrategyFactory {
 private final DBType dbType;
 private final ApplicationContext applicationContext;

 public BackupStrategy createInstance(){
  return Helpers.createNewBackupStrategyInstance(applicationContext,dbType);
 }
}
```

Bean to create all instances of `BackupStrategyFactory` derived from `DBType`

```java
@Configuration
@RequiredArgsConstructor
class StrategyConfig {
    private final ApplicationContext applicationContext;

    @Bean
    public Map<DBType, BackupStrategyFactory> backupDataByDBType() {
        Map<DBType, BackupStrategyFactory> backupStrategyFactoryMap = new EnumMap<>(DBType.class);
        Arrays.stream(DBType.values())
                .forEach(dbType -> backupStrategyFactoryMap.put(dbType, new BackupStrategyFactory(dbType, applicationContext)));
        return backupStrategyFactoryMap;
    }
}
```

Loads the relevant strategy depending on `dbConfig.dbType()`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupContext {
  private final Map<DBType, BackupStrategyFactory> backupDataByDBType;
 
  public void backup(DBConfig dbConfig, String destination, List<String> otherDirsToBackup) {
    BackupStrategyFactory strategyFactory = backupDataByDBType.getOrDefault(dbConfig.dbType(), null);
    if (Objects.isNull(strategyFactory)) {
     log.error("DBType: {} does not have an existing implementation", dbConfig.dbType());
     return;
    }
    //get a new instance of the strategy since each request can have different db configs
    BackupStrategy backupStrategy = strategyFactory.createInstance();
    backupStrategy.backup(dbConfig, destination, otherDirsToBackup);
  }
 
}
```
`MysqlBackupStrategy` one of the implementations of the `BackupStrategy`

```java
@Slf4j
@Component(DBType.DBTypeStrategyConstants.MYSQL_STRATEGY)
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class MysqlBackupStrategy implements BackupStrategy {
    
  @Override
  public DBType dbType() {
   return DBType.MYSQL;
  }
  
  @Override
  public void backup(DBConfig databaseConfig, String destination, List<String> otherDirsToBackup) {
      
  }
  
}
```

`DataSourceFactory` Uses Functional Interfaces with custom `TriFunction`. Enables extendability of `JDBC URL` generation.
```java
public final class DataSourceFactory {
    private static final Map<DBType, TriFunction<String, Integer, String, String>> jdbcUrlMap = Map.of(
            DBType.MYSQL, (host, port, dbName) -> "jdbc:mysql://" + host + ":" + port + "/" + dbName
    );

    private DataSourceFactory() {
    }

    public static DataSource createDataSource(DBType dbType, DBConfig dbConfig) {
        var jdbcUrlFunc = jdbcUrlMap.getOrDefault(dbType, null);
        if (Objects.isNull(jdbcUrlFunc)) {
            throw new RuntimeException("jdbcUrlMap does not have " + dbType);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrlFunc.apply(Helpers.parseHost(dbConfig.host()), dbConfig.port(), dbConfig.dbName()));
        config.setUsername(dbConfig.username());
        config.setPassword(dbConfig.password());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        return new HikariDataSource(config);
    }

}
```


## Production ready DBs
1. Mysql

## Upcoming support
1. PostgresQL
2. MongoDB

## Native Image Build with <a href="https://www.graalvm.org" target="_blank">GraalVM</a>
1. To generate image for your current host `mvn package -Pnative -DskipTests`
2. If on Mac or window, you can generate native image for ubuntu using `docker` as described below
   1. Ubuntu 20.04
      1. `docker build -f ./docker/Dockerfile20-04 -t graalvm-native-ubuntu-20-04 .`
      2. `docker run -v $(pwd):/app -w /app graalvm-native-ubuntu-20-04 bash -c "mvn package -Pnative -DskipTests"`
   2. Ubuntu 22.04
      1. `docker build -f ./docker/Dockerfile22-04 -t graalvm-native-ubuntu-22-04 .`
      2. `docker run -v $(pwd):/app -w /app graalvm-native-ubuntu-22-04 bash -c "mvn package -Pnative -DskipTests"`
3. **Copy** the native image to production host e.g `cp target/AppBackup ~/App/AppBackup`
4. Setup **systemd**
```bash
sudo touch /etc/systemd/system/AppBackup.service
sudo nano /etc/systemd/system/AppBackup.service
```

Paste this script in above AppBackup.service
```bash
[Unit]
Description=App Backup
After=network.target

[Service]
ExecStart=/home/johndoe/App/AppBackup
WorkingDirectory=/home/johndoe/App
EnvironmentFile=/home/johndoe/App/.env
Restart=always
User=johndoe

[Install]
WantedBy=multi-user.target
```
Only include `EnvironmentFile=/home/johndoe/App/.env` if applicable otherwise ignore it.

Then allow the AppBackup app in **systemd**
```bash
sudo systemctl daemon-reload
sudo systemctl start AppBackup
sudo systemctl enable AppBackup