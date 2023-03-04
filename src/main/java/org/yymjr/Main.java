package org.yymjr;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class Main {

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(JsonBenchmark.class.getSimpleName())
                .exclude("fast")
                .forks(1)
                .jvmArgs("-Xmx1024m", "-XX:+HeapDumpOnOutOfMemoryError")
                .build();
        new Runner(options).run();
    }

}