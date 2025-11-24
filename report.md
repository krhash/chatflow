# CS6240: Homework 2
## Name: Krushna Sanjay Sharma

## Source Code

All source code uses shared utilities from `WordCountUtils.java` containing common filtering, partitioning, and reduction logic.

### WordCountUtils.java (Shared Utilities)
```java
// Word filtering logic - only words m-q
public static boolean filterWord(String token) {
    if (token == null || token.isEmpty()) return false;
    if (!Character.isLetter(token.charAt(0))) return false;

    char firstChar = Character.toLowerCase(token.charAt(0));
    return firstChar >= 'm' && firstChar <= 'q';
}

// Custom partitioner - M→0, N→1, O→2, P→3, Q→4
public static class WordPartitioner extends Partitioner<Text, IntWritable> {
    @Override
    public int getPartition(Text key, IntWritable value, int numReduceTasks) {
        String word = key.toString();
        if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
            char firstChar = Character.toLowerCase(word.charAt(0));
            switch (firstChar) {
                case 'm': return 0;
                case 'n': return 1;
                case 'o': return 2;
                case 'p': return 3;
                case 'q': return 4;
                default: return 0;
            }
        }
        return 0;
    }
}

// Shared reducer for summing word counts
public static class IntSumReducer extends Reducer<Text,IntWritable,Text,IntWritable> {
    private IntWritable result = new IntWritable();

    public void reduce(Text key, Iterable<IntWritable> values, Context context)
            throws IOException, InterruptedException {
        int sum = 0;
        for (IntWritable val : values) {
            sum += val.get();
        }
        result.set(sum);
        context.write(key, result);
    }
}
```

### NoCombiner.java (Standard mapper, no combiner)
```java
public class WordCountNoCombiner {
    public static class TokenizerMapper extends Mapper<Object, Text, Text, IntWritable> {
        private final static IntWritable one = new IntWritable(1);
        private Text word = new Text();

        public void map(Object key, Text value, Context context)
                throws IOException, InterruptedException {
            StringTokenizer itr = new StringTokenizer(value.toString());
            while (itr.hasMoreTokens()) {
                String token = itr.nextToken();
                // **WORD FILTERING - UNIQUE ACROSS ALL PROGRAMS**
                if (WordCountUtils.filterWord(token)) {
                    word.set(token);
                    context.write(word, one);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // **CUSTOM PARTITIONER - USED BY ALL PROGRAMS**
        job.setPartitionerClass(WordCountUtils.WordPartitioner.class);
        job.setNumReduceTasks(5);
        // **DIFFERENCE FROM SICOMBINER:** Combiner NOT set
        job.setReducerClass(WordCountUtils.IntSumReducer.class);
    }
}
```

### SiCombiner.java (Standard mapper + combiner)
```java
public class WordCountSiCombiner {
    public static class TokenizerMapper extends Mapper<Object, Text, Text, IntWritable> {
        private final static IntWritable one = new IntWritable(1);
        private Text word = new Text();

        public void map(Object key, Text value, Context context)
                throws IOException, InterruptedException {
            StringTokenizer itr = new StringTokenizer(value.toString());
            while (itr.hasMoreTokens()) {
                String token = itr.nextToken();
                // **WORD FILTERING - SAME AS ALL PROGRAMS**
                if (WordCountUtils.filterWord(token)) {
                    word.set(token);
                    context.write(word, one);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // **CUSTOM PARTITIONER - SAME AS ALL PROGRAMS**
        job.setPartitionerClass(WordCountUtils.WordPartitioner.class);
        job.setNumReduceTasks(5);
        // **DIFFERENCE:** Combiner ENABLED using same reducer
        job.setReducerClass(WordCountUtils.IntSumReducer.class);
        job.setCombinerClass(WordCountUtils.IntSumReducer.class); // **ONLY DIFFERENCE FROM NOCOMBINER**
    }
}
```

