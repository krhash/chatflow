package cs6650.chatflow.consumer.database;

import cs6650.chatflow.consumer.database.impl.DynamoDBService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating database service instances.
 * Currently supports DynamoDB with extensibility for future implementations.
 */
public class DatabaseFactory {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseFactory.class);

    /**
     * Supported database types
     */
    public enum DatabaseType {
        DYNAMODB,
        POSTGRESQL
    }

    /**
     * Create database service based on type
     *
     * @param type The database type to create
     * @return DatabaseService implementation
     * @throws UnsupportedOperationException if type is not yet implemented
     */
    public static DatabaseService createDatabaseService(DatabaseType type) {
        logger.info("Creating database service: {}", type);

        switch (type) {
            case DYNAMODB:
                return new DynamoDBService();

            case POSTGRESQL:
                // Future implementation for comparison/migration
                throw new UnsupportedOperationException(
                        "PostgreSQL implementation not yet available. Use DYNAMODB.");

            default:
                throw new IllegalArgumentException("Unsupported database type: " + type);
        }
    }

    /**
     * Create database service from configuration.
     * Reads database type from (in priority order):
     * 1. System property: -Ddatabase.type=DYNAMODB
     * 2. Environment variable: DATABASE_TYPE=DYNAMODB
     * 3. Configuration file: database.properties
     * 4. Default: DYNAMODB
     *
     * @return DatabaseService implementation
     * @throws IllegalArgumentException if configured type is invalid
     */
    public static DatabaseService createFromEnvironment() {
        // Priority 1: System property
        String dbType = System.getProperty("database.type");

        // Priority 2: Environment variable
        if (dbType == null) {
            dbType = System.getenv("DATABASE_TYPE");
        }

        // Priority 3: Configuration file (via DatabaseConfig)
        if (dbType == null) {
            dbType = DatabaseConfig.getProperty("database.type", "DYNAMODB");
        }

        logger.info("Database type from configuration: {}", dbType);

        // Parse and validate
        try {
            DatabaseType type = DatabaseType.valueOf(dbType.toUpperCase());
            return createDatabaseService(type);

        } catch (IllegalArgumentException e) {
            logger.error("Invalid database type '{}'. Valid options: DYNAMODB, POSTGRESQL", dbType);
            throw new IllegalArgumentException(
                    String.format("Invalid database type: '%s'. " +
                            "Valid options are: DYNAMODB, POSTGRESQL. " +
                            "Check database.properties or DATABASE_TYPE environment variable.", dbType),
                    e);
        }
    }
}
