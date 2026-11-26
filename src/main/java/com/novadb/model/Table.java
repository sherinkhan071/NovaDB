package com.novadb.model;

import java.util.ArrayList;
import java.util.List;

public class Table {

    private final List<Row> rows = new ArrayList<>();

    public void addRow(Row row) {
        rows.add(row);
    }

    public List<Row> getRows() {
        return rows;
    }

    /** The row whose first value equals the id, or null. */
    public Row find(String id) {

        for (Row row : rows) {
            if (row.getValues().get(0).equals(id)) {
                return row;
            }
        }

        return null;
    }
}
