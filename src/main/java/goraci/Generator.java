/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package goraci;

import goraci.generated.CINode;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.apache.avro.util.Utf8;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.gora.store.DataStore;
import org.apache.gora.store.DataStoreFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * A Map only job that generates random linked list and stores them using Gora.
 */
public class Generator extends Configured implements Tool {

  private static final Log LOG = LogFactory.getLog(Generator.class);

  private static final int WIDTH = 1000000;
  private static final int WRAP = WIDTH * 25;

  static class GeneratorInputFormat extends InputFormat<LongWritable,NullWritable> {

    static class GeneratorInputSplit extends InputSplit implements Writable {

      @Override
      public long getLength() throws IOException, InterruptedException {
        return 1;
      }

      @Override
      public String[] getLocations() throws IOException, InterruptedException {
        return new String[0];
      }

      @Override
      public void readFields(DataInput arg0) throws IOException {
        // TODO Auto-generated method stub

      }

      @Override
      public void write(DataOutput arg0) throws IOException {
        // TODO Auto-generated method stub

      }
    }

    static class GeneratorRecordReader extends RecordReader<LongWritable,NullWritable> {
      private long count;
      private long numNodes;
      private Random rand;

      @Override
      public void close() throws IOException {
      }

      @Override
      public LongWritable getCurrentKey() throws IOException, InterruptedException {
        return new LongWritable(Math.abs(rand.nextLong()));
      }

      @Override
      public NullWritable getCurrentValue() throws IOException, InterruptedException {
        return NullWritable.get();
      }

      @Override
      public float getProgress() throws IOException, InterruptedException {
        return count / (float)numNodes;
      }

      @Override
      public void initialize(InputSplit arg0, TaskAttemptContext context) throws IOException, InterruptedException {
        numNodes = context.getConfiguration().getLong("goraci.generator.nodes", 1000000);
        rand = new Random();
      }

      @Override
      public boolean nextKeyValue() throws IOException, InterruptedException {
        return count++ < numNodes;
      }

    }

    @Override
    public RecordReader<LongWritable,NullWritable> createRecordReader(InputSplit split, TaskAttemptContext context) throws IOException, InterruptedException {
      GeneratorRecordReader rr = new GeneratorRecordReader();
      rr.initialize(split, context);
      return rr;
    }

    @Override
    public List<InputSplit> getSplits(JobContext job) throws IOException, InterruptedException {
      int numMappers = job.getConfiguration().getInt("goraci.generator.mappers", 1);

      ArrayList<InputSplit> splits = new ArrayList<InputSplit>(numMappers);

      for (int i = 0; i < numMappers; i++) {
        splits.add(new GeneratorInputSplit());
      }

      return splits;
    }

  }

  /** Ensure output files from prev-job go to map inputs for current job */
  static class OneFilePerMapperSFIF<K, V> extends SequenceFileInputFormat<K, V> {
    @Override
    protected boolean isSplitable(JobContext context, Path filename) {
      return false;
    }
  }

  /**
   * Some ASCII art time:
   * [ . . . ] represents one batch of random longs of length WIDTH
   *
   *                _________________________
   *               |                  ______ |
   *               |                 |      ||
   *             __+_________________+_____ ||
   *             v v                 v     |||
   * first   = [ . . . . . . . . . . . ]   |||
   *             ^ ^ ^ ^ ^ ^ ^ ^ ^ ^ ^     |||
   *             | | | | | | | | | | |     |||
   * prev    = [ . . . . . . . . . . . ]   |||
   *             ^ ^ ^ ^ ^ ^ ^ ^ ^ ^ ^     |||
   *             | | | | | | | | | | |     |||
   * current = [ . . . . . . . . . . . ]   |||
   *                                       |||
   * ...                                   |||
   *                                       |||
   * last    = [ . . . . . . . . . . . ]   |||
   *             | | | | | | | | | | |-----|||
   *             |                 |--------||
   *             |___________________________|
   */
  static class GeneratorMapper extends Mapper<LongWritable,NullWritable,NullWritable,NullWritable> {

    Random rand = new Random();

    long[] first = null;
    long[] prev = null;
    long[] current = new long[WIDTH];
    DataStore<Long,CINode> store;
    Utf8 id;
    long count = 0;
    int i;

    protected void setup(Context context) throws IOException, InterruptedException {
      id = new Utf8(UUID.randomUUID().toString());
      store = DataStoreFactory.getDataStore(Long.class, CINode.class, new Configuration());
    };

    protected void cleanup(Context context) throws IOException ,InterruptedException {
      store.close();
    };

