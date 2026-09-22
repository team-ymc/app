package com.ymc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class FlywayMigrationTest {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Test
    void 빈_DB에_전체_스키마를_생성한다() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)) {
            postgres.start();

            Flyway flyway = flyway(postgres);

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(count(postgres,
                    "select count(*) from flyway_schema_history where version = '0' and type = 'BASELINE'"))
                    .isZero();
            assertThat(count(postgres,
                    "select count(*) from flyway_schema_history where version = '1' and success"))
                    .isEqualTo(1);
            assertThat(count(postgres,
                    "select count(*) from flyway_schema_history where version = '2' and success"))
                    .isEqualTo(1);
            assertThat(count(postgres,
                    "select count(*) from information_schema.tables "
                            + "where table_schema = 'public' and table_name in "
                            + "('paper', 'users', 'usage_record', 'document_prerequisite_highlight')"))
                    .isEqualTo(4);
        }
    }

    @Test
    void 기존_DB를_0으로_baseline하고_V1으로_보정한다() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)) {
            postgres.start();
            createLegacyPaper(postgres);
            createLegacyUsageRecord(postgres);

            Flyway flyway = flyway(postgres);

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(count(postgres,
                    "select count(*) from flyway_schema_history where version = '0' and type = 'BASELINE'"))
                    .isEqualTo(1);
            assertThat(count(postgres,
                    "select count(*) from flyway_schema_history where version = '1' and success"))
                    .isEqualTo(1);
            assertThat(count(postgres,
                    "select count(*) from flyway_schema_history where version = '2' and success"))
                    .isEqualTo(1);
            assertThat(count(postgres,
                    "select count(*) from information_schema.columns "
                            + "where table_schema = 'public' and table_name = 'paper' "
                            + "and column_name in ('title_override', 'last_accessed_at', 'expired_at', 'deleted_at')"))
                    .isEqualTo(4);
            assertThat(count(postgres,
                    "select count(*) from pg_constraint where conname = 'uk_paper_owner_filename'"))
                    .isZero();
            assertThat(count(postgres,
                    "select count(*) from usage_record where source_type = 'PAPER'"))
                    .isEqualTo(1);
            assertThat(count(postgres,
                    "select count(*) from information_schema.columns "
                            + "where table_schema = 'public' and table_name = 'usage_record' "
                            + "and column_name = 'source_type' and is_nullable = 'NO'"))
                    .isEqualTo(1);

            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .baselineDescription("pre-flyway-schema")
                .locations("classpath:db/migration")
                .load();
    }

    private static void createLegacyPaper(PostgreSQLContainer<?> postgres) throws Exception {
        execute(postgres, """
                create table paper (
                    id uuid not null,
                    owner_id uuid not null,
                    filename varchar(255) not null,
                    file_key varchar(255) not null,
                    document_id uuid,
                    created_at timestamp(6) with time zone not null,
                    updated_at timestamp(6) with time zone not null,
                    primary key (id),
                    constraint uk_paper_owner_filename unique (owner_id, filename)
                )
                """);
    }

    private static void createLegacyUsageRecord(PostgreSQLContainer<?> postgres) throws Exception {
        execute(postgres, """
                create table usage_record (
                    id uuid not null,
                    bucket_id uuid not null,
                    usage_type varchar(32) not null,
                    source_id uuid not null,
                    status varchar(16) not null,
                    estimated_cost_usd numeric(14, 8),
                    created_at timestamp(6) with time zone not null,
                    updated_at timestamp(6) with time zone not null,
                    primary key (id),
                    constraint uk_usage_record_type_source unique (usage_type, source_id)
                )
                """);
        execute(postgres, """
                insert into usage_record (
                    id, bucket_id, usage_type, source_id, status, created_at, updated_at
                ) values (
                    '10000000-0000-0000-0000-000000000001',
                    '20000000-0000-0000-0000-000000000001',
                    'PAPER_REGISTRATION',
                    '30000000-0000-0000-0000-000000000001',
                    'RESERVED',
                    now(),
                    now()
                )
                """);
    }

    private static void execute(PostgreSQLContainer<?> postgres, String sql) throws Exception {
        try (Connection connection = connection(postgres);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(PostgreSQLContainer<?> postgres, String sql) throws Exception {
        try (Connection connection = connection(postgres);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Connection connection(PostgreSQLContainer<?> postgres) throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
