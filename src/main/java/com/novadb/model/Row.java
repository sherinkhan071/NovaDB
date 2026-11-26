package com.novadb.model;

import java.util.ArrayList;
import java.util.List;

public class Row {

    private final List<String> values = new ArrayList<>();

    public void addValue(String value) {
        values.add(value);
    }

    public List<String> getValues() {
        return values;
    }
}
