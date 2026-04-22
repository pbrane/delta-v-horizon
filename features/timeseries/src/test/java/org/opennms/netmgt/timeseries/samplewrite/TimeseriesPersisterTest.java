/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.timeseries.samplewrite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.opennms.core.cache.Cache;
import org.opennms.integration.api.v1.timeseries.Tag;
import org.opennms.netmgt.collection.api.CollectionAttribute;
import org.opennms.netmgt.collection.api.CollectionAttributeType;
import org.opennms.netmgt.collection.api.CollectionResource;
import org.opennms.netmgt.collection.api.ServiceParameters;
import org.opennms.netmgt.model.ResourcePath;
import org.opennms.netmgt.rrd.RrdRepository;

import com.codahale.metrics.MetricRegistry;

/**
 * Regression tests for the three failure modes previously reported as
 * "Phase 0 horizon inner-persister bugs":
 * <ul>
 *   <li>{@code visitResource} swallowing transaction/loader failures from
 *       {@link MetaTagDataLoader#load(CollectionResource)} instead of
 *       letting them abort the visit chain.</li>
 *   <li>{@code getUserDefinedMetaTags} using {@code getIfCached} on cache
 *       miss rather than invoking the type-erased {@link MetaTagDataLoader}
 *       CacheLoader (which would ClassCastException because the loader is
 *       typed on {@link CollectionResource} while the cache is keyed by
 *       {@link ResourcePath}).</li>
 *   <li>{@code persistNumericAttribute}/{@code persistStringAttribute}
 *       tolerating a null {@code currentBuilder} that results when
 *       {@code visitGroup} was skipped or threw.</li>
 * </ul>
 */
public class TimeseriesPersisterTest {

    private TimeseriesWriter writer;
    private MetaTagDataLoader loader;
    @SuppressWarnings("unchecked")
    private Cache<ResourcePath, Set<Tag>> cache = mock(Cache.class);
    private MetricRegistry metricRegistry;
    private RrdRepository repository;
    private ServiceParameters params;
    private TimeseriesPersister persister;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        writer = mock(TimeseriesWriter.class);
        loader = mock(MetaTagDataLoader.class);
        cache = mock(Cache.class);
        metricRegistry = new MetricRegistry();
        repository = new RrdRepository();
        params = new ServiceParameters(new HashMap<>());
        persister = new TimeseriesPersister(params, repository, writer, loader, cache, metricRegistry);
    }

    /**
     * Bug #1 regression. Spring marks the read-only tx rollback-only during
     * {@code MetaTagDataLoader.load}; the commit then throws
     * {@code UnexpectedRollbackException}. Prior behavior let this propagate
     * out of {@code visitResource}, aborting the rest of the resource's
     * visit chain. Fixed behavior: swallow, log, continue.
     */
    @Test
    public void visitResourceSwallowsLoaderRuntimeException() {
        CollectionResource resource = mock(CollectionResource.class);
        when(resource.getPath()).thenReturn(ResourcePath.get("node", "1"));
        when(resource.shouldPersist(any())).thenReturn(true);
        when(loader.load(resource)).thenThrow(new RuntimeException("simulated rollback"));

        persister.visitResource(resource); // must not throw

        verify(cache, never()).put(any(), any());
    }

    /**
     * Bug #4 regression. The cache key type is {@link ResourcePath} but the
     * underlying {@link MetaTagDataLoader} is typed on
     * {@link CollectionResource}; invoking the loader on a miss would
     * ClassCastException through the type-erased bridge method.
     * Fixed behavior: {@code getIfCached} returns null on miss; the
     * loader is never invoked from the persister's read path.
     */
    @Test
    public void visitGroupDoesNotInvokeLoaderOnCacheMiss() throws Exception {
        CollectionResource resource = mock(CollectionResource.class);
        when(resource.getPath()).thenReturn(ResourcePath.get("node", "1"));
        when(resource.shouldPersist(any())).thenReturn(true);
        when(loader.load(resource)).thenThrow(new RuntimeException("simulated rollback"));
        // Cache miss path: cache.getIfCached returns null.
        when(cache.getIfCached(any(ResourcePath.class))).thenReturn(null);

        org.opennms.netmgt.collection.api.AttributeGroup group =
                mock(org.opennms.netmgt.collection.api.AttributeGroup.class);
        when(group.getResource()).thenReturn(resource);
        when(group.getName()).thenReturn("mib2");
        when(group.shouldPersist(any())).thenReturn(true);

        persister.visitResource(resource); // swallows loader throw
        persister.visitGroup(group);       // reads cache via getIfCached

        // Key assertion: loader was invoked only from visitResource (and that
        // call threw), never from the cache-read path.
        verify(loader).load(resource);
        verify(cache, never()).get(any());
    }

    /**
     * Bug #2 regression. When {@code visitGroup} is skipped (shouldPersist
     * false) or threw before assigning {@code currentBuilder}, subsequent
     * {@code persistNumericAttribute} calls must not NPE.
     */
    @Test
    public void persistNumericAttributeDoesNotNpeWhenNoBuilder() {
        // currentBuilder is null (never assigned)
        CollectionAttribute attribute = mock(CollectionAttribute.class);
        CollectionAttributeType type = mock(CollectionAttributeType.class);
        when(attribute.getAttributeType()).thenReturn(type);

        persister.persistNumericAttribute(attribute); // must not throw
    }

    /**
     * Bug #2 regression, string path. Same condition as the numeric test
     * but exercising the string attribute persistence override.
     */
    @Test
    public void persistStringAttributeDoesNotNpeWhenNoBuilder() throws Exception {
        persister.persistStringAttribute(ResourcePath.get("node", "1"), "k", "v"); // must not throw
    }
}
