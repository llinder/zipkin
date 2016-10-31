/**
 * Copyright 2015-2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.storage.cassandra3;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.Span;
import zipkin.internal.Pair;
import zipkin.storage.cassandra3.Schema.AnnotationUDT;
import zipkin.storage.cassandra3.Schema.BinaryAnnotationUDT;
import zipkin.storage.guava.GuavaSpanConsumer;

import static com.google.common.util.concurrent.Futures.transform;
import static zipkin.internal.ApplyTimestampAndDuration.guessTimestamp;
import static zipkin.internal.Util.checkNotNull;
import static zipkin.storage.cassandra3.CassandraUtil.bindWithName;
import static zipkin.storage.cassandra3.CassandraUtil.durationIndexBucket;

final class CassandraSpanConsumer implements GuavaSpanConsumer {
  private static final Logger LOG = LoggerFactory.getLogger(CassandraSpanConsumer.class);
  private static final Function<Object, Void> TO_VOID = Functions.<Void>constant(null);
  private static final long WRITTEN_NAMES_TTL
      = Long.getLong("zipkin.store.cassandra3.internal.writtenNamesTtl", 60 * 60 * 1000);

  private final Session session;
  private final PreparedStatement insertSpan;
  private final PreparedStatement insertTraceServiceSpanName;
  private final PreparedStatement insertServiceSpanName;
  private final Schema.Metadata metadata;
  private final DeduplicatingExecutor deduplicatingExecutor;

  CassandraSpanConsumer(Session session) {
    this.session = session;
    this.metadata = Schema.readMetadata(session);

    insertSpan = session.prepare(
        QueryBuilder
            .insertInto(Schema.TABLE_TRACES)
            .value("trace_id", QueryBuilder.bindMarker("trace_id"))
            .value("ts_uuid", QueryBuilder.bindMarker("ts_uuid"))
            .value("id", QueryBuilder.bindMarker("id"))
            .value("ts", QueryBuilder.bindMarker("ts"))
            .value("span_name", QueryBuilder.bindMarker("span_name"))
            .value("parent_id", QueryBuilder.bindMarker("parent_id"))
            .value("duration", QueryBuilder.bindMarker("duration"))
            .value("annotations", QueryBuilder.bindMarker("annotations"))
            .value("binary_annotations", QueryBuilder.bindMarker("binary_annotations"))
            .value("all_annotations", QueryBuilder.bindMarker("all_annotations")));

    insertTraceServiceSpanName = session.prepare(
        QueryBuilder
            .insertInto(Schema.TABLE_TRACE_BY_SERVICE_SPAN)
            .value("service_name", QueryBuilder.bindMarker("service_name"))
            .value("span_name", QueryBuilder.bindMarker("span_name"))
            .value("bucket", QueryBuilder.bindMarker("bucket"))
            .value("ts", QueryBuilder.bindMarker("ts"))
            .value("trace_id", QueryBuilder.bindMarker("trace_id"))
            .value("duration", QueryBuilder.bindMarker("duration")));

    insertServiceSpanName = session.prepare(
        QueryBuilder
            .insertInto(Schema.TABLE_SERVICE_SPANS)
            .value("service_name", QueryBuilder.bindMarker("service_name"))
            .value("span_name", QueryBuilder.bindMarker("span_name")));

    deduplicatingExecutor = new DeduplicatingExecutor(session, WRITTEN_NAMES_TTL);
  }

  /**
   * This fans out into many requests, last count was 2 * spans.size. If any of these fail, the
   * returned future will fail. Most callers drop or log the result.
   */
  @Override
  public ListenableFuture<Void> accept(List<Span> rawSpans) {
    ImmutableSet.Builder<ListenableFuture<?>> futures = ImmutableSet.builder();

    for (Span span : rawSpans) {
      // indexing occurs by timestamp, so derive one if not present.
      Long timestamp = guessTimestamp(span);
      BigInteger traceId = CassandraUtil.extractTraceId(span);
      futures.add(storeSpan(span, traceId, timestamp));

      for (String serviceName : span.serviceNames()) {
        // QueryRequest.min/maxDuration
        if (timestamp != null) {
          // Contract for Repository.storeTraceServiceSpanName is to store the span twice, once with
          // the span name and another with empty string.
          futures.add(storeTraceServiceSpanName(serviceName, span.name, timestamp, span.duration,
              traceId));
          if (!span.name.isEmpty()) { // If span.name == "", this would be redundant
            futures.add(storeTraceServiceSpanName(serviceName, "", timestamp, span.duration, traceId));
            futures.add(storeServiceSpanName(serviceName, span.name));
          }
        }
      }
    }
    return transform(Futures.allAsList(futures.build()), TO_VOID);
  }

  /**
   * Store the span in the underlying storage for later retrieval.
   */
  ListenableFuture<?> storeSpan(Span span, BigInteger traceId, Long timestamp) {
    try {
      if ((null == timestamp || 0 == timestamp)
          && metadata.compactionClass.contains("TimeWindowCompactionStrategy")) {

        LOG.warn("Span {} in trace {} had no timestamp. "
            + "If this happens a lot consider switching back to SizeTieredCompactionStrategy for "
            + "{}.traces", span.id, span.traceId, session.getLoggedKeyspace());
      }

      List<AnnotationUDT> annotations = new ArrayList<>(span.annotations.size());
      for (Annotation annotation : span.annotations) {
        annotations.add(new AnnotationUDT(annotation));
      }
      List<BinaryAnnotationUDT> binaryAnnotations = new ArrayList<>(span.binaryAnnotations.size());
      for (BinaryAnnotation annotation : span.binaryAnnotations) {
        binaryAnnotations.add(new BinaryAnnotationUDT(annotation));
      }
      Set<String> annotationKeys = CassandraUtil.annotationKeys(span);

      BoundStatement bound = bindWithName(insertSpan, "insert-span")
          .setVarint("trace_id", traceId)
          .setUUID("ts_uuid", new UUID(
              UUIDs.startOf(null != timestamp ? (timestamp / 1000) : 0)
                  .getMostSignificantBits(),
              UUIDs.random().getLeastSignificantBits()))
          .setLong("id", span.id)
          .setString("span_name", span.name)
          .setList("annotations", annotations)
          .setList("binary_annotations", binaryAnnotations)
          .setString("all_annotations", Joiner.on(',').join(annotationKeys));

      if (null != span.timestamp) {
        bound = bound.setLong("ts", span.timestamp);
      }
      if (null != span.duration) {
        bound = bound.setLong("duration", span.duration);
      }
      if (null != span.parentId) {
        bound = bound.setLong("parent_id", span.parentId);
      }

      return session.executeAsync(bound);
    } catch (RuntimeException ex) {
      return Futures.immediateFailedFuture(ex);
    }
  }

  ListenableFuture<?> storeTraceServiceSpanName(
      String serviceName,
      String spanName,
      long timestamp_micro,
      Long duration,
      BigInteger traceId) {

    int bucket = durationIndexBucket(timestamp_micro);
    UUID ts = new UUID(
        UUIDs.startOf(timestamp_micro / 1000).getMostSignificantBits(),
        UUIDs.random().getLeastSignificantBits());
    try {
      BoundStatement bound =
          bindWithName(insertTraceServiceSpanName, "insert-service-span-name")
              .setString("service_name", serviceName)
              .setString("span_name", spanName)
              .setInt("bucket", bucket)
              .setUUID("ts", ts)
              .setVarint("trace_id", traceId);

      if (null != duration) {
        bound = bound.setLong("duration", duration);
      }

      return deduplicatingExecutor.maybeExecuteAsync(bound,
          new TraceServiceSpanNameKey(serviceName, spanName, bucket, ts));

    } catch (RuntimeException ex) {
      return Futures.immediateFailedFuture(ex);
    }
  }

  ListenableFuture<?> storeServiceSpanName(
      String serviceName,
      String spanName
  ) {
    try {
      BoundStatement bound = bindWithName(insertServiceSpanName, "insert-service-span-name")
          .setString("service_name", serviceName)
          .setString("span_name", spanName);

      return deduplicatingExecutor.maybeExecuteAsync(bound, Pair.create(serviceName, spanName));
    } catch (RuntimeException ex) {
      return Futures.immediateFailedFuture(ex);
    }
  }

  static BigInteger traceId(Span span) {
    return span.traceIdHigh != 0
        ? BigInteger.valueOf(span.traceIdHigh).shiftLeft(64).or(BigInteger.valueOf(span.traceId))
        : BigInteger.valueOf(span.traceId);
  }

  final class TraceServiceSpanNameKey {

    final String serviceName;
    final String spanName;
    final int bucket;
    final UUID ts;

    TraceServiceSpanNameKey(String serviceName, String spanName, int bucket, UUID ts) {
      this.serviceName = serviceName;
      this.spanName = spanName;
      this.bucket = bucket;
      this.ts = ts;
    }

    @Override
    public String toString() {
      return "(" + serviceName + ", " + spanName + ", " + bucket + ", " + ts + " )";
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      if (o instanceof TraceServiceSpanNameKey) {
        TraceServiceSpanNameKey that = (TraceServiceSpanNameKey) o;
        return this.serviceName.equals(that.serviceName) &&
               this.spanName.equals(that.spanName) &&
               this.bucket == that.bucket &&
               this.ts.equals(that.ts);
      }
      return false;
    }

    @Override
    public int hashCode() {
      int h = 1;
      h *= 1000003;
      h ^= this.serviceName.hashCode();
      h *= 1000003;
      h ^= this.spanName.hashCode();
      h *= 1000003;
      h ^= this.bucket;
      h *= 1000003;
      h ^= this.ts.hashCode();
      return h;
    }
  }

}
