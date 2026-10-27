package com.actify.invoice.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.ForeignKey;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps docs/sql/invoice-process-bootstrap-schema.sql (gate G2) in step with the JPA mappings, without a database.
 *
 * Hibernate's ddl-auto=validate checks that every mapped table and column exists with a compatible type; it does
 * NOT check varchar lengths, NOT NULL or named constraints. This test checks all of them against the annotations,
 * so a field added to an entity without a matching line in the script fails here - not at the first hosted start.
 * It proves the script MATCHES the mappings; that the script RUNS on PostgreSQL is proven only by applying it to a
 * scratch database (RENDER_DEPLOYMENT.md, section 5.3).
 */
class SchemaBootstrapScriptTest {

    private static final String SCRIPT = "docs/sql/invoice-process-bootstrap-schema.sql";

    private static String sql;                                   // the script without "--" comments
    private static final Map<String, Map<String, SqlColumn>> scriptTables = new LinkedHashMap<>();
    private static final Map<String, Map<String, SqlColumn>> entityTables = new LinkedHashMap<>();
    private static final List<Class<?>> entities = new ArrayList<>();

    @BeforeAll
    static void load() throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get(SCRIPT)), StandardCharsets.UTF_8).replace("\r\n", "\n");
        sql = raw.replaceAll("--[^\\n]*", "");
        parseScript();

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.actify.invoice")) {
            entities.add(Class.forName(definition.getBeanClassName()));
        }
        for (Class<?> entity : entities) {
            entityTables.put(entity.getAnnotation(Table.class).name(), columnsOf(entity));
        }
    }

    @Test
    void createsExactlyTheMappedTables() {
        assertThat(entities).hasSize(12);
        assertThat(new TreeSet<>(scriptTables.keySet())).isEqualTo(new TreeSet<>(entityTables.keySet()));
    }

    @Test
    void everyMappedColumnHasTheSameTypeAndNullabilityAndNothingIsExtra() {
        for (Map.Entry<String, Map<String, SqlColumn>> table : entityTables.entrySet()) {
            Map<String, SqlColumn> script = scriptTables.get(table.getKey());
            assertThat(new TreeSet<>(script.keySet())).as("columns of %s", table.getKey())
                    .isEqualTo(new TreeSet<>(table.getValue().keySet()));
            for (SqlColumn expected : table.getValue().values()) {
                SqlColumn actual = script.get(expected.name);
                assertThat(actual.type).as("type of %s.%s", table.getKey(), expected.name).isEqualTo(expected.type);
                assertThat(actual.notNull).as("NOT NULL of %s.%s", table.getKey(), expected.name)
                        .isEqualTo(expected.notNull);
            }
        }
    }

    @Test
    void everyNamedIndexUniqueConstraintAndForeignKeyExistsWithTheMappedColumns() {
        for (Class<?> entity : entities) {
            Table table = entity.getAnnotation(Table.class);
            for (Index index : table.indexes()) {
                assertThat(indexColumns(table.name(), index.name())).as("index %s", index.name())
                        .isEqualTo(normalizeList(index.columnList()));
            }
            for (UniqueConstraint unique : table.uniqueConstraints()) {
                assertThat(constraintColumns(unique.name(), "UNIQUE")).as("unique %s", unique.name())
                        .isEqualTo(String.join(",", unique.columnNames()));
            }
            for (Field field : entity.getDeclaredFields()) {
                JoinColumn join = field.getAnnotation(JoinColumn.class);
                if (join != null) {
                    ForeignKey fk = join.foreignKey();
                    assertThat(fk.name().isEmpty() || constraintColumns(fk.name(), "FOREIGN KEY").equals(join.name()))
                            .as("foreign key %s on %s", fk.name(), join.name()).isTrue();
                    assertThat(sql).as("a foreign key for %s.%s", table.name(), join.name())
                            .containsPattern("FOREIGN KEY \\(" + join.name() + "\\) REFERENCES public\\.");
                }
            }
        }
    }

    @Test
    void isOneGuardedTransactionThatNeverDeletesOrChangesData() {
        String upper = sql.toUpperCase(Locale.ROOT);
        for (String forbidden : Arrays.asList("DROP ", "TRUNCATE", "DELETE ", "UPDATE ", "INSERT ", "ALTER TABLE",
                "IF NOT EXISTS", "GRANT ", "OWNER TO")) {
            assertThat(upper).as("statement %s", forbidden.trim()).doesNotContain(forbidden);
        }
        assertThat(countOf(upper, "BEGIN;")).isEqualTo(1);
        assertThat(countOf(upper, "COMMIT;")).isEqualTo(1);
        // The guard runs before the first CREATE and refuses a non-empty schema and the local databases.
        int guard = upper.indexOf("DO $GUARD$");
        int guardEnd = upper.indexOf("$GUARD$;");
        assertThat(guard).isBetween(upper.indexOf("BEGIN;"), upper.indexOf("CREATE TABLE"));
        String guardBody = sql.substring(guard, guardEnd);
        assertThat(guardBody).contains("Actify-Internship-Invoice-Process_db", "Actify-Internship-Invoice-Process_test_db",
                "invoice_process_local_demo_db", "invoice_process_migration_scratch_db");
        // Counted from pg_catalog: information_schema hides objects this role has no privilege on.
        assertThat(guardBody).contains("pg_catalog.pg_class", "n.nspname = 'public'", "'r', 'p', 'v', 'm', 'f', 'S'")
                .doesNotContain("information_schema");
    }

    // ------------------------------------------------------------------------------------------- expected columns

    private static Map<String, SqlColumn> columnsOf(Class<?> entity) {
        Map<String, SqlColumn> columns = new LinkedHashMap<>();
        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)
                    || field.isAnnotationPresent(OneToMany.class)) {
                continue;
            }
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            Column column = field.getAnnotation(Column.class);
            if (join != null) {
                boolean optional = field.isAnnotationPresent(ManyToOne.class)
                        ? field.getAnnotation(ManyToOne.class).optional()
                        : !field.isAnnotationPresent(OneToOne.class) || field.getAnnotation(OneToOne.class).optional();
                merge(columns, new SqlColumn(join.name(), idTypeOf(field.getType()), !join.nullable() || !optional));
            } else if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                throw new AssertionError(entity.getSimpleName() + "." + field.getName()
                        + " is a relationship without @JoinColumn - name its column explicitly first");
            } else {
                // Without @Column, Spring Boot's naming strategy turns the field name into snake_case (e.g. "id").
                String name = column != null && !column.name().isEmpty() ? column.name() : snakeCase(field.getName());
                boolean notNull = (column != null && !column.nullable()) || field.isAnnotationPresent(Id.class)
                        || field.getType().isPrimitive();
                boolean readOnlyMirror = column != null && !column.insertable() && !column.updatable();
                merge(columns, new SqlColumn(name, sqlType(field, column), notNull, readOnlyMirror));
            }
        }
        return columns;
    }

    /**
     * A read-only mirror of a join column (insertable=false, updatable=false) describes the same database column; the
     * writable mapping defines its exact type, the mirror only has to agree on the base type (e.g. varchar).
     */
    private static void merge(Map<String, SqlColumn> columns, SqlColumn column) {
        SqlColumn existing = columns.get(column.name);
        if (existing == null) {
            columns.put(column.name, column);
            return;
        }
        assertThat(baseType(existing.type)).as("both mappings of %s", column.name).isEqualTo(baseType(column.type));
        String type = existing.readOnlyMirror ? column.type : existing.type;
        if (!existing.readOnlyMirror && !column.readOnlyMirror) {
            assertThat(existing.type).as("both writable mappings of %s", column.name).isEqualTo(column.type);
        }
        columns.put(column.name, new SqlColumn(column.name, type, existing.notNull || column.notNull,
                existing.readOnlyMirror && column.readOnlyMirror));
    }

    private static String baseType(String type) {
        return type.replaceAll("\\(.*\\)", "");
    }

    private static String snakeCase(String fieldName) {
        return fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String idTypeOf(Class<?> target) {
        for (Field field : target.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return sqlType(field, field.getAnnotation(Column.class));
            }
        }
        throw new AssertionError(target.getSimpleName() + " has no @Id field");
    }

    private static String sqlType(Field field, Column column) {
        Class<?> type = field.getType();
        if (column != null && !column.columnDefinition().isEmpty()) {
            return column.columnDefinition().toLowerCase(Locale.ROOT);
        }
        if (type == String.class || type.isEnum()) {
            return "varchar(" + (column == null ? 255 : column.length()) + ")";
        }
        if (type == Long.class || type == long.class) {
            return "bigint";
        }
        if (type == Integer.class || type == int.class) {
            return "integer";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (type == BigDecimal.class) {
            return "numeric(" + column.precision() + "," + column.scale() + ")";
        }
        if (type == LocalDate.class) {
            return "date";
        }
        if (type == LocalDateTime.class) {
            return "timestamp without time zone";
        }
        throw new AssertionError("no SQL type rule for " + field + " - extend this test deliberately");
    }

    // ------------------------------------------------------------------------------------------- script parsing

    private static void parseScript() {
        Matcher table = Pattern.compile("CREATE TABLE public\\.(\\w+) \\((.*?)\\n\\);", Pattern.DOTALL).matcher(sql);
        while (table.find()) {
            Map<String, SqlColumn> columns = new LinkedHashMap<>();
            for (String line : table.group(2).split("\\n")) {
                String text = line.trim().replaceAll(",$", "").trim();
                if (text.isEmpty() || text.startsWith("CONSTRAINT")) {
                    continue;
                }
                int space = text.indexOf(' ');
                String name = text.substring(0, space);
                String rest = text.substring(space).trim();
                boolean notNull = rest.contains("NOT NULL") || rest.contains("PRIMARY KEY");
                String type = rest.replace("GENERATED BY DEFAULT AS IDENTITY", "").replace("PRIMARY KEY", "")
                        .replace("NOT NULL", "").trim().replaceAll("\\s+", " ");
                columns.put(name, new SqlColumn(name, type, notNull));
            }
            scriptTables.put(table.group(1), columns);
        }
    }

    private static String indexColumns(String table, String index) {
        Matcher m = Pattern.compile("CREATE INDEX " + index + " ON public\\." + table + " \\(([^)]*)\\);").matcher(sql);
        assertThat(m.find()).as("CREATE INDEX %s ON %s", index, table).isTrue();
        return normalizeList(m.group(1));
    }

    private static String constraintColumns(String name, String kind) {
        Matcher m = Pattern.compile("CONSTRAINT " + name + " " + kind + " \\(([^)]*)\\)").matcher(sql);
        assertThat(m.find()).as("CONSTRAINT %s %s", name, kind).isTrue();
        return normalizeList(m.group(1));
    }

    private static String normalizeList(String list) {
        return list.replaceAll("\\s+", "");
    }

    private static int countOf(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }

    private static final class SqlColumn {
        final String name;
        final String type;
        final boolean notNull;
        final boolean readOnlyMirror;

        SqlColumn(String name, String type, boolean notNull) {
            this(name, type, notNull, false);
        }

        SqlColumn(String name, String type, boolean notNull, boolean readOnlyMirror) {
            this.name = name;
            this.type = type;
            this.notNull = notNull;
            this.readOnlyMirror = readOnlyMirror;
        }
    }
}
