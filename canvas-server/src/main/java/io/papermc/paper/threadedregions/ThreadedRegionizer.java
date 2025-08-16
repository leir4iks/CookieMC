package io.papermc.paper.threadedregions;

import ca.spottedleaf.concurrentutil.map.SWMRLong2ObjectHashTable;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import com.destroystokyo.paper.util.SneakyThrow;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ThreadedRegionizer<R extends ThreadedRegionizer.ThreadedRegionData<R, S>, S extends ThreadedRegionizer.ThreadedRegionSectionData> {

    private static final Logger LOGGER = LogUtils.getLogger();

    public final int regionSectionChunkSize;
    public final int sectionChunkShift;
    public final int minSectionRecalcCount;
    public final int emptySectionCreateRadius;
    public final int regionSectionMergeRadius;
    public final double maxDeadRegionPercent;
    public final ServerLevel world;

    private final SWMRLong2ObjectHashTable<ThreadedRegionSection<R, S>> sections = new SWMRLong2ObjectHashTable<>();
    private final SWMRLong2ObjectHashTable<ThreadedRegion<R, S>> regionsById = new SWMRLong2ObjectHashTable<>();
    private final RegionCallbacks<R, S> callbacks;
    private final ReentrantLock regionLock = new ReentrantLock();
    private Thread writeLockOwner;

    public ThreadedRegionizer(final int minSectionRecalcCount, final double maxDeadRegionPercent,
                              final int emptySectionCreateRadius, final int regionSectionMergeRadius,
                              final int regionSectionChunkShift, final ServerLevel world,
                              final RegionCallbacks<R, S> callbacks) {
        if (emptySectionCreateRadius <= 0) {
            throw new IllegalStateException("Region section create radius must be > 0");
        }
        if (regionSectionMergeRadius <= 0) {
            throw new IllegalStateException("Region section merge radius must be > 0");
        }
        this.regionSectionChunkSize = 1 << regionSectionChunkShift;
        this.sectionChunkShift = regionSectionChunkShift;
        this.minSectionRecalcCount = Math.max(2, minSectionRecalcCount);
        this.maxDeadRegionPercent = maxDeadRegionPercent;
        this.emptySectionCreateRadius = emptySectionCreateRadius;
        this.regionSectionMergeRadius = regionSectionMergeRadius;
        this.world = world;
        this.callbacks = callbacks;
    }

    public void acquireReadLock() {
        this.regionLock.lock();
    }

    public void releaseReadLock() {
        this.regionLock.unlock();
    }

    private void acquireWriteLock() {
        final Thread currentThread = Thread.currentThread();
        if (this.writeLockOwner == currentThread) {
            throw new IllegalStateException("Cannot recursively operate in the regioniser");
        }
        this.regionLock.lock();
        this.writeLockOwner = currentThread;
    }

    private void releaseWriteLock() {
        this.writeLockOwner = null;
        this.regionLock.unlock();
    }

    private void onRegionCreate(final ThreadedRegion<R, S> region) {
        final ThreadedRegion<R, S> conflict;
        if ((conflict = this.regionsById.putIfAbsent(region.id, region)) != null) {
            throw new IllegalStateException("Region " + region + " is already mapped to " + conflict);
        }
    }

    private void onRegionDestroy(final ThreadedRegion<R, S> region) {
        final ThreadedRegion<R, S> removed = this.regionsById.remove(region.id);
        if (removed == null) return;
        if (removed != region) {
            throw new IllegalStateException("Expected to remove " + region + ", but removed " + removed);
        }
    }

    public int getSectionCoordinate(final int chunkCoordinate) {
        return chunkCoordinate >> this.sectionChunkShift;
    }

    public long getSectionKey(final BlockPos pos) {
        return CoordinateUtils.getChunkKey((pos.getX() >> 4) >> this.sectionChunkShift, (pos.getZ() >> 4) >> this.sectionChunkShift);
    }

    public long getSectionKey(final ChunkPos pos) {
        return CoordinateUtils.getChunkKey(pos.x >> this.sectionChunkShift, pos.z >> this.sectionChunkShift);
    }

    public long getSectionKey(final Entity entity) {
        final ChunkPos pos = entity.chunkPosition();
        return CoordinateUtils.getChunkKey(pos.x >> this.sectionChunkShift, pos.z >> this.sectionChunkShift);
    }

    public void computeForAllRegions(final Consumer<? super ThreadedRegion<R, S>> consumer) {
        this.acquireReadLock();
        try {
            this.regionsById.forEachValue(consumer);
        } finally {
            this.releaseReadLock();
        }
    }

    public void computeForAllRegionsUnsynchronised(final Consumer<? super ThreadedRegion<R, S>> consumer) {
        this.regionsById.forEachValue(consumer);
    }

    public int computeForRegions(final int fromChunkX, final int fromChunkZ, final int toChunkX, final int toChunkZ,
                                 final Consumer<Set<ThreadedRegion<R, S>>> consumer) {
        final int shift = this.sectionChunkShift;
        final int fromSectionX = fromChunkX >> shift;
        final int fromSectionZ = fromChunkZ >> shift;
        final int toSectionX = toChunkX >> shift;
        final int toSectionZ = toChunkZ >> shift;
        this.acquireWriteLock();
        try {
            final ReferenceOpenHashSet<ThreadedRegion<R, S>> set = new ReferenceOpenHashSet<>();

            for (int currZ = fromSectionZ; currZ <= toSectionZ; ++currZ) {
                for (int currX = fromSectionX; currX <= toSectionX; ++currX) {
                    final ThreadedRegionSection<R, S> section = this.sections.get(CoordinateUtils.getChunkKey(currX, currZ));
                    if (section != null) {
                        set.add(section.getRegionPlain());
                    }
                }
            }

            consumer.accept(set);

            return set.size();
        } finally {
            this.releaseWriteLock();
        }
    }

    public ThreadedRegion<R, S> getRegionAtUnsynchronised(final int chunkX, final int chunkZ) {
        final int sectionX = chunkX >> this.sectionChunkShift;
        final int sectionZ = chunkZ >> this.sectionChunkShift;
        final long sectionKey = CoordinateUtils.getChunkKey(sectionX, sectionZ);

        final ThreadedRegionSection<R, S> section = this.sections.get(sectionKey);

        return section == null ? null : section.getRegion();
    }

    public ThreadedRegion<R, S> getRegionAtSynchronised(final int chunkX, final int chunkZ) {
        final int sectionX = chunkX >> this.sectionChunkShift;
        final int sectionZ = chunkZ >> this.sectionChunkShift;
        final long sectionKey = CoordinateUtils.getChunkKey(sectionX, sectionZ);

        this.acquireReadLock();
        try {
            final ThreadedRegionSection<R, S> section = this.sections.get(sectionKey);
            return section == null ? null : section.getRegionPlain();
        } finally {
            this.releaseReadLock();
        }
    }

    public void computeAtRegionIfPresentOrElseSynchronized(final int chunkX, final int chunkZ,
                                                           Consumer<ThreadedRegion<R, S>> ifPresent, Runnable notPresent) {
        this.acquireReadLock();
        try {
            ThreadedRegion<R, S> region = getRegionAtUnsynchronised(chunkX, chunkZ);
            if (region != null) {
                ifPresent.accept(region);
            } else {
                notPresent.run();
            }
        } finally {
            this.releaseReadLock();
        }
    }

    public void computeAtRegionIfPresentOrElseUnsynchronized(final int chunkX, final int chunkZ,
                                                             Consumer<ThreadedRegion<R, S>> ifPresent, Runnable notPresent) {
        ThreadedRegion<R, S> region = getRegionAtUnsynchronised(chunkX, chunkZ);
        if (region != null) {
            ifPresent.accept(region);
        } else {
            notPresent.run();
        }
    }

    public void addChunk(final int chunkX, final int chunkZ) {
        final int sectionX = chunkX >> this.sectionChunkShift;
        final int sectionZ = chunkZ >> this.sectionChunkShift;
        final long sectionKey = CoordinateUtils.getChunkKey(sectionX, sectionZ);

        {
            final ThreadedRegionSection<R, S> existing = this.sections.get(sectionKey);
            if (existing != null && !existing.isEmpty()) {
                existing.addChunk(chunkX, chunkZ);
                return;
            }
        }

        this.acquireWriteLock();
        try {
            ThreadedRegionSection<R, S> section = this.sections.get(sectionKey);

            List<ThreadedRegionSection<R, S>> newSections = new ArrayList<>();

            if (section == null) {
                section = new ThreadedRegionSection<>(sectionX, sectionZ, this, chunkX, chunkZ);
                this.sections.put(sectionKey, section);
                newSections.add(section);
            } else {
                section.addChunk(chunkX, chunkZ);
            }

            final int createRadius = this.emptySectionCreateRadius;
            final int searchRadius = createRadius + this.regionSectionMergeRadius;
            ReferenceOpenHashSet<ThreadedRegion<R, S>> nearbyRegions = null;
            for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
                for (int dz = -searchRadius; dz <= searchRadius; ++dz) {
                    if ((dx | dz) == 0) {
                        continue;
                    }
                    final int squareDistance = Math.max(Math.abs(dx), Math.abs(dz));
                    final boolean inCreateRange = squareDistance <= createRadius;

                    final int neighbourX = dx + sectionX;
                    final int neighbourZ = dz + sectionZ;
                    final long neighbourKey = CoordinateUtils.getChunkKey(neighbourX, neighbourZ);

                    ThreadedRegionSection<R, S> neighbourSection = this.sections.get(neighbourKey);

                    if (neighbourSection != null) {
                        if (nearbyRegions == null) {
                            nearbyRegions = new ReferenceOpenHashSet<>(((searchRadius * 2 + 1) * (searchRadius * 2 + 1)) >> 1);
                        }
                        nearbyRegions.add(neighbourSection.getRegionPlain());
                    }

                    if (!inCreateRange) {
                        continue;
                    }

                    if (neighbourSection != null) {
                        neighbourSection.incrementNonEmptyNeighbours();
                        continue;
                    }
                    neighbourSection = new ThreadedRegionSection<>(neighbourX, neighbourZ, this, 1);
                    if (null != this.sections.put(neighbourKey, neighbourSection)) {
                        throw new IllegalStateException("Failed to insert new section");
                    }
                    newSections.add(neighbourSection);
                }
            }

            if (newSections.isEmpty()) {
                return;
            }

            final ThreadedRegion<R, S> regionOfInterest;
            final boolean regionOfInterestAlive;
            if (nearbyRegions == null) {
                regionOfInterest = new ThreadedRegion<>(this);
                regionOfInterestAlive = true;

                for (int i = 0, len = newSections.size(); i < len; ++i) {
                    regionOfInterest.addSection(newSections.get(i));
                }

                regionOfInterest.onCreate();
            } else {
                ThreadedRegion<R, S> firstUnlockedRegion = null;

                for (final ThreadedRegion<R, S> region : nearbyRegions) {
                    if (region.isTicking()) {
                        continue;
                    }
                    firstUnlockedRegion = region;
                    if (firstUnlockedRegion.state == ThreadedRegion.STATE_READY && (!firstUnlockedRegion.mergeIntoLater.isEmpty() || !firstUnlockedRegion.expectingMergeFrom.isEmpty())) {
                        throw new IllegalStateException("Illegal state for unlocked region " + firstUnlockedRegion);
                    }
                    break;
                }

                if (firstUnlockedRegion != null) {
                    regionOfInterest = firstUnlockedRegion;
                } else {
                    regionOfInterest = new ThreadedRegion<>(this);
                }

                for (int i = 0, len = newSections.size(); i < len; ++i) {
                    regionOfInterest.addSection(newSections.get(i));
                }

                if (firstUnlockedRegion == null) {
                    regionOfInterest.onCreate();
                }

                if (firstUnlockedRegion != null && nearbyRegions.size() == 1) {
                    return;
                }

                for (final ThreadedRegion<R, S> region : nearbyRegions) {
                    if (region == regionOfInterest) {
                        continue;
                    }

                    if (!region.killAndMergeInto(regionOfInterest)) {
                        regionOfInterest.mergeIntoLater(region);
                    }
                }

                if (firstUnlockedRegion != null && firstUnlockedRegion.state == ThreadedRegion.STATE_READY) {
                    if (!firstUnlockedRegion.mergeIntoLater.isEmpty() || !firstUnlockedRegion.expectingMergeFrom.isEmpty()) {
                        firstUnlockedRegion.state = ThreadedRegion.STATE_TRANSIENT;
                        this.callbacks.onRegionInactive(firstUnlockedRegion);
                    }
                }

                regionOfInterestAlive = firstUnlockedRegion == null && regionOfInterest.mergeIntoLater.isEmpty() && regionOfInterest.expectingMergeFrom.isEmpty();
            }

            if (regionOfInterestAlive) {
                regionOfInterest.state = ThreadedRegion.STATE_READY;
                if (!regionOfInterest.mergeIntoLater.isEmpty() || !regionOfInterest.expectingMergeFrom.isEmpty()) {
                    throw new IllegalStateException("Should not happen on region " + this);
                }
                this.callbacks.onRegionActive(regionOfInterest);
            }

            if (regionOfInterest.state == ThreadedRegion.STATE_READY) {
                if (!regionOfInterest.mergeIntoLater.isEmpty() || !regionOfInterest.expectingMergeFrom.isEmpty()) {
                    throw new IllegalStateException("Should not happen on region " + this);
                }
            }
        } catch (final Throwable throwable) {
            LOGGER.error("Failed to add chunk (" + chunkX + "," + chunkZ + ")", throwable);
            SneakyThrow.sneaky(throwable);
        } finally {
            this.releaseWriteLock();
        }
    }

    public void removeChunk(final int chunkX, final int chunkZ) {
        final int sectionX = chunkX >> this.sectionChunkShift;
        final int sectionZ = chunkZ >> this.sectionChunkShift;
        final long sectionKey = CoordinateUtils.getChunkKey(sectionX, sectionZ);

        final ThreadedRegionSection<R, S> section = this.sections.get(sectionKey);
        if (section == null) {
            throw new IllegalStateException("Chunk (" + chunkX + "," + chunkZ + ") has no section");
        }
        if (!section.hasOnlyOneChunk()) {
            section.removeChunk(chunkX, chunkZ);
            return;
        }

        this.acquireWriteLock();
        try {
            section.removeChunk(chunkX, chunkZ);

            final int searchRadius = this.emptySectionCreateRadius;
            for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
                for (int dz = -searchRadius; dz <= searchRadius; ++dz) {
                    if ((dx | dz) == 0) {
                        continue;
                    }

                    final int neighbourX = dx + sectionX;
                    final int neighbourZ = dz + sectionZ;
                    final long neighbourKey = CoordinateUtils.getChunkKey(neighbourX, neighbourZ);
                    final ThreadedRegionSection<R, S> neighbourSection = this.sections.get(neighbourKey);

                    if (neighbourSection != null) {
                        neighbourSection.decrementNonEmptyNeighbours();
                    }
                }
            }
        } catch (final Throwable throwable) {
            LOGGER.error("Failed to remove chunk (" + chunkX + "," + chunkZ + ")", throwable);
            SneakyThrow.sneaky(throwable);
        } finally {
            this.releaseWriteLock();
        }
    }

    private void onRegionRelease(final ThreadedRegion<R, S> region) {
        if (!region.mergeIntoLater.isEmpty()) {
            throw new IllegalStateException("Region " + region + " should not have any regions to merge into!");
        }

        final boolean hasExpectingMerges = !region.expectingMergeFrom.isEmpty();

        if (hasExpectingMerges) {
            final ReferenceOpenHashSet<ThreadedRegion<R, S>> expectingMergeFrom = region.expectingMergeFrom.clone();
            for (final ThreadedRegion<R, S> mergeFrom : expectingMergeFrom) {
                if (!mergeFrom.killAndMergeInto(region)) {
                    throw new IllegalStateException("Merge from region " + mergeFrom + " should be killable! Trying to merge into " + region);
                }
            }

            if (!region.expectingMergeFrom.isEmpty()) {
                throw new IllegalStateException("Region " + region + " should no longer have merge requests after mering from " + expectingMergeFrom);
            }

            if (!region.mergeIntoLater.isEmpty()) {
                region.state = ThreadedRegion.STATE_TRANSIENT;
                this.callbacks.onRegionInactive(region);
                return;
            }
        }

        final boolean removeDeadSections = hasExpectingMerges || region.hasNoAliveSections()
            || (region.sectionByKey.size() >= this.minSectionRecalcCount && region.getDeadSectionPercent() >= this.maxDeadRegionPercent);
        final boolean removedDeadSections = removeDeadSections && !region.deadSections.isEmpty();
        if (removeDeadSections) {
            for (final ThreadedRegionSection<R, S> deadSection : new ArrayList<>(region.deadSections)) {
                final long key = CoordinateUtils.getChunkKey(deadSection.sectionX, deadSection.sectionZ);

                if (!deadSection.isEmpty()) {
                    throw new IllegalStateException("Dead section '" + deadSection.toStringWithRegion() + "' is marked dead but has chunks!");
                }
                if (deadSection.hasNonEmptyNeighbours()) {
                    throw new IllegalStateException("Dead section '" + deadSection.toStringWithRegion() + "' is marked dead but has non-empty neighbours!");
                }
                if (!region.sectionByKey.remove(key, deadSection)) {
                    throw new IllegalStateException("Region " + region + " has inconsistent state, it should contain section " + deadSection);
                }
                if (this.sections.remove(key) != deadSection) {
                    throw new IllegalStateException("Cannot remove dead section '" +
                        deadSection.toStringWithRegion() + "' from section state! State at section coordinate: " + this.sections.get(key));
                }
                region.deadSections.remove(deadSection);
            }
        }

        if (!removedDeadSections) {
            region.state = ThreadedRegion.STATE_READY;
            if (!region.expectingMergeFrom.isEmpty() || !region.mergeIntoLater.isEmpty()) {
                throw new IllegalStateException("Illegal state " + region);
            }
            return;
        }

        final Long2ReferenceOpenHashMap<ThreadedRegionSection<R, S>> recalculateSections = region.sectionByKey.clone();

        if (recalculateSections.isEmpty()) {
            region.state = ThreadedRegion.STATE_DEAD;
            region.onRemove(true);
            return;
        }

        final int mergeRadius = Math.max(this.regionSectionMergeRadius, this.emptySectionCreateRadius);

        final List<List<ThreadedRegionSection<R, S>>> newRegionsData = new ArrayList<>();
        while (!recalculateSections.isEmpty()) {
            final List<ThreadedRegionSection<R, S>> currRegion = new ArrayList<>();
            final Iterator<ThreadedRegionSection<R, S>> firstIterator = recalculateSections.values().iterator();

            currRegion.add(firstIterator.next());
            firstIterator.remove();
            search_loop:
            for (int idx = 0; idx < currRegion.size(); ++idx) {
                final ThreadedRegionSection<R, S> curr = currRegion.get(idx);
                final int centerX = curr.sectionX;
                final int centerZ = curr.sectionZ;

                for (int dz = -mergeRadius; dz <= mergeRadius; ++dz) {
                    for (int dx = -mergeRadius; dx <= mergeRadius; ++dx) {
                        if ((dx | dz) == 0) {
                            continue;
                        }

                        final ThreadedRegionSection<R, S> section = recalculateSections.remove(CoordinateUtils.getChunkKey(dx + centerX, dz + centerZ));
                        if (section != null) {
                            currRegion.add(section);
                            if (recalculateSections.isEmpty()) {
                                break search_loop;
                            }
                        }
                    }
                }
            }
            newRegionsData.add(currRegion);
        }

        if (newRegionsData.size() == 1) {
            region.state = ThreadedRegion.STATE_READY;
            if (!region.expectingMergeFrom.isEmpty() || !region.mergeIntoLater.isEmpty()) {
                throw new IllegalStateException("Illegal state " + region);
            }
            return;
        }

        final List<ThreadedRegion<R, S>> newRegionObjects = new ArrayList<>(newRegionsData.size());
        for (int i = 0; i < newRegionsData.size(); ++i) {
            newRegionObjects.add(new ThreadedRegion<>(this));
        }

        this.callbacks.preSplit(region, newRegionObjects);

        region.state = ThreadedRegion.STATE_DEAD;
        region.onRemove(true);

        final Long2ReferenceOpenHashMap<ThreadedRegion<R, S>> newRegionsMap = new Long2ReferenceOpenHashMap<>();
        final ReferenceOpenHashSet<ThreadedRegion<R, S>> newRegionsSet = new ReferenceOpenHashSet<>(newRegionObjects);

        for (int i = 0, len = newRegionsData.size(); i < len; i++) {
            final List<ThreadedRegionSection<R, S>> sections = newRegionsData.get(i);
            final ThreadedRegion<R, S> newRegion = newRegionObjects.get(i);

            for (final ThreadedRegionSection<R, S> section : sections) {
                section.setRegionRelease(null);
                newRegion.addSection(section);
                final ThreadedRegion<R, S> curr = newRegionsMap.putIfAbsent(section.sectionKey, newRegion);
                if (curr != null) {
                    throw new IllegalStateException("Expected no region at " + section + ", but got " + curr + ", should have put " + newRegion);
                }
            }
        }

        region.split(newRegionsMap, newRegionsSet);

        for (final ThreadedRegion<R, S> newRegion : newRegionsSet) {
            newRegion.state = ThreadedRegion.STATE_READY;
            if (!newRegion.expectingMergeFrom.isEmpty() || !newRegion.mergeIntoLater.isEmpty()) {
                throw new IllegalStateException("Illegal state " + newRegion);
            }
            newRegion.onCreate();
            this.callbacks.onRegionActive(newRegion);
        }
    }

    public static final class ThreadedRegion<R extends ThreadedRegionData<R, S>, S extends ThreadedRegionSectionData> {

        private static final AtomicLong REGION_ID_GENERATOR = new AtomicLong();

        private static final int STATE_TRANSIENT     = 0;
        private static final int STATE_READY         = 1;
        private static final int STATE_TICKING       = 2;
        private static final int STATE_DEAD          = 3;

        public final long id;

        private int state;

        private final Long2ReferenceOpenHashMap<ThreadedRegionSection<R, S>> sectionByKey = new Long2ReferenceOpenHashMap<>();
        private final ReferenceOpenHashSet<ThreadedRegionSection<R, S>> deadSections = new ReferenceOpenHashSet<>();

        public final ThreadedRegionizer<R, S> regioniser;

        private final R data;

        private final ReferenceOpenHashSet<ThreadedRegion<R, S>> mergeIntoLater = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<ThreadedRegion<R, S>> expectingMergeFrom = new ReferenceOpenHashSet<>();

        public ThreadedRegion(final ThreadedRegionizer<R, S> regioniser) {
            this.regioniser = regioniser;
            this.id = REGION_ID_GENERATOR.getAndIncrement();
            this.state = STATE_TRANSIENT;
            this.data = regioniser.callbacks.createNewData(this);
        }

        public LongArrayList getOwnedSections() {
            final boolean lock = !this.regioniser.regionLock.isHeldByCurrentThread();
            if (lock) {
                this.regioniser.acquireReadLock();
            }
            try {
                final LongArrayList ret = new LongArrayList(this.sectionByKey.size());
                ret.addAll(this.sectionByKey.keySet());
                return ret;
            } finally {
                if (lock) {
                    this.regioniser.releaseReadLock();
                }
            }
        }

        public LongIterator getOwnedSectionsUnsynchronised() {
            return this.sectionByKey.keySet().iterator();
        }

        public LongArrayList getOwnedChunks() {
            final boolean lock = !this.regioniser.regionLock.isHeldByCurrentThread();
            if (lock) {
                this.regioniser.acquireReadLock();
            }
            try {
                final LongArrayList ret = new LongArrayList();
                for (final ThreadedRegionSection<R, S> section : this.sectionByKey.values()) {
                    ret.addAll(section.getChunks());
                }
                return ret;
            } finally {
                if (lock) {
                    this.regioniser.releaseReadLock();
                }
            }
        }

        public Long getCenterSection() {
            final LongArrayList sections = this.getOwnedSections();
            if (sections.isEmpty()) {
                return null;
            }
            sections.sort(LongComparator.getInstance());
            return sections.getLong(sections.size() >> 1);
        }

        public ChunkPos getCenterChunk() {
            final LongArrayList chunks = this.getOwnedChunks();
            if (chunks.isEmpty()) {
                return null;
            }
            chunks.sort(LongComparator.getInstance());
            final long middle = chunks.getLong(chunks.size() >> 1);
            return new ChunkPos(CoordinateUtils.getChunkX(middle), CoordinateUtils.getChunkZ(middle));
        }

        private void onCreate() {
            this.regioniser.onRegionCreate(this);
            this.regioniser.callbacks.onRegionCreate(this);
        }

        private void onRemove(final boolean wasActive) {
            try {
                if (wasActive) {
                    this.regioniser.callbacks.onRegionInactive(this);
                }
                this.regioniser.callbacks.onRegionDestroy(this);
                this.regioniser.onRegionDestroy(this);
            } finally {
                if (this.data != null && this.data instanceof ServerRegions.TickRegionData tickRegionData && tickRegionData.tickHandle != null) {
                    tickRegionData.tickHandle.retire();
                }
            }
        }

        private final boolean hasNoAliveSections() {
            return this.deadSections.size() == this.sectionByKey.size();
        }

        private final double getDeadSectionPercent() {
            return (double)this.deadSections.size() / (double)this.sectionByKey.size();
        }

        private void split(final Long2ReferenceOpenHashMap<ThreadedRegion<R, S>> into, final ReferenceOpenHashSet<ThreadedRegion<R, S>> regions) {
            if (this.data != null) {
                this.data.split(this.regioniser, into, regions);
            }
        }

        boolean killAndMergeInto(final ThreadedRegion<R, S> mergeTarget) {
            if (this.state == STATE_TICKING) {
                return false;
            }
            if (mergeTarget == null || mergeTarget.isDead()) {
                return false;
            }

            this.regioniser.callbacks.preMerge(this, mergeTarget);

            if (!this.tryKill()) {
                return false;
            }

            this.mergeInto(mergeTarget);
            return true;
        }

        private void mergeInto(final ThreadedRegion<R, S> mergeTarget) {
            if (this == mergeTarget) {
                throw new IllegalStateException("Cannot merge a region onto itself");
            }
            if (!this.isDead()) {
                throw new IllegalStateException("Source region is not dead! Source " + this + ", target " + mergeTarget);
            }
            if (mergeTarget.isDead()) {
                throw new IllegalStateException("Target region is dead! Source " + this + ", target " + mergeTarget);
            }

            for (final ThreadedRegionSection<R, S> section : this.sectionByKey.values()) {
                section.setRegionRelease(null);
                mergeTarget.addSection(section);
            }
            for (final ThreadedRegionSection<R, S> deadSection : this.deadSections) {
                if (this.sectionByKey.get(deadSection.sectionKey) == deadSection) {
                    if (!mergeTarget.deadSections.add(deadSection)) {
                        throw new IllegalStateException("Merge target contains dead section from source! Has " + deadSection + " from region " + this);
                    }
                }
            }

            for (final ThreadedRegion<R, S> region : this.expectingMergeFrom) {
                if (region.mergeIntoLater.remove(this) && region != mergeTarget) {
                    region.mergeIntoLater(mergeTarget);
                }
            }

            for (final ThreadedRegion<R, S> region : this.mergeIntoLater) {
                if (region.expectingMergeFrom.remove(this) && region != mergeTarget) {
                    mergeTarget.mergeIntoLater(region);
                }
            }

            if (this.data != null) {
                this.data.mergeInto(mergeTarget);
            }
        }

        private void mergeIntoLater(final ThreadedRegion<R, S> region) {
            if (region.isDead()) {
                throw new IllegalStateException("Trying to merge later into a dead region: " + region);
            }
            this.mergeIntoLater.add(region);
            region.expectingMergeFrom.add(this);
        }

        private boolean tryKill() {
            switch (this.state) {
                case STATE_TRANSIENT:
                    this.state = STATE_DEAD;
                    this.onRemove(false);
                    return true;
                case STATE_READY:
                    this.state = STATE_DEAD;
                    this.onRemove(true);
                    return true;
                case STATE_TICKING:
                    return false;
                case STATE_DEAD:
                    throw new IllegalStateException("Already dead");
                default:
                    throw new IllegalStateException("Unknown state: " + this.state);
            }
        }

        private boolean isDead() {
            return this.state == STATE_DEAD;
        }

        private boolean isTicking() {
            return this.state == STATE_TICKING;
        }

        private void removeDeadSection(final ThreadedRegionSection<R, S> section) {
            this.deadSections.remove(section);
        }

        private void addDeadSection(final ThreadedRegionSection<R, S> section) {
            this.deadSections.add(section);
        }

        private void addSection(final ThreadedRegionSection<R, S> section) {
            if (section.getRegionPlain() != null) {
                throw new IllegalStateException("Section already has region");
            }
            if (this.sectionByKey.putIfAbsent(section.sectionKey, section) != null) {
                throw new IllegalStateException("Already have section " + section + ", mapped to " + this.sectionByKey.get(section.sectionKey));
            }
            section.setRegionRelease(this);
        }

        public R getData() {
            return this.data;
        }

        public boolean tryMarkTicking(final BooleanSupplier abort) {
            this.regioniser.acquireWriteLock();
            try {
                if (this.state != STATE_READY || abort.getAsBoolean()) {
                    return false;
                }
                if (!this.mergeIntoLater.isEmpty() || !this.expectingMergeFrom.isEmpty()) {
                    throw new IllegalStateException("Region " + this + " should not be ready");
                }
                this.state = STATE_TICKING;
                return true;
            } finally {
                this.regioniser.releaseWriteLock();
            }
        }

        public boolean markNotTicking() {
            this.regioniser.acquireWriteLock();
            try {
                if (this.state != STATE_TICKING) {
                    throw new IllegalStateException("Attempting to release non-locked state");
                }
                this.regioniser.onRegionRelease(this);
                return this.state == STATE_READY;
            } catch (final Throwable throwable) {
                LOGGER.error("Failed to release region " + this, throwable);
                SneakyThrow.sneaky(throwable);
                return false;
            } finally {
                this.regioniser.releaseWriteLock();
            }
        }

        @Override
        public String toString() {
            final StringBuilder ret = new StringBuilder(128);
            ret.append("ThreadedRegion{");
            ret.append("id=").append(this.id).append(',');
            ret.append("state=").append(this.state).append(',');
            ret.append("sectionCount=").append(this.sectionByKey.size()).append(',');
            ret.append("sections=[");
            for (final Iterator<ThreadedRegionSection<R, S>> iterator = this.sectionByKey.values().iterator(); iterator.hasNext();) {
                ret.append(iterator.next().toString());
                if (iterator.hasNext()) {
                    ret.append(',');
                }
            }
            ret.append(']');
            ret.append('}');
            return ret.toString();
        }
    }

    public static final class ThreadedRegionSection<R extends ThreadedRegionData<R, S>, S extends ThreadedRegionSectionData> {

        public final int sectionX;
        public final int sectionZ;
        public final long sectionKey;
        private final long[] chunksBitset;
        private int chunkCount;
        private int nonEmptyNeighbours;

        private ThreadedRegion<R, S> region;
        private static final VarHandle REGION_HANDLE = ConcurrentUtil.getVarHandle(ThreadedRegionSection.class, "region", ThreadedRegion.class);

        public final ThreadedRegionizer<R, S> regioniser;
        private final int regionChunkShift;
        private final int regionChunkMask;
        private final S data;

        private ThreadedRegion<R, S> getRegionPlain() {
            return (ThreadedRegion<R, S>)REGION_HANDLE.get(this);
        }

        private ThreadedRegion<R, S> getRegionAcquire() {
            return (ThreadedRegion<R, S>)REGION_HANDLE.getAcquire(this);
        }

        private void setRegionRelease(final ThreadedRegion<R, S> value) {
            REGION_HANDLE.setRelease(this, value);
        }

        private ThreadedRegionSection(final int sectionX, final int sectionZ, final ThreadedRegionizer<R, S> regioniser) {
            this.sectionX = sectionX;
            this.sectionZ = sectionZ;
            this.sectionKey = CoordinateUtils.getChunkKey(sectionX, sectionZ);
            this.chunksBitset = new long[Math.max(1, regioniser.regionSectionChunkSize * regioniser.regionSectionChunkSize / Long.SIZE)];
            this.regioniser = regioniser;
            this.regionChunkShift = regioniser.sectionChunkShift;
            this.regionChunkMask = regioniser.regionSectionChunkSize - 1;
            this.data = regioniser.callbacks.createNewSectionData(sectionX, sectionZ, this.regionChunkShift);
        }

        private ThreadedRegionSection(final int sectionX, final int sectionZ, final ThreadedRegionizer<R, S> regioniser, final int chunkXInit, final int chunkZInit) {
            this(sectionX, sectionZ, regioniser);
            final int initIndex = this.getChunkIndex(chunkXInit, chunkZInit);
            this.chunkCount = 1;
            this.chunksBitset[initIndex >>> 6] = 1L << (initIndex & (Long.SIZE - 1));
        }

        private ThreadedRegionSection(final int sectionX, final int sectionZ, final ThreadedRegionizer<R, S> regioniser, final int nonEmptyNeighbours) {
            this(sectionX, sectionZ, regioniser);
            this.nonEmptyNeighbours = nonEmptyNeighbours;
        }

        public LongArrayList getChunks() {
            final LongArrayList ret = new LongArrayList();
            if (this.chunkCount == 0) {
                return ret;
            }
            final int shift = this.regionChunkShift;
            final int mask = this.regionChunkMask;
            final int offsetX = this.sectionX << shift;
            final int offsetZ = this.sectionZ << shift;
            final long[] bitset = this.chunksBitset;
            for (int arrIdx = 0, arrLen = bitset.length; arrIdx < arrLen; ++arrIdx) {
                long value = bitset[arrIdx];
                for (int i = 0, bits = Long.bitCount(value); i < bits; ++i) {
                    final int valueIdx = Long.numberOfTrailingZeros(value);
                    value ^= ca.spottedleaf.concurrentutil.util.IntegerUtil.getTrailingBit(value);
                    final int idx = valueIdx | (arrIdx << 6);
                    final int localX = idx & mask;
                    final int localZ = (idx >>> shift) & mask;
                    ret.add(CoordinateUtils.getChunkKey(localX | offsetX, localZ | offsetZ));
                }
            }
            return ret;
        }

        private boolean isEmpty() {
            return this.chunkCount == 0;
        }

        private boolean hasOnlyOneChunk() {
            return this.chunkCount == 1;
        }

        public boolean hasNonEmptyNeighbours() {
            return this.nonEmptyNeighbours != 0;
        }

        public S getData() {
            return this.data;
        }

        public ThreadedRegion<R, S> getRegion() {
            return this.getRegionAcquire();
        }

        private int getChunkIndex(final int chunkX, final int chunkZ) {
            return (chunkX & this.regionChunkMask) | ((chunkZ & this.regionChunkMask) << this.regionChunkShift);
        }

        private void markAlive() {
            this.getRegionPlain().removeDeadSection(this);
        }

        private void markDead() {
            this.getRegionPlain().addDeadSection(this);
        }

        private void incrementNonEmptyNeighbours() {
            if (++this.nonEmptyNeighbours == 1 && this.chunkCount == 0) {
                this.markAlive();
            }
            final int createRadius = this.regioniser.emptySectionCreateRadius;
            if (this.nonEmptyNeighbours >= ((createRadius * 2 + 1) * (createRadius * 2 + 1))) {
                throw new IllegalStateException("Non empty neighbours exceeded max value for radius " + createRadius);
            }
        }

        private void decrementNonEmptyNeighbours() {
            if (--this.nonEmptyNeighbours == 0 && this.chunkCount == 0) {
                this.markDead();
            }
            if (this.nonEmptyNeighbours < 0) {
                throw new IllegalStateException("Non empty neighbours reached zero");
            }
        }

        private void addChunk(final int chunkX, final int chunkZ) {
            final int index = this.getChunkIndex(chunkX, chunkZ);
            final long bitset = this.chunksBitset[index >>> 6];
            final long after = this.chunksBitset[index >>> 6] = bitset | (1L << (index & (Long.SIZE - 1)));
            if (after == bitset) {
                throw new IllegalStateException("Cannot add a chunk to a section which already has the chunk! RegionSection: " + this + ", global chunk: " + new ChunkPos(chunkX, chunkZ).toString());
            }
            final boolean notEmpty = ++this.chunkCount == 1;
            if (notEmpty && this.nonEmptyNeighbours == 0) {
                this.markAlive();
            }
        }

        private void removeChunk(final int chunkX, final int chunkZ) {
            final int index = this.getChunkIndex(chunkX, chunkZ);
            final long before = this.chunksBitset[index >>> 6];
            final long bitset = this.chunksBitset[index >>> 6] = before & ~(1L << (index & (Long.SIZE - 1)));
            if (before == bitset) {
                throw new IllegalStateException("Cannot remove a chunk from a section which does not have that chunk! RegionSection: " + this + ", global chunk: " + new ChunkPos(chunkX, chunkZ).toString());
            }
            final boolean empty = --this.chunkCount == 0;
            if (empty && this.nonEmptyNeighbours == 0) {
                this.markDead();
            }
        }

        @Override
        public String toString() {
            return "RegionSection{" +
                "sectionCoordinate=" + new ChunkPos(this.sectionX, this.sectionZ).toString() + "," +
                "chunkCount=" + this.chunkCount + "," +
                "nonEmptyNeighbours=" + this.nonEmptyNeighbours +
                "}";
        }

        public String toStringWithRegion() {
            return this.toString() + ",region=" + this.getRegionAcquire();
        }
    }

    public interface ThreadedRegionData<R extends ThreadedRegionData<R, S>, S extends ThreadedRegionSectionData> {
        void split(final ThreadedRegionizer<R, S> regioniser, final Long2ReferenceOpenHashMap<ThreadedRegion<R, S>> into,
                   final ReferenceOpenHashSet<ThreadedRegion<R, S>> regions);
        void mergeInto(final ThreadedRegion<R, S> into);
    }

    public interface ThreadedRegionSectionData {}

    public interface RegionCallbacks<R extends ThreadedRegionData<R, S>, S extends ThreadedRegionSectionData> {
        S createNewSectionData(final int sectionX, final int sectionZ, final int sectionShift);
        R createNewData(final ThreadedRegion<R, S> forRegion);
        void onRegionCreate(final ThreadedRegion<R, S> region);
        void onRegionDestroy(final ThreadedRegion<R, S> region);
        void onRegionActive(final ThreadedRegion<R, S> region);
        void onRegionInactive(final ThreadedRegion<R, S> region);
        void preMerge(final ThreadedRegion<R, S> from, final ThreadedRegion<R, S> into);
        void preSplit(final ThreadedRegion<R, S> from, final List<ThreadedRegion<R, S>> into);
    }
}