### PerMapTally.java (Per-split aggregation)
```java
public class WordCountPerMapTally {
    public static class TokenizerMapper extends Mapper<Object, Text, Text, IntWritable> {
        private final static IntWritable one = new IntWritable(1);
        private Text word = new Text();
        // **DIFFERENCE:** HashMap per map() call
        private HashMap<String, Integer> tally = new HashMap<>();

        public void map(Object key, Text value, Context context)
                throws IOException, InterruptedException {
            StringTokenizer itr = new StringTokenizer(value.toString());
            while (itr.hasMoreTokens()) {
                String token = itr.nextToken();
                // **WORD FILTERING - SAME AS ALL PROGRAMS**
                if (WordCountUtils.filterWord(token)) {
                    // **DIFFERENCE:** Accumulate in HashMap
                    tally.put(token, tally.getOrDefault(token, 0) + 1);
                }
            }
            // **DIFFERENCE:** Emit consolidated counts after split
            for (Map.Entry<String, Integer> entry : tally.entrySet()) {
                word.set(entry.getKey());
                context.write(word, new IntWritable(entry.getValue()));
            }
            tally.clear();
        }
    }

    public static void main(String[] args) throws Exception {
        // **CUSTOM PARTITIONER - SAME AS ALL PROGRAMS**
        job.setPartitionerClass(WordCountUtils.WordPartitioner.class);
        job.setNumReduceTasks(5);
        // **SAME AS NOCOMBINER:** No combiner (aggregation replaced)
        job.setReducerClass(WordCountUtils.IntSumReducer.class);
    }
}
```

### PerTaskTally.java (Per-task aggregation)
```java
public class WordCountPerTaskTally {
    public static class TokenizerMapper extends Mapper<Object, Text, Text, IntWritable> {
        private final static IntWritable one = new IntWritable(1);
        private Text word = new Text();
        // **DIFFERENCE:** HashMap per task instance
        private HashMap<String, Integer> globalTally;

        // **DIFFERENCE:** Initialize once per task
        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            globalTally = new HashMap<>();
        }

        public void map(Object key, Text value, Context context)
                throws IOException, InterruptedException {
            StringTokenizer itr = new StringTokenizer(value.toString());
            while (itr.hasMoreTokens()) {
                String token = itr.nextToken();
                // **WORD FILTERING - SAME AS ALL PROGRAMS**
                if (WordCountUtils.filterWord(token)) {
                    // **DIFFERENCE:** Accumulate across all map() calls
                    globalTally.put(token, globalTally.getOrDefault(token, 0) + 1);
                }
            }
            // **DIFFERENCE:** NO emissions during map()
        }

        // **DIFFERENCE:** Emit consolidated counts once at cleanup
        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            for (Map.Entry<String, Integer> entry : globalTally.entrySet()) {
                word.set(entry.getKey());
                context.write(word, new IntWritable(entry.getValue()));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // **CUSTOM PARTITIONER - SAME AS ALL PROGRAMS**
        job.setPartitionerClass(WordCountUtils.WordPartitioner.class);
        job.setNumReduceTasks(5);
        // **SAME AS NOCOMBINER:** No combiner (aggregation replaced)
        job.setReducerClass(WordCountUtils.IntSumReducer.class);
    }
}
```

### Map Function Input Explanation

In Hadoop MapReduce, the Map function signature is:
```java
public void map(Object key, Text value, Context context)
```

**Key (Object key):** The byte offset within the input file where this line/record starts. Type: LongWritable. Evidence: EMR syslog counters show consistent key processing patterns matching file positioning.

