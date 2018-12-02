/*
 * Copyright 2015-2018 Austin Keener & Michael Ritter & Florian Spieß
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.dv8tion.jda.internal.utils.cache;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.utils.ClosableIterator;
import net.dv8tion.jda.core.utils.ClosableIteratorImpl;
import net.dv8tion.jda.core.utils.cache.CacheView;
import net.dv8tion.jda.core.utils.cache.ShardCacheView;
import net.dv8tion.jda.internal.utils.ChainedClosableIterator;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.UnlockHook;
import org.apache.commons.collections4.iterators.ObjectArrayIterator;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ShardCacheViewImpl extends ReadWriteLockCache implements ShardCacheView
{
    protected static final JDA[] EMPTY_ARRAY = new JDA[0];
    protected final TIntObjectMap<JDA> elements;

    public ShardCacheViewImpl()
    {
        this.elements = new TIntObjectHashMap<>();
    }

    public ShardCacheViewImpl(int initialCapacity)
    {
        this.elements = new TIntObjectHashMap<>(initialCapacity);
    }

    public void clear()
    {
        try (UnlockHook hook = writeLock())
        {
            elements.clear();
        }
    }

    public TIntObjectMap<JDA> getMap()
    {
        if (!lock.writeLock().isHeldByCurrentThread())
            throw new IllegalStateException("Cannot access map without holding write lock!");
        return elements;
    }

    public TIntSet keySet()
    {
        try (UnlockHook hook = readLock())
        {
            return new TIntHashSet(elements.keySet());
        }
    }

    @Override
    public void forEach(Consumer<? super JDA> action)
    {
        Objects.requireNonNull(action);
        try (UnlockHook hook = readLock())
        {
            for (JDA shard : elements.valueCollection())
            {
                action.accept(shard);
            }
        }
    }

    @Override
    public List<JDA> asList()
    {
        if (isEmpty())
            return Collections.emptyList();
        try (UnlockHook hook = readLock())
        {
            return Collections.unmodifiableList(new ArrayList<>(elements.valueCollection()));
        }
    }

    @Override
    public Set<JDA> asSet()
    {
        if (isEmpty())
            return Collections.emptySet();
        try (UnlockHook hook = readLock())
        {
            return Collections.unmodifiableSet(new HashSet<>(elements.valueCollection()));
        }
    }

    @Override
    public ClosableIteratorImpl<JDA> lockedIterator()
    {
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        readLock.lock();
        try
        {
            Iterator<JDA> directIterator = elements.valueCollection().iterator();
            return new ClosableIteratorImpl<>(directIterator, readLock);
        }
        catch (Throwable t)
        {
            readLock.unlock();
            throw t;
        }
    }

    @Override
    public long size()
    {
        return elements.size();
    }

    @Override
    public boolean isEmpty()
    {
        return elements.isEmpty();
    }

    @Override
    public List<JDA> getElementsByName(String name, boolean ignoreCase)
    {
        Checks.notEmpty(name, "Name");
        if (elements.isEmpty())
            return Collections.emptyList();

        try (UnlockHook hook = readLock())
        {
            List<JDA> list = new LinkedList<>();
            for (JDA elem : elements.valueCollection())
            {
                String elementName = elem.getShardInfo().getShardString();
                if (elementName != null)
                {
                    if (ignoreCase)
                    {
                        if (elementName.equalsIgnoreCase(name))
                            list.add(elem);
                    }
                    else
                    {
                        if (elementName.equals(name))
                            list.add(elem);
                    }
                }
            }

            return list;
        }
    }

    @Override
    public Spliterator<JDA> spliterator()
    {
        try (UnlockHook hook = readLock())
        {
            return Spliterators.spliterator(iterator(), size(), Spliterator.IMMUTABLE | Spliterator.NONNULL);
        }
    }

    @Override
    public Stream<JDA> stream()
    {
        return StreamSupport.stream(spliterator(), false);
    }

    @Override
    public Stream<JDA> parallelStream()
    {
        return StreamSupport.stream(spliterator(), true);
    }

    @Nonnull
    @Override
    public Iterator<JDA> iterator()
    {
        try (UnlockHook hook = readLock())
        {
            JDA[] arr = elements.values(EMPTY_ARRAY);
            return new ObjectArrayIterator<>(arr);
        }
    }

    @Override
    public JDA getElementById(int id)
    {
        try (UnlockHook hook = readLock())
        {
            return this.elements.get(id);
        }
    }

    public static class UnifiedShardCacheViewImpl implements ShardCacheView
    {
        protected final Supplier<Stream<ShardCacheView>> generator;

        public UnifiedShardCacheViewImpl(Supplier<Stream<ShardCacheView>> generator)
        {
            this.generator = generator;
        }

        @Override
        public long size()
        {
            return distinctStream().mapToLong(CacheView::size).sum();
        }

        @Override
        public boolean isEmpty()
        {
            return generator.get().allMatch(CacheView::isEmpty);
        }

        @Override
        public List<JDA> asList()
        {
            List<JDA> list = new ArrayList<>();
            stream().forEach(list::add);
            return Collections.unmodifiableList(list);
        }

        @Override
        public Set<JDA> asSet()
        {
            Set<JDA> set = new HashSet<>();
            generator.get().flatMap(CacheView::stream).forEach(set::add);
            return Collections.unmodifiableSet(set);
        }

        @Override
        public ClosableIterator<JDA> lockedIterator()
        {
            Iterator<ShardCacheView> gen = this.generator.get().iterator();
            return new ChainedClosableIterator<>(gen);
        }

        @Override
        public List<JDA> getElementsByName(String name, boolean ignoreCase)
        {
            return Collections.unmodifiableList(distinctStream()
                .flatMap(view -> view.getElementsByName(name, ignoreCase).stream())
                .collect(Collectors.toList()));
        }

        @Override
        public JDA getElementById(int id)
        {
            return generator.get()
                .map(view -> view.getElementById(id))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        }

        @Override
        public Stream<JDA> stream()
        {
            return generator.get().flatMap(CacheView::stream).distinct();
        }

        @Override
        public Stream<JDA> parallelStream()
        {
            return generator.get().flatMap(CacheView::parallelStream).distinct();
        }

        @Nonnull
        @Override
        public Iterator<JDA> iterator()
        {
            return stream().iterator();
        }

        protected Stream<ShardCacheView> distinctStream()
        {
            return generator.get().distinct();
        }
    }
}
