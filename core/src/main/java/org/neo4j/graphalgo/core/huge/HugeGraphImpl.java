package org.neo4j.graphalgo.core.huge;

import org.neo4j.collection.primitive.PrimitiveLongIterable;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.graphalgo.api.HugeGraph;
import org.neo4j.graphalgo.api.HugeRelationshipConsumer;
import org.neo4j.graphalgo.api.HugeWeightMapping;
import org.neo4j.graphalgo.api.RelationshipConsumer;
import org.neo4j.graphalgo.api.WeightedRelationshipConsumer;
import org.neo4j.graphalgo.core.utils.RawValues;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.ByteArray;
import org.neo4j.graphalgo.core.utils.paged.LongArray;
import org.neo4j.graphdb.Direction;

import java.util.Collection;
import java.util.function.LongPredicate;

/**
 * Huge Graph contains two array like data structures.
 * <p>
 * The adjacency data is stored in a ByteArray, which is a byte[] addressable by
 * longs indices and capable of storing about 2^46 (~ 70k bn) bytes – or 64 TiB.
 * The bytes are stored in byte[] pages of 32 KiB size.
 * <p>
 * The data is in the format:
 * <blockquote>
 * <code>degree</code> ~ <code>targetId</code><sub><code>1</code></sub> ~ <code>targetId</code><sub><code>2</code></sub> ~ <code>targetId</code><sub><code>n</code></sub>
 * </blockquote>
 * The {@code degree} is stored as a fill-sized 4 byte long {@code int}
 * (the neo kernel api returns an int for {@link org.neo4j.kernel.api.ReadOperations#nodeGetDegree(long, Direction)}).
 * Every target ID is first sorted, then delta encoded, and finally written as variable-length vlongs.
 * The delta encoding does not write the actual value but only the difference to the previous value, which plays very nice with the vlong encoding.
 * <p>
 * The seconds data structure is a LongArray, which is a long[] addressable by longs
 * and capable of storing about 2^43 (~9k bn) longs – or 64 TiB worth of 64 bit longs.
 * The data is the offset address into the aforementioned adjacency array, the index is the respective source node id.
 * <p>
 * To traverse all nodes, first access to offset from the LongArray, then read
 * 4 bytes into the {@code degree} from the ByteArray, starting from the offset, then read
 * {@code degree} vlongs as targetId.
 * <p>
 * <p>
 * The graph encoding (sans delta+vlong) is similar to that of the
 * {@link org.neo4j.graphalgo.core.lightweight.LightGraph} but stores degree
 * explicitly into the target adjacency array where the LightGraph would subtract
 * offsets of two consecutive nodes. While that doesn't use up memory to store the
 * degree, it makes it practically impossible to build the array out-of-order,
 * which is necessary for loading the graph in parallel.
 * <p>
 * Reading the degree from the offset position not only does not require the offset array
 * to be sorted but also allows the adjacency array to be sparse. This fact is
 * used during the import – each thread pre-allocates a local chunk of some pages (512 KiB)
 * and gives access to this data during import. Synchronization between threads only
 * has to happen when a new chunk has to be pre-allocated. This is similar to
 * what most garbage collectors do with TLAB allocations.
 *
 * @see <a href="https://developers.google.com/protocol-buffers/docs/encoding#varints">more abount vlong</a>
 * @see <a href="https://shipilev.net/jvm-anatomy-park/4-tlab-allocation/">more abount TLAB allocation</a>
 */
public class HugeGraphImpl implements HugeGraph {

    private final HugeIdMap idMapping;
    private final AllocationTracker tracker;

    private HugeWeightMapping weights;
    private ByteArray inAdjacency;
    private ByteArray outAdjacency;
    private LongArray inOffsets;
    private LongArray outOffsets;
    private ByteArray.DeltaCursor empty;
    private ByteArray.DeltaCursor inCache;
    private ByteArray.DeltaCursor outCache;
    private final boolean isBoth;

    HugeGraphImpl(
            final AllocationTracker tracker,
            final HugeIdMap idMapping,
            final HugeWeightMapping weights,
            final ByteArray inAdjacency,
            final ByteArray outAdjacency,
            final LongArray inOffsets,
            final LongArray outOffsets) {
        this.idMapping = idMapping;
        this.tracker = tracker;
        this.weights = weights;
        this.inAdjacency = inAdjacency;
        this.outAdjacency = outAdjacency;
        this.inOffsets = inOffsets;
        this.outOffsets = outOffsets;
        inCache = newCursor(this.inAdjacency);
        outCache = newCursor(this.outAdjacency);
        empty = inCache == null ? newCursor(this.outAdjacency) : newCursor(this.inAdjacency);
        isBoth = inAdjacency != null && outAdjacency != null;
    }

    @Override
    public long nodeCount() {
        return idMapping.nodeCount();
    }

    @Override
    public Collection<PrimitiveLongIterable> hugeBatchIterables(final int batchSize) {
        return idMapping.hugeBatchIterables(batchSize);
    }

    @Override
    public void forEachNode(LongPredicate consumer) {
        idMapping.forEachNode(consumer);
    }

    @Override
    public PrimitiveLongIterator hugeNodeIterator() {
        return idMapping.hugeNodeIterator();
    }

    @Override
    public double weightOf(final long sourceNodeId, final long targetNodeId) {
        if (isBoth && sourceNodeId > targetNodeId) {
            return weights.weight(targetNodeId, sourceNodeId);
        }
        return weights.weight(sourceNodeId, targetNodeId);
    }

    @Override
    public void forEachRelationship(
            long vertexId,
            Direction direction,
            HugeRelationshipConsumer consumer) {
        switch (direction) {
            case INCOMING:
                forEachIncoming(vertexId, consumer);
                return;

            case OUTGOING:
                forEachOutgoing(vertexId, consumer);
                return;

            case BOTH:
                forEachIncoming(vertexId, consumer);
                forEachOutgoing(vertexId, consumer);
                return;

            default:
                throw new IllegalArgumentException(direction + "");
        }
    }

    @Override
    public void forEachRelationship(
            int nodeId,
            Direction direction,
            RelationshipConsumer consumer) {
        switch (direction) {
            case INCOMING:
                forEachIncoming(nodeId, consumer);
                return;

            case OUTGOING:
                forEachOutgoing(nodeId, consumer);
                return;

            case BOTH:
                forEachIncoming(nodeId, consumer);
                forEachOutgoing(nodeId, consumer);
                return;

            default:
                throw new IllegalArgumentException(direction + "");
        }
    }

    @Override
    public void forEachRelationship(
            int nodeId,
            Direction direction,
            WeightedRelationshipConsumer consumer) {
        RelationshipConsumer nonWeighted = (s, t, relId) -> {
            double weight = weightOf((long) s, (long) t);
            return consumer.accept(
                    s,
                    t,
                    RawValues.combineIntInt(direction, s, t),
                    weight);
        };
        forEachRelationship(nodeId, direction, nonWeighted);
    }

    @Override
    public int degree(
            final long node,
            final Direction direction) {
        switch (direction) {
            case INCOMING:
                return degree(node, inOffsets, inAdjacency);

            case OUTGOING:
                return degree(node, outOffsets, outAdjacency);

            case BOTH:
                return degree(node, inOffsets, inAdjacency) + degree(
                        node,
                        outOffsets,
                        outAdjacency);

            default:
                throw new IllegalArgumentException(direction + "");
        }
    }

    @Override
    public long toHugeMappedNodeId(long nodeId) {
        return idMapping.toHugeMappedNodeId(nodeId);
    }

    @Override
    public long toOriginalNodeId(long vertexId) {
        return idMapping.toOriginalNodeId(vertexId);
    }

    @Override
    public boolean contains(final long nodeId) {
        return idMapping.contains(nodeId);
    }

    @Override
    public void forEachIncoming(
            final long node,
            final HugeRelationshipConsumer consumer) {
        ByteArray.DeltaCursor cursor = cursor(
                node,
                inCache,
                inOffsets,
                inAdjacency);
        consumeNodes(node, cursor, consumer);
    }

    @Override
    public void forEachIncoming(int nodeId, RelationshipConsumer consumer) {
        final long node = (long) nodeId;
        ByteArray.DeltaCursor cursor = cursor(
                node,
                inAdjacency.newCursor(),
                inOffsets,
                inAdjacency);
        consumeNodes(node, cursor, (s, t) -> consumer.accept(
                (int) s,
                (int) t,
                RawValues.combineIntInt((int) t, (int) s)));
    }

    @Override
    public void forEachOutgoing(
            final long node,
            final HugeRelationshipConsumer consumer) {
        ByteArray.DeltaCursor cursor = cursor(
                node,
                outCache,
                outOffsets,
                outAdjacency);
        consumeNodes(node, cursor, consumer);
    }

    @Override
    public void forEachOutgoing(int nodeId, RelationshipConsumer consumer) {
        final long node = (long) nodeId;
        ByteArray.DeltaCursor cursor = cursor(
                node,
                outAdjacency.newCursor(),
                outOffsets,
                outAdjacency);
        consumeNodes(node, cursor, (s, t) -> consumer.accept(
                (int) s,
                (int) t,
                RawValues.combineIntInt((int) s, (int) t)));
    }

    @Override
    public HugeGraph concurrentCopy() {
        return new HugeGraphImpl(
                tracker,
                idMapping,
                weights,
                inAdjacency,
                outAdjacency,
                inOffsets,
                outOffsets
        );
    }

    private ByteArray.DeltaCursor newCursor(final ByteArray adjacency) {
        return adjacency != null ? adjacency.newCursor() : null;
    }

    private int degree(long node, LongArray offsets, ByteArray array) {
        long offset = offsets.get(node);
        if (offset == 0L) {
            return 0;
        }
        return array.getInt(offset);
    }

    private ByteArray.DeltaCursor cursor(
            long node,
            ByteArray.DeltaCursor reuse,
            LongArray offsets,
            ByteArray array) {
        final long offset = offsets.get(node);
        if (offset == 0L) {
            return empty;
        }
        return array.deltaCursor(reuse, offset);
    }

    private void consumeNodes(
            long startNode,
            ByteArray.DeltaCursor cursor,
            HugeRelationshipConsumer consumer) {
        long next;
        //noinspection StatementWithEmptyBody
        while ((next = cursor.getVLong()) != -1L &&
                consumer.accept(startNode, next)) ;
    }

    @Override
    public void release() {
        if (inAdjacency != null) {
            tracker.remove(inAdjacency.release());
            tracker.remove(inOffsets.release());
            inAdjacency = null;
            inOffsets = null;
        }
        if (outAdjacency != null) {
            tracker.remove(outAdjacency.release());
            tracker.remove(outOffsets.release());
            outAdjacency = null;
            outOffsets = null;
        }
        tracker.remove(weights.release());
        empty = null;
        inCache = null;
        outCache = null;
        weights = null;
    }
}
