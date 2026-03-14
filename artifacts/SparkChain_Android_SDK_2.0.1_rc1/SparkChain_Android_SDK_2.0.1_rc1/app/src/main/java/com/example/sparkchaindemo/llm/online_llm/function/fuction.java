package com.example.sparkchaindemo.llm.online_llm.function;

public class fuction {
    public static String fuction = " [\n" +
            "            {\n" +
            "                \"name\": \"天气查询\",\n" +
            "                \"description\": \"天气插件可以提供天气相关信息。你可以提供指定的地点信息、指定的时间点或者时间段信息，来精准检索到天气信息。\",\n" +
            "                \"parameters\": {\n" +
            "                    \"type\": \"object\",\n" +
            "                    \"properties\": {\n" +
            "                        \"location\": {\n" +
            "                            \"type\": \"string\",\n" +
            "                            \"description\": \"地点，比如北京。\"\n" +
            "                        },\n" +
            "                        \"date\": {\n" +
            "                            \"type\": \"string\",\n" +
            "                            \"description\": \"日期。\"\n" +
            "                        }\n" +
            "                    },\n" +
            "                    \"required\": [\n" +
            "                        \"location\"\n" +
            "                    ]\n" +
            "                }\n" +
            "            },\n" +
            "            {\n" +
            "                \"name\": \"税率查询\",\n" +
            "                \"description\": \"税率查询可以查询某个地方的个人所得税率情况。你可以提供指定的地点信息、指定的时间点，精准检索到所得税率。\",\n" +
            "                \"parameters\": {\n" +
            "                    \"type\": \"object\",\n" +
            "                    \"properties\": {\n" +
            "                        \"location\": {\n" +
            "                            \"type\": \"string\",\n" +
            "                            \"description\": \"地点，比如北京。\"\n" +
            "                        },\n" +
            "                        \"date\": {\n" +
            "                            \"type\": \"string\",\n" +
            "                            \"description\": \"日期。\"\n" +
            "                        }\n" +
            "                    },\n" +
            "                    \"required\": [\n" +
            "                        \"location\"\n" +
            "                    ]\n" +
            "                }\n" +
            "            }\n" +
            "        ]";
}
