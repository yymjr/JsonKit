package org.yymjr;

import com.alibaba.fastjson2.JSONObject;
import okio.BufferedSource;
import okio.Okio;
import org.openjdk.jmh.annotations.*;
import org.yymjr.util.JsonKit;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class JsonBenchmark {
    @Param({"10", "1000", "100000"})
    public int size;
    String latin1File = "/home/ec2-user/gsoc-2018.json";
    String utf16File = "/home/ec2-user/fgo.json";

    @Setup(Level.Invocation)
    public void setUp() {
        String os = System.getProperty("os.name");
        if (os.contains("Mac")) {
            latin1File = "/Users/yymjr/Downloads/gsoc-2018.json";
            utf16File = "/Users/yymjr/Downloads/fgo.json";
        } else {
            latin1File = "/home/ec2-user/gsoc-2018.json";
            utf16File = "/home/ec2-user/fgo.json";
        }
    }

    @Benchmark
    public void jsonKitLatin1() throws IOException {
        BufferedSource jsonKitLatin1Source = Okio.buffer(Okio.source(new File(latin1File)));
        JsonKit.toJsonObject(jsonKitLatin1Source, JsonKit.LATIN1);
        jsonKitLatin1Source.close();
    }

    @Benchmark
    public void fastJsonLatin1() {
        try {
            BufferedSource fastJsonLatin1Source = Okio.buffer(Okio.source(new File(latin1File)));
            JSONObject.parseObject(fastJsonLatin1Source.readUtf8());
            fastJsonLatin1Source.close();
        } catch (IOException ignored) {
        }
    }

    @Benchmark
    public void jsonKitUtf16() throws IOException {
        BufferedSource jsonKitUtf16Source = Okio.buffer(Okio.source(new File(utf16File)));
        JsonKit.toJsonObject(jsonKitUtf16Source, JsonKit.UTF8);
        jsonKitUtf16Source.close();
    }

    @Benchmark
    public void fastJsonUtf16() {
        try {
            BufferedSource fastJsonUtf16Source = Okio.buffer(Okio.source(new File(utf16File)));
            JSONObject.parseObject(fastJsonUtf16Source.readUtf8());
            fastJsonUtf16Source.close();
        } catch (IOException ignored) {
        }
    }
}
