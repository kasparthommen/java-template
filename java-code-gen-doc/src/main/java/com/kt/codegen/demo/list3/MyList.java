package com.kt.codegen.demo.list3;

import com.kt.codegen.Instantiate;
import com.kt.codegen.Replace;

@Instantiate(value = String.class,
             replace = @Replace(from = "(T[]) new Object[size]", to = "new String[size]"))
class MyList<T> {
    private T[] array;

    MyList(int size) {
        this.array = (T[]) new Object[size];
    }

    T get(int index) {
        return array[index];
    }
}
