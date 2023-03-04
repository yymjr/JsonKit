package org.yymjr;

import com.alibaba.fastjson2.JSONObject;
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
    @Param({"10"})
    public int size;
    public String latin1Data;
    public String utf16Data;

    @Setup(Level.Invocation)
    public void setup() {
        try {
            latin1Data = Okio.buffer(Okio.source(new File("/Users/yymjr/Downloads/gsoc-2018.json"))).readUtf8();
            utf16Data = Okio.buffer(Okio.source(new File("/Users/yymjr/Downloads/fgo.json"))).readUtf8();
        } catch (IOException ignored) {
        }
    }

    @Benchmark
    public void jsonKitLatin1() {
        JsonKit.toJsonObject(latin1Data);
    }

    @Benchmark
    public void fastJsonLatin1() {
        JSONObject.parseObject(latin1Data);
    }

    @Benchmark
    public void jsonKitUtf16() {
        JsonKit.toJsonObject(utf16Data);
    }

    @Benchmark
    public void fastJsonUtf16() {
        JSONObject.parseObject(utf16Data);
    }
}
