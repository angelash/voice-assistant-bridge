package com.example.sparkchaindemo.ai.asr;

import com.example.sparkchaindemo.adapter.ParamInfo;

import java.util.ArrayList;
import java.util.List;

public class asrParams {
    public static List<ParamInfo> getLanguage(){
        //识别语种
        List<ParamInfo> typeList = new ArrayList<>();
        typeList.add(new ParamInfo("zh_cn","中文"));
        typeList.add(new ParamInfo("en_us","英文"));
        //...其他语种请自行在此添加
        return typeList;
    }
}