    @Override
    protected void map(LongWritable key, NullWritable value, Context output) throws IOException {
      current[i++] = Math.abs(key.get());

      if (i == current.length) {
        persist(output, store, count, prev, current, id);
        i = 0;

        if (first == null)
          first = current;
        prev = current;
        current = new long[WIDTH];

        count += current.length;
        output.setStatus("Count " + count);

        if (count % WRAP == 0) {
          // this block of code turns the 1 million linked list of length 25 into one giant circular linked list of 25 million

          circularLeftShift(first);

          updatePrev(store, first, prev);

          first = null;
          prev = null;
        }
      }
    }

    private static void circularLeftShift(long[] first) {
      long ez = first[0];
      for (int i = 0; i < first.length - 1; i++)
        first[i] = first[i + 1];
      first[first.length - 1] = ez;
    }

    private static void persist(Context output, DataStore<Long,CINode> store, long count, long[] prev, long[] current, Utf8 id) throws IOException {
      for (int i = 0; i < current.length; i++) {
        CINode node = store.newPersistent();
        node.setCount(count + i);
        if (prev != null)
          node.setPrev(prev[i]);
        else
          node.setPrev(-1);
        node.setClient(id);

        store.put(current[i], node);
        if (i % 1000 == 0) {
          // Tickle progress every so often else maprunner will think us hung
          output.progress();
        }
      }

      store.flush();
    }

    private static void updatePrev(DataStore<Long,CINode> store, long[] first, long[] current) throws IOException {
      for (int i = 0; i < current.length; i++) {
        CINode node = store.newPersistent();
        node.setPrev(current[i]);
        store.put(first[i], node);
      }

      store.flush();
    }
  }


  @Override
  public int run(String[] args) throws Exception {
    if (args.length < 3) {
      System.out.println("Usage : " + Generator.class.getSimpleName() + " <num mappers> <num nodes per map> <tmp output dir>");
      return 0;
    }

    int numMappers = Integer.parseInt(args[0]);
    long numNodes = Long.parseLong(args[1]);
    Path tmpOutput = new Path(args[2]);
    return run(numMappers, numNodes, tmpOutput);
  }

  protected void createSchema() throws IOException {
    DataStore<Long,CINode> store = DataStoreFactory.getDataStore(Long.class, CINode.class, new Configuration());
    store.createSchema();
  }

  public int runRandomInputGenerator(int numMappers, long numNodes, Path tmpOutput) throws Exception {
    LOG.info("Running RandomInputGenerator with numMappers=" + numMappers +", numNodes=" + numNodes);
    Job job = new Job(getConf());

    job.setJobName("Random Input Generator");
    job.setNumReduceTasks(0);
    job.setJarByClass(getClass());

    job.setInputFormatClass(GeneratorInputFormat.class);
    job.setOutputKeyClass(LongWritable.class);
    job.setOutputValueClass(NullWritable.class);

    job.getConfiguration().setInt("goraci.generator.mappers", numMappers);
    job.getConfiguration().setLong("goraci.generator.nodes", numNodes);

    job.setMapperClass(Mapper.class); //identity mapper

    FileOutputFormat.setOutputPath(job, tmpOutput);
    job.setOutputFormatClass(SequenceFileOutputFormat.class);

    boolean success = job.waitForCompletion(true);

    return success ? 0 : 1;
  }

  public int runGenerator(int numMappers, long numNodes, Path tmpOutput) throws Exception {
    LOG.info("Running Generator with numMappers=" + numMappers +", numNodes=" + numNodes);
    createSchema();

    Job job = new Job(getConf());

    job.setJobName("Link Generator");
    job.setNumReduceTasks(0);
    job.setJarByClass(getClass());

    FileInputFormat.setInputPaths(job, tmpOutput);
    job.setInputFormatClass(OneFilePerMapperSFIF.class);
    job.setOutputKeyClass(NullWritable.class);
    job.setOutputValueClass(NullWritable.class);

    job.getConfiguration().setInt("goraci.generator.mappers", numMappers);
    job.getConfiguration().setLong("goraci.generator.nodes", numNodes);

    job.setMapperClass(GeneratorMapper.class);

    job.setOutputFormatClass(NullOutputFormat.class);

    job.getConfiguration().setBoolean("mapred.map.tasks.speculative.execution", false);

    boolean success = job.waitForCompletion(true);

    return success ? 0 : 1;
  }

  public int run(int numMappers, long numNodes, Path tmpOutput) throws Exception {
    int ret = runRandomInputGenerator(numMappers, numNodes, tmpOutput);
    if (ret > 0) {
      return ret;
    }

    return runGenerator(numMappers, numNodes, tmpOutput);
  }

  public static void main(String[] args) throws Exception {
    int ret = ToolRunner.run(new Generator(), args);
    System.exit(ret);
  }
}
