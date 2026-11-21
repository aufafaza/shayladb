package mdbms.models.schema;

/** Whether incoming foreign keys prevent a table from being dropped. */
public enum DropBehavior {
    RESTRICT,
    CASCADE
}
