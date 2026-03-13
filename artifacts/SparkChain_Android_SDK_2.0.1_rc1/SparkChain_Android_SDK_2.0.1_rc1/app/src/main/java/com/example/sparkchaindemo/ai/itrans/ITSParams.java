package com.example.sparkchaindemo.ai.itrans;


import com.example.sparkchaindemo.adapter.ParamInfo;

import java.util.ArrayList;
import java.util.List;

public class ITSParams {
    public static List<ParamInfo> getLanguage(){
        //翻译方向
        List<ParamInfo> typeList = new ArrayList<>();
        typeList.add(new ParamInfo("cn2en","中译英"));
        typeList.add(new ParamInfo("en2cn","英译中"));
        //...其他语种请自行在此按照frome2to的格式添加翻译方向
        return typeList;
    }

    public static List<ParamInfo> getTransType(){
        //翻译类型
        List<ParamInfo> typeList = new ArrayList<>();
        typeList.add(new ParamInfo("ITRANS","讯飞翻译"));
        typeList.add(new ParamInfo("NIUTRANS","小牛翻译"));
        return typeList;
    }

    public static List<String> testTxt(){
        //翻译文本
        List<String> txtList = new ArrayList<>();
        txtList.add("今天的天气很不错，适合出去玩。");
        txtList.add("The weather is good for going out today");
        //...其他语种请自行在此添加翻译源文本，需要和翻译方向一一对应，否则会报数组越界
        return txtList;
    }
}
