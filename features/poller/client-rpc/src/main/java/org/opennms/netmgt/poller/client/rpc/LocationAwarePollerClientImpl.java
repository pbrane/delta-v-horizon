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
package org.opennms.netmgt.poller.client.rpc;

import java.util.Objects;

import org.opennms.core.rpc.api.RpcClient;
import org.opennms.core.rpc.api.RpcClientFactory;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.core.rpc.utils.RpcTargetHelper;
import org.opennms.netmgt.poller.LocationAwarePollerClient;
import org.opennms.netmgt.poller.PollerRequestBuilder;
import org.opennms.netmgt.poller.ServiceMonitorRegistry;
import org.springframework.beans.factory.InitializingBean;

public class LocationAwarePollerClientImpl implements LocationAwarePollerClient, InitializingBean {

    private final ServiceMonitorRegistry registry;
    private final PollerClientRpcModule pollerClientRpcModule;
    private final RpcClientFactory rpcClientFactory;
    private final RpcTargetHelper rpcTargetHelper;
    private final EntityScopeProvider entityScopeProvider;

    private RpcClient<PollerRequestDTO, PollerResponseDTO> delegate;

    public LocationAwarePollerClientImpl(ServiceMonitorRegistry registry,
                                         PollerClientRpcModule pollerClientRpcModule,
                                         RpcClientFactory rpcClientFactory,
                                         RpcTargetHelper rpcTargetHelper,
                                         EntityScopeProvider entityScopeProvider) {
        this.registry = Objects.requireNonNull(registry);
        this.pollerClientRpcModule = Objects.requireNonNull(pollerClientRpcModule);
        this.rpcClientFactory = Objects.requireNonNull(rpcClientFactory);
        this.rpcTargetHelper = Objects.requireNonNull(rpcTargetHelper);
        this.entityScopeProvider = Objects.requireNonNull(entityScopeProvider);
    }

    @Override
    public void afterPropertiesSet() {
        delegate = rpcClientFactory.getClient(pollerClientRpcModule);
    }

    protected RpcClient<PollerRequestDTO, PollerResponseDTO> getDelegate() {
        return delegate;
    }

    @Override
    public PollerRequestBuilder poll() {
        return new PollerRequestBuilderImpl(this);
    }

    public ServiceMonitorRegistry getRegistry() {
        return registry;
    }

    public RpcTargetHelper getRpcTargetHelper() {
        return rpcTargetHelper;
    }

    public EntityScopeProvider getEntityScopeProvider() {
        return this.entityScopeProvider;
    }
}