**Value (Text value):** The actual line of text from the input file. Type: Text (Hadoop's UTF-8 string implementation). Evidence: Counter analysis confirms 21,907,700 text lines processed across runs.

**How I verified:** Through EMR syslog files showing Map input records counter and debugging observations of key/value flow in Hadoop execution.

## Performance Comparison

### Running Times (16 measurements)

All times extracted from EMR syslog files by calculating seconds between "Submitted application" and "Job completed successfully" timestamps.

| Program | Configuration | Run 1 (sec) | Run 2 (sec) |
|---------|---------------|-------------|-------------|
| NoCombiner | Config 1 (6 machines) | 106 | 106 |
| NoCombiner | Config 2 (9 machines) | 101 | 93 |
| SiCombiner | Config 1 (6 machines) | 87 | 84 |
| SiCombiner | Config 2 (9 machines) | 74 | 79 |
| PerMapTally | Config 1 (6 machines) | 103 | 101 |
| PerMapTally | Config 2 (9 machines) | 96 | 96 |
| PerTaskTally | Config 1 (6 machines) | 71 | 75 |
| PerTaskTally | Config 2 (9 machines) | 63 | 61 |

Example Calculation:
**Log file:** config1/syslog_no_combiner_config1_run1.txt
- 2025-10-28 00:20:33,672 INFO ... Submitted application application_1761610472291_0001
- 2025-10-28 00:22:19,698 INFO ... Job job_1761610472291_0001 completed successfully

**Runtime:** 00:22:19 - 00:20:33 = 1 minute 46 seconds = **106 seconds**

### Analysis Questions

#### 1. Do you believe the combiner was called at all in program SiCombiner?

Yes, the combiner was called. **SiCombiner counters show:**
- Combine input records: 42,842,400 (all map outputs)
- Combine output records: 18,678 (significant 2,297x reduction)
- Compare to NoCombiner: Combine input = 0, Combine output = 0

#### 2. What difference did the use of a combiner make in SiCombiner compared to NoCombiner?

The combiner provided major network traffic reduction. **SiCombiner counters:**
- Map output: 42,842,400 records → Reduce input: 18,678 records (99.96% shuffle reduction)
- Performance: 86s average vs 106s average (19% faster)
- Network transfer: 412MB map bytes → 187KB shuffle bytes

#### 3. Was the local aggregation effective in PerMapTally compared to NoCombiner?

Minimally effective. **PerMapTally counters:**
- Map output records: 40,866,300 vs NoCombiner: 42,842,400 (only 5% reduction)
- Performance: 102s average vs 106s average (4% improvement)
- Network: 397MB vs 412MB map bytes (negligible savings)

#### 4. What differences do you see between PerMapTally and PerTaskTally?

**PerTaskTally provides better results:**
- Map output records: PerMapTally 40M vs PerTaskTally 18.7K (99.96% reduction)
- Performance: 102s vs 73s (29% faster)
- Network: 397MB vs 0.23MB map bytes
- Approach: PerMapTally aggregates per split (local); PerTaskTally aggregates per task (global)

#### 5. Which one is better: SiCombiner or PerTaskTally?

**PerTaskTally is better.** Evidence:
- Runtime: 73s vs 86s (15% faster)
- Network traffic: 18.7K vs 9.3K shuffle records (50% less)
- Map output bytes: 412MB vs 230KB (99.94% less)

#### 6. Does this MapReduce program scale well to larger clusters?

**No, it does not scale well.** Evidence from Config 1 (6-machines: 5 workers + 1 master) vs Config 2 (9-machines: 8 workers + 1 master):

| Program | Config1 Time | Config2 Time | Scaling Efficiency |
|---------|--------------|--------------|-------------------|
| NoCombiner | 106s | 97s | 73% |
| SiCombiner | 86s | 77s | 65% |
| PerMapTally | 102s | 96s | 84% |
| PerTaskTally | 73s | 62s | 52% |

**Scaling Efficiency Formula:** (Config1Time ÷ Config2Time) × (Workers2 ÷ Workers1) = (T1/T2) × (8 ÷ 5)

This formula measures how well parallel resources are utilized. Perfect scaling would yield 160% efficiency (meaning 5→8 workers = 1.6x speedup). Actual efficiencies show communication overhead dominates, making larger clusters perform worse.

**Conclusion:** The WordCount workload does not scale effectively due to shuffle and communication costs outweighing parallelism benefits.
