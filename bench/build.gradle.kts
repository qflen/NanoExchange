plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    jmh(project(":engine"))
    jmh(project(":network"))
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// The JMH plugin's annotation-processor classpath doesn't include the processor jar
// automatically; wire it explicitly so generated benchmark source compiles.
jmh {
    // Default knobs here match what PERFORMANCE.md documents. Individual benchmarks can
    // override via @Warmup / @Measurement annotations.
    // Reduced vs. the "gold standard" (5 × 2s warmup / 10 × 2s meas / 3 forks): we pick
    // numbers that fit in ~5 minutes end-to-end on a laptop. The PERFORMANCE.md methodology
    // section documents both configurations; tighter numbers for publishable results require
    // the larger run, which is wired via the ``jmhLong`` task if ever needed.
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("2s")
    fork.set(1)
    failOnError.set(true)
    jvmArgs.set(listOf("-Xms1g", "-Xmx1g"))
    resultFormat.set("JSON")
    humanOutputFile.set(project.file("${project.layout.buildDirectory.get()}/reports/jmh/human.txt"))
    resultsFile.set(project.file("${project.layout.buildDirectory.get()}/reports/jmh/results.json"))
}

// -Werror from the root build cascades in — JMH-generated sources trip on some warnings;
// turn that off for the jmh source set specifically.
tasks.named<JavaCompile>("compileJmhJava") {
    options.compilerArgs.removeAll { it == "-Werror" }
}
