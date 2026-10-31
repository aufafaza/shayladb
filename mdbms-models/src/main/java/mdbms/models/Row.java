package mdbms.models;

import java.util.List;

public record Row(RowId rowId, List<Object> values){}