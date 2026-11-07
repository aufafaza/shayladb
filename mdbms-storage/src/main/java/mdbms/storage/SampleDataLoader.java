package mdbms.storage;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.access.DataWrite;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.ForeignKeyAction;
import mdbms.models.schema.ForeignKeyConstraint;
import mdbms.models.schema.TableSchema;

public final class SampleDataLoader {
    private final IDataAccess storage;
    private final ISchemaCatalog catalog;
    public SampleDataLoader(IDataAccess storage, ISchemaCatalog catalog){
        this.storage = Objects.requireNonNull(storage, "storage");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }
    public void loadIfEmpty(){
        if(!catalog.listTable().isEmpty()) return;
        createTables();
        insertRows();
    }
    private void createTables(){
        catalog.updateTableSchema(new TableSchema("students", List.of(
            new ColumnDefinition("nim", DataType.INTEGER, 0, false),
            new ColumnDefinition("name", DataType.CHAR, 100, false),
            new ColumnDefinition("gender", DataType.CHAR, 1, false),
            new ColumnDefinition("major", DataType.CHAR, 100, false),
            new ColumnDefinition("gpa", DataType.FLOAT, 0, false)),
        List.of("nim"), List.of()));
        catalog.updateTableSchema(new TableSchema("takes", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 0, false),
            new ColumnDefinition("nim", DataType.INTEGER, 0, false),
            new ColumnDefinition("code", DataType.VARCHAR, 30, false),
            new ColumnDefinition("grade", DataType.INTEGER, 0, false),
            new ColumnDefinition("semester", DataType.INTEGER, 0, false)),
        List.of("id"), List.of(
            new ForeignKeyConstraint("nim", "students", "nim", ForeignKeyAction.RESTRICT, ForeignKeyAction.RESTRICT),
            new ForeignKeyConstraint("code", "courses", "code", ForeignKeyAction.RESTRICT, ForeignKeyAction.RESTRICT)
        )));
        catalog.updateTableSchema(new TableSchema("courses", List.of(
            new ColumnDefinition("code", DataType.VARCHAR, 30, false),
            new ColumnDefinition("title", DataType.VARCHAR, 100, false),
            new ColumnDefinition("credits", DataType.INTEGER, 0, false)),
        List.of("code"), List.of()));
    }
    private void insertRows(){
        for(int id = 1; id <= 50; id++){
            storage.writeBlock(new DataWrite("students", Map.of(
                "nim", 100000 + id,
                "name", String.format("Student %03d", id),
                "gender", (id % 2 == 0) ? "M" : "F",
                "major", id % 2 == 0 ? "Computer Science" : "Information Systems",
                "gpa", 2.0f + (id % 21) / 10.0f), List.of()));
        }
        for(int id = 1; id <= 50; id++){
            storage.writeBlock(new DataWrite("courses", Map.of(
                "code", String.format("IF%04d", id),
                "title", String.format("SBD %02d", id),
                "credits", (id % 3) + 2), List.of()));
        }
        for(int id = 1; id <= 50; id++){
            storage.writeBlock(new DataWrite("takes", Map.of(
                "id", id,
                "nim", 100000 + id,
                "code", String.format("IF%04d", id),
                "grade", 60 + id % 41,
                "semester", 20261 + id % 2), List.of()));
        }
    }
}
