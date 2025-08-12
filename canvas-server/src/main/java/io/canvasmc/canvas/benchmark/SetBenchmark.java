package io.canvasmc.canvas.benchmark;

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import io.canvasmc.canvas.server.chunk.DynamicChunkPosLongSet;
import org.agrona.collections.LongHashSet;
import org.jetbrains.annotations.NotNull;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class SetBenchmark {

    private static final int WARMUP_ITERATIONS = 3;
    private static final int TEST_ITERATIONS = 500;
    private static final int ELEMENT_COUNT = 1_000_000;

    public static void main(String[] args) {
        // Avg Add:      59.60 ms
        // Avg Contains: 15.57 ms
        // Avg Remove:   10.22 ms
        benchmark("HashSet", HashSet::new);
        // Avg Add:      30.06 ms
        // Avg Contains: 8.52 ms
        // Avg Remove:   8.00 ms
        benchmark("DynamicLongPosSet", DynamicChunkPosLongSet::new);
        // Avg Add:      31.77 ms
        // Avg Contains: 11.28 ms
        // Avg Remove:   26.80 ms
        benchmark("ObjectHashSet", LongHashSet::new);
        // Avg Add:      81.37 ms
        // Avg Contains: 16.52 ms
        // Avg Remove:   29.52 ms
        benchmark("ConcurrentUtil", LongKeyOnlySet::new);
    }

    private static void benchmark(String name, Supplier<Set<Long>> setSupplier) {
        System.out.println("Benchmarking " + name);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            runBenchmark(setSupplier.get(), false);
        }

        long totalAdd = 0, totalContains = 0, totalRemove = 0;

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            BenchmarkResult result = runBenchmark(setSupplier.get(), true);
            totalAdd += result.addTime;
            totalContains += result.containsTime;
            totalRemove += result.removeTime;
        }

        System.out.printf("Avg Add:      %.2f ms\n", totalAdd / (TEST_ITERATIONS * 1_000_000.0));
        System.out.printf("Avg Contains: %.2f ms\n", totalContains / (TEST_ITERATIONS * 1_000_000.0));
        System.out.printf("Avg Remove:   %.2f ms\n", totalRemove / (TEST_ITERATIONS * 1_000_000.0));
        System.out.println();
    }

    private static @NotNull BenchmarkResult runBenchmark(Set<Long> set, boolean print) {
        List<Long> values = new ArrayList<>(ELEMENT_COUNT);
        for (int i = 0; i < ELEMENT_COUNT; i++) {
            values.add(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
        }

        long startAdd = System.nanoTime();
        for (Long val : values) set.add(val);
        long endAdd = System.nanoTime();

        long startContains = System.nanoTime();
        for (Long val : values) set.contains(val);
        long endContains = System.nanoTime();

        long startRemove = System.nanoTime();
        for (Long val : values) set.remove(val);
        long endRemove = System.nanoTime();

        long addTime = endAdd - startAdd;
        long containsTime = endContains - startContains;
        long removeTime = endRemove - startRemove;

        if (print) {
            System.out.printf("Add: %d ms | Contains: %d ms | Remove: %d ms\n",
                    addTime / 1_000_000, containsTime / 1_000_000, removeTime / 1_000_000);
        }

        return new BenchmarkResult(addTime, containsTime, removeTime);
    }

    private record BenchmarkResult(long addTime, long containsTime, long removeTime) {}

    public static class LongKeyOnlySet extends AbstractSet<Long> {

        private static final Object DUMMY_VALUE = new Object();
        private final ConcurrentLong2ReferenceChainedHashTable<Object> backing;

        public LongKeyOnlySet() {
            backing = new ConcurrentLong2ReferenceChainedHashTable<>();
        }

        @Override
        public boolean add(Long key) {
            return backing.put(key, DUMMY_VALUE) != null;
        }

        @Override
        public boolean remove(Object key) {
            if (!(key instanceof Long)) return false;
            return backing.remove(((Long) key)) != null;
        }

        @Override
        public boolean contains(Object key) {
            if (!(key instanceof Long lon)) return false;
            return backing.containsKey(lon);
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public Iterator<Long> iterator() {
            throw new UnsupportedOperationException("Not supported for benchmarking");
        }
    }
}
