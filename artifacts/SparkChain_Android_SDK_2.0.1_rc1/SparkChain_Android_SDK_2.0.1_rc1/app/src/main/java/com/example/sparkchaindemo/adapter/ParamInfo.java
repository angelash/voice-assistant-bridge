package com.example.sparkchaindemo.adapter;

public class ParamInfo {

    public String showName;

    public String value;

    public int value2;
    public ParamInfo(String value, String showName) {
        this.showName = showName;
        this.value = value;
    }


    public ParamInfo(int value, String showName) {
        this.showName = showName;
        this.value2= value;
    }
}
