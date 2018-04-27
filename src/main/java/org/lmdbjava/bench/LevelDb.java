/*-
 * #%L
 * LmdbJava Benchmarks
 * %%
 * Copyright (C) 2016 - 2018 The LmdbJava Open Source Project
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package org.lmdbjava.bench;

import java.io.IOException;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import java.util.Map.Entry;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.openhft.hashing.LongHashFunction.xx_r39;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import static org.fusesource.leveldbjni.JniDBFactory.factory;
import static org.fusesource.leveldbjni.JniDBFactory.popMemoryPool;
import static org.fusesource.leveldbjni.JniDBFactory.pushMemoryPool;
import static org.iq80.leveldb.CompressionType.NONE;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import static org.openjdk.jmh.annotations.Level.Invocation;
import static org.openjdk.jmh.annotations.Level.Trial;
import org.openjdk.jmh.annotations.Measurement;
import static org.openjdk.jmh.annotations.Mode.SampleTime;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import static org.openjdk.jmh.annotations.Scope.Benchmark;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

@OutputTimeUnit(MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@BenchmarkMode(SampleTime)
@SuppressWarnings({"checkstyle:javadoctype", "checkstyle:designforextension"})
public class LevelDb {

  @Benchmark
  public void readCrc(final Reader r, final Blackhole bh) throws IOException {
    r.crc.reset();
    try (DBIterator iterator = r.db.iterator()) {
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        final Entry<byte[], byte[]> peeked = iterator.peekNext();
        r.crc.update(peeked.getKey());
        r.crc.update(peeked.getValue());
      }
    }
    bh.consume(r.crc.getValue());
  }

  @Benchmark
  public void readKey(final Reader r, final Blackhole bh) throws IOException {
    for (final int key : r.keys) {
      if (r.intKey) {
        r.wkb.putInt(0, key);
      } else {
        r.wkb.putStringWithoutLengthUtf8(0, r.padKey(key));
      }
      bh.consume(r.db.get(r.wkb.byteArray()));
    }
  }

  @Benchmark
  public void readRev(final Reader r, final Blackhole bh) throws IOException {
    try (DBIterator iterator = r.db.iterator()) {
      for (iterator.seekToLast(); iterator.hasPrev(); iterator.prev()) {
        final Entry<byte[], byte[]> peeked = iterator.peekPrev();
        bh.consume(peeked.getValue());
      }
    }
  }

  @Benchmark
  public void readSeq(final Reader r, final Blackhole bh) throws IOException {
    try (DBIterator iterator = r.db.iterator()) {
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        final Entry<byte[], byte[]> peeked = iterator.peekNext();
        bh.consume(peeked.getValue());
      }
    }
  }

  @Benchmark
  public void readXxh64(final Reader r, final Blackhole bh) throws IOException {
    long result = 0;
    try (DBIterator iterator = r.db.iterator()) {
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        final Entry<byte[], byte[]> peeked = iterator.peekNext();
        result += xx_r39().hashBytes(peeked.getKey());
        result += xx_r39().hashBytes(peeked.getValue());
      }
    }
    bh.consume(result);
  }

  @Benchmark
  public void write(final Writer w, final Blackhole bh) throws IOException {
    w.write(w.batchSize);
  }

  @State(value = Benchmark)
  @SuppressWarnings("checkstyle:visibilitymodifier")
  public static class CommonLevelDb extends Common {

    DB db;

    /**
     * Writable key buffer. Backed by a plain byte[] for LevelDB API ease.
     */
    MutableDirectBuffer wkb;

    /**
     * Writable value buffer. Backed by a plain byte[] for LevelDB API ease.
     */
    MutableDirectBuffer wvb;

    @Override
    public void setup(final BenchmarkParams b) throws IOException {
      super.setup(b);
      wkb = new UnsafeBuffer(new byte[keySize]);
      wvb = new UnsafeBuffer(new byte[valSize]);
      pushMemoryPool(1_024 * 512);
      final Options options = new Options();
      options.createIfMissing(true);
      options.compressionType(NONE);
      db = factory.open(tmp, options);
    }

    @Override
    public void teardown() throws IOException {
      reportSpaceBeforeClose();
      db.close();
      popMemoryPool();
      super.teardown();
    }

    void write(final int batchSize) throws IOException {
      final int rndByteMax = RND_MB.length - valSize;
      int rndByteOffset = 0;
      WriteBatch batch = db.createWriteBatch();
      for (int i = 0; i < keys.length; i++) {
        final int key = keys[i];
        if (intKey) {
          wkb.putInt(0, key, LITTLE_ENDIAN);
        } else {
          wkb.putStringWithoutLengthUtf8(0, padKey(key));
        }
        if (valRandom) {
          wvb.putBytes(0, RND_MB, rndByteOffset, valSize);
          rndByteOffset += valSize;
          if (rndByteOffset >= rndByteMax) {
            rndByteOffset = 0;
          }
        } else {
          wvb.putInt(0, key);
        }
        batch.put(wkb.byteArray(), wvb.byteArray());
        if (i % batchSize == 0) {
          db.write(batch);
          batch.close();
          batch = db.createWriteBatch();
        }
      }
      db.write(batch); // possible partial batch
      batch.close();
    }
  }

  @State(Benchmark)
  public static class Reader extends CommonLevelDb {

    @Setup(Trial)
    @Override
    public void setup(final BenchmarkParams b) throws IOException {
      super.setup(b);
      super.write(num);
    }

    @TearDown(Trial)
    @Override
    public void teardown() throws IOException {
      super.teardown();
    }
  }

  @State(Benchmark)
  @SuppressWarnings("checkstyle:visibilitymodifier")
  public static class Writer extends CommonLevelDb {

    @Param("1000000")
    int batchSize;

    @Setup(Invocation)
    @Override
    public void setup(final BenchmarkParams b) throws IOException {
      super.setup(b);
    }

    @TearDown(Invocation)
    @Override
    public void teardown() throws IOException {
      super.teardown();
    }
  }

}
