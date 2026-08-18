package com.wm.streamhub.model;

public class Category {
    public String id;
    public String name;
    public int count;

    public Category(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
