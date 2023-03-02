package org.yymjr;

import org.yymjr.util.JsonKit;

import java.util.*;

public class Main {

    public static void main(String[] args) {
        final String data = "{\"name\": \"John Doe\",\"age\": 43,\"address\": {\"street\": \"10 Downing Street\",\"city\": \"London\"},\"phones\": [\"+44 1234567\",\"+44 2345678\"]}";
        Map<String, Object> jsonObject = JsonKit.toJsonObject(data);
        System.out.println(jsonObject);
    }

}