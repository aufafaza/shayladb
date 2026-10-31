package mdbms.models;

public enum QueryNodeType{ 
    PROJECT, 
    SELECT, 
    JOIN, 
    CARTESIAN, 
    TABLE_SCAN, 
    SORT, 
    GROUP_BY, 
    LIMIT, 
    UPDATE,
    INSERT,
    DELETE
}