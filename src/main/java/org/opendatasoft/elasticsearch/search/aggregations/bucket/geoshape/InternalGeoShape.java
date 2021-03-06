package org.opendatasoft.elasticsearch.search.aggregations.bucket.geoshape;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.LongObjectPagedHashMap;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.InternalMultiBucketAggregation;
import org.elasticsearch.search.aggregations.KeyComparable;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.opendatasoft.elasticsearch.plugin.GeoUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * An internal implementation of {@link InternalMultiBucketAggregation} which extends {@link Aggregation}.
 */
public class InternalGeoShape extends InternalMultiBucketAggregation<InternalGeoShape,
        InternalGeoShape.InternalBucket> implements GeoShape {

    public enum OutputFormat {
        WKT,
        WKB,
        GEOJSON
    }

    /**
     * The bucket class of InternalGeoShape.
     * @see MultiBucketsAggregation.Bucket
     */
    public static class InternalBucket extends InternalMultiBucketAggregation.InternalBucket implements
            GeoShape.Bucket, KeyComparable<InternalBucket> {

        protected BytesRef wkb;
        protected String wkbHash;
        protected String realType;
        protected double area;
        long bucketOrd;
        protected long docCount;
        protected InternalAggregations aggregations;

        public InternalBucket(BytesRef wkb, String wkbHash, String realType, double area,
                              long docCount, InternalAggregations aggregations) {
            this.wkb = wkb;
            this.wkbHash = wkbHash;
            this.realType = realType;
            this.docCount = docCount;
            this.aggregations = aggregations;
            this.area = area;
        }

        /**
         * Read from a stream.
         */
        public InternalBucket(StreamInput in) throws IOException {
            wkb = in.readBytesRef();
            wkbHash = in.readString();
            realType = in.readString();
            area = in.readDouble();
            docCount = in.readLong();
            aggregations = InternalAggregations.readAggregations(in);
        }

        /**
         * Write to a stream.
         */
        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeBytesRef(wkb);
            out.writeString(wkbHash);
            out.writeString(realType);
            out.writeDouble(area);
            out.writeLong(docCount);
            aggregations.writeTo(out);
        }

        @Override
        public String getKey() {
            return wkb.toString();
        }

        @Override
        public String getKeyAsString() {
            return wkb.utf8ToString();
        }

        @Override
        public int compareKey(InternalGeoShape.InternalBucket other) {
            return wkb.compareTo(other.wkb);
        }

        private long getShapeHash() {
            return wkb.hashCode();
        }

        private String getType() {
            return realType;
        }

        private int compareTo(InternalBucket other) {
            if (this.docCount > other.docCount) {
                return 1;
            }
            else if (this.docCount < other.docCount) {
                return -1;
            }
            else
                return 0;
        }

        @Override
        public long getDocCount() {
            return docCount;
        }

        @Override
        public Aggregations getAggregations() {
            return aggregations;
        }

        InternalBucket reduce(List<InternalBucket> buckets, ReduceContext context) {
            List<InternalAggregations> aggregationsList = new ArrayList<>(buckets.size());
            InternalBucket reduced = null;
            for (InternalBucket bucket : buckets) {
                if (reduced == null) {
                    reduced = bucket;
                } else {
                    reduced.docCount += bucket.docCount;
                }
                aggregationsList.add(bucket.aggregations);
            }
            reduced.aggregations = InternalAggregations.reduce(aggregationsList, context);
            return reduced;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(CommonFields.DOC_COUNT.getPreferredName(), docCount);
            aggregations.toXContentInternal(builder, params);
            builder.endObject();
            return builder;
        }

    }

    private List<InternalBucket> buckets;
    private final int requiredSize;
    private final int shardSize;
    private OutputFormat output_format;
    private GeoJsonWriter geoJsonWriter;

    public InternalGeoShape(
            String name,
            List<InternalBucket> buckets,
            OutputFormat output_format,
            int requiredSize,
            int shardSize,
            List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData
    ) {
        super(name, pipelineAggregators, metaData);
        this.buckets = buckets;
        this.output_format = output_format;
        this.requiredSize = requiredSize;
        this.shardSize = shardSize;
        geoJsonWriter = new GeoJsonWriter();
    }

    /**
     * Read from a stream.
     */
    public InternalGeoShape(StreamInput in) throws IOException {
        super(in);
        output_format = InternalGeoShape.OutputFormat.valueOf(in.readString());
        requiredSize = readSize(in);
        shardSize = readSize(in);
        this.buckets = in.readList(InternalBucket::new);
    }

    /**
     * Write to a stream.
     */
    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(output_format.name());
        writeSize(requiredSize, out);
        writeSize(shardSize, out);
        out.writeList(buckets);
    }

    @Override
    public String getWriteableName() {
        return GeoShapeBuilder.NAME;
    }

//    protected int getShardSize() {
//        return shardSize;
//    }

    @Override
    public InternalGeoShape create(List<InternalBucket> buckets) {
        return new InternalGeoShape(this.name, buckets, output_format,
            requiredSize, shardSize, this.pipelineAggregators(), this.metaData);
    }

    @Override
    public InternalBucket createBucket(InternalAggregations aggregations, InternalBucket prototype) {
        return new InternalBucket(prototype.wkb, prototype.wkbHash, prototype.realType,
                prototype.area, prototype.docCount, aggregations);
    }

    @Override
    public List<InternalBucket> getBuckets() {
        return buckets;
    }

    /**
     * Reduces the given aggregations to a single one and returns it.
     */
    @Override
    public InternalGeoShape doReduce(List<InternalAggregation> aggregations, ReduceContext reduceContext) {
        LongObjectPagedHashMap<List<InternalBucket>> buckets = null;

        for (InternalAggregation aggregation : aggregations) {
            InternalGeoShape shape = (InternalGeoShape) aggregation;
            if (buckets == null) {
                buckets = new LongObjectPagedHashMap<>(shape.buckets.size(), reduceContext.bigArrays());
            }

            for (InternalBucket bucket : shape.buckets) {
                List<InternalBucket> existingBuckets = buckets.get(bucket.getShapeHash());
                if (existingBuckets == null) {
                    existingBuckets = new ArrayList<>(aggregations.size());
                    buckets.put(bucket.getShapeHash(), existingBuckets);
                }
                existingBuckets.add(bucket);
            }
        }

        final int size = !reduceContext.isFinalReduce() ? (int) buckets.size() : Math.min(requiredSize, (int) buckets.size());

        BucketPriorityQueue ordered = new BucketPriorityQueue(size);
        for (LongObjectPagedHashMap.Cursor<List<InternalBucket>> cursor : buckets) {
            List<InternalBucket> sameCellBuckets = cursor.value;
            ordered.insertWithOverflow(sameCellBuckets.get(0).reduce(sameCellBuckets, reduceContext));
        }
        buckets.close();
        InternalBucket[] list = new InternalBucket[ordered.size()];
        for (int i = ordered.size() - 1; i >= 0; i--) {
            list[i] = ordered.pop();
        }

        return new InternalGeoShape(getName(), Arrays.asList(list), output_format, requiredSize, shardSize,
                pipelineAggregators(), getMetaData());
    }


    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        builder.startArray(CommonFields.BUCKETS.getPreferredName());
        for (InternalBucket bucket : buckets) {
            builder.startObject();
            try {
                builder.field(CommonFields.KEY.getPreferredName(), GeoUtils.exportWkbTo(bucket.wkb, output_format, geoJsonWriter));
                builder.field("digest", bucket.wkbHash);
                builder.field("type", bucket.getType());
            } catch (ParseException e) {
                continue;
            }
            builder.field(CommonFields.DOC_COUNT.getPreferredName(), bucket.getDocCount());
            bucket.getAggregations().toXContentInternal(builder, params);
            builder.endObject();
        }
        builder.endArray();
        return builder;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(buckets, output_format, requiredSize, shardSize);
    }

    @Override
    protected boolean doEquals(Object obj) {
        InternalGeoShape that = (InternalGeoShape) obj;
        return Objects.equals(buckets, that.buckets)
                && Objects.equals(output_format, that.output_format)
                && Objects.equals(requiredSize, that.requiredSize)
                && Objects.equals(shardSize, that.shardSize);
    }

    static class BucketPriorityQueue extends PriorityQueue<InternalBucket> {

        BucketPriorityQueue(int size) {
            super(size);
        }

        @Override
        protected boolean lessThan(InternalBucket o1, InternalBucket o2) {

            double i = o2.area - o1.area;
            if (i == 0) {
                i = o2.compareTo(o1);
                if (i == 0) {
                    i = System.identityHashCode(o2) - System.identityHashCode(o1);
                }
            }
            return i > 0;
        }
    }
}
