package mdbms.models.data;

import java.util.List;

public record Row(RowId rowId, List<Object> values){}