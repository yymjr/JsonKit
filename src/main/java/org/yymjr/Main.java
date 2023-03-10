package org.yymjr;

import okio.BufferedSource;
import okio.Okio;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.yymjr.util.JsonKit;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class Main {
    private static final boolean isLocalDebug = false;
    private static final boolean isUtf8File = true;

    public static void main(String[] args) throws IOException {
        if (isLocalDebug) {
            String file = isUtf8File ? "fgo.json" : "gsoc-2018.json";
            BufferedSource jsonKitLatin1Source = Okio.buffer(Okio.source(new File("/Users/yymjr/Downloads/", file)));
            Map<String, Object> jsonObject = JsonKit.toJsonObject(jsonKitLatin1Source, isUtf8File ? JsonKit.UTF8 : JsonKit.LATIN1);
            System.out.println(jsonObject);
            jsonKitLatin1Source.close();
        } else {
            benchmark();
        }
    }

    private static void benchmark() {
        try {
            Options options = new OptionsBuilder()
                    .forks(1)
                    .jvmArgs("-Xmx512m", "-XX:+HeapDumpOnOutOfMemoryError")
                    .build();
            new Runner(options).run();
        } catch (Exception ignored) {
        }
    }

}