package com.novadb.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Row implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<String> values;

    public Row() {
        values = new ArrayList<>();
    }

    public void addValue(String value) {
        values.add(value);
    }

    public List<String> getValues() {
        return values;
    }
}