/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.jdbc;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkState;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

class ReferenceCountingLoadingCache<K, V>
{
    private static class Holder<V>
    {
        private final V value;
        private int refcount;
        private int cleanerCount;
        private ScheduledFuture currentCleanup;

        private Holder(V value)
        {
            this.value = requireNonNull(value, "value is null");
        }

        private V get()
        {
            return value;
        }

        private int getRefcount()
        {
            return refcount;
        }

        private int getCleanerCount()
        {
            return cleanerCount;
        }

        private int reference()
        {
            setCleanup(null);
            return ++refcount;
        }

        private int dereference()
        {
            return --refcount;
        }

        private void setCleanup(ScheduledFuture cleanup)
        {
            if (currentCleanup != null && currentCleanup.cancel(false)) {
                --cleanerCount;
            }

            checkState(cleanerCount >= 0, "Negative cleanerCount in setCleanup");

            currentCleanup = cleanup;
            if (cleanup != null) {
                ++cleanerCount;
            }
        }

        private void scheduleCleanup(
                ScheduledExecutorService cleanupService,
                Runnable cleanupTask,
                long delay,
                TimeUnit unit)
        {
            checkState(refcount == 0, "non-zero refcount in scheduleCleanup");
            ScheduledFuture cleanup = cleanupService.schedule(cleanupTask, delay, unit);
            setCleanup(cleanup);
        }

        private void releaseForCleaning()
        {
            --cleanerCount;
            checkState(cleanerCount >= 0, "Negative cleanerCount in releaseForCleaning");
        }
    }

    private final ScheduledExecutorService valueCleanupService;
    private final LoadingCache<K, Holder<V>> backingCache;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final long retentionPeriod;
    private final TimeUnit retentionUnit;

    protected ReferenceCountingLoadingCache(
            CacheLoader<K, V> loader,
            Consumer<V> disposer,
            long retentionPeriod,
            TimeUnit retentionUnit,
            ScheduledExecutorService valueCleanupService)
    {
        requireNonNull(loader, "loader is null");
        requireNonNull(disposer, "disposer is null");
        this.retentionPeriod = retentionPeriod;
        this.retentionUnit = requireNonNull(retentionUnit, "retentionUnit is null");

        this.valueCleanupService = requireNonNull(valueCleanupService, "valueCleanupService is null");
        this.backingCache = CacheBuilder.newBuilder()
                .removalListener(new RemovalListener<K, Holder<V>>() {
                    @Override
                    public void onRemoval(RemovalNotification<K, Holder<V>> notification)
                    {
                        Holder<V> holder = notification.getValue();
                        /*
                         * The docs say that both the key and value may be
                         * null if they've already been garbage collected.
                         * We aren't using weak or soft keys or values, so
                         * this shouldn't apply.
                         */
                        requireNonNull(holder, format("holder is null while removing key %s", notification.getKey()));

                        if (closed.get()) {
                            // Caller goofed.
                            checkState(holder.getRefcount() == 0, "Unreleased key %s on close", notification.getKey());
                        }
                        else {
                            // We goofed.
                            checkState(holder.getRefcount() == 0, "Non-zero refcount disposing %s", notification.getKey());
                            checkState(holder.getCleanerCount() == 0, "Non-zero cleaner count disposing %s", notification.getKey());
                        }

                        disposer.accept(holder.get());
                    }
                })
                .build(new CacheLoader<K, Holder<V>>() {
                    @Override
                    public Holder<V> load(K key)
                            throws Exception
                    {
                        return new Holder<>(loader.load(key));
                    }
                });
    }

    public static class Builder<K, V>
    {
        private ScheduledExecutorService valueCleanupService = new ScheduledThreadPoolExecutor(1, daemonThreadsNamed("cache-cleanup"));

        // TODO: This is probably on the long side. Maybe change to 30 seconds?
        private long retentionPeriod = 2;
        private TimeUnit retentionUnit = TimeUnit.MINUTES;

        public Builder<K, V> withRetentionTime(long retentionPeriod, TimeUnit retentionUnit)
        {
            this.retentionPeriod = retentionPeriod;
            this.retentionUnit = requireNonNull(retentionUnit, "retentionUnit is null");
            return this;
        }

        public Builder<K, V> withCleanupService(ScheduledExecutorService valueCleanupService)
        {
            this.valueCleanupService = requireNonNull(valueCleanupService, "valueCleanupService is null");
            return this;
        }

        public ReferenceCountingLoadingCache<K, V> build(CacheLoader<K, V> loader, Consumer<V> disposer)
        {
            return new ReferenceCountingLoadingCache<K, V>(loader, disposer, retentionPeriod, retentionUnit, valueCleanupService);
        }
    }

    public static <K, V> Builder<K, V> builder()
    {
        return new Builder<>();
    }

    public void close()
    {
        if (closed.compareAndSet(false, true)) {
            valueCleanupService.shutdownNow();
            backingCache.invalidateAll();
        }
    }

    public V acquire(K key)
    {
        checkState(!closed.get(), "Can't acquire from closed cache.");
        synchronized (this) {
            Holder<V> holder = backingCache.getUnchecked(key);
            holder.reference();
            return holder.get();
        }
    }

    public void release(K key)
    {
        checkState(!closed.get(), "Can't release to closed cache.");
        /*
         * Access through the Map interface to avoid creating a Holder and immediately
         * scheduling it for cleanup.
         */
        Holder holder = backingCache.asMap().get(key);
        if (holder == null) {
            return;
        }

        Runnable deferredRelease = () -> {
            synchronized (this) {
                holder.releaseForCleaning();
                if (holder.getCleanerCount() ==  0 && holder.getRefcount() == 0) {
                    backingCache.invalidate(key);
                }
            }
        };

        synchronized (this) {
            if (holder.dereference() == 0) {
                holder.scheduleCleanup(valueCleanupService, deferredRelease, retentionPeriod, retentionUnit);
            }
        }
    }

    public long getRetentionPeriod()
    {
        return retentionPeriod;
    }

    public TimeUnit getRetentionUnit()
    {
        return retentionUnit;
    }
}