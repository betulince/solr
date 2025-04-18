/*
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
package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.solr.client.solrj.impl.SolrZkClientTimeout.SolrZkClientTimeoutAware;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocRouter;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;

/**
 * SolrJ client class to communicate with SolrCloud. Instances of this class communicate with
 * Zookeeper to discover Solr endpoints for SolrCloud collections, and then use the {@link
 * LBHttpSolrClient} to issue requests.
 *
 * @deprecated Please use {@link CloudSolrClient}
 */
@Deprecated(since = "9.0")
public class CloudLegacySolrClient extends CloudSolrClient {

  private final ClusterStateProvider stateProvider;
  private final LBHttpSolrClient lbClient;
  private final boolean shutdownLBHttpSolrServer;
  private HttpClient myClient;
  private final boolean clientIsInternal;

  public static final String STATE_VERSION = CloudSolrClient.STATE_VERSION;

  /**
   * Create a new client object that connects to Zookeeper and is always aware of the SolrCloud
   * state. If there is a fully redundant Zookeeper quorum and SolrCloud has enough replicas for
   * every shard in a collection, there is no single point of failure. Updates will be sent to shard
   * leaders by default.
   *
   * @param builder a {@link Builder} with the options used to create the client.
   */
  protected CloudLegacySolrClient(Builder builder) {
    super(builder.shardLeadersOnly, builder.parallelUpdates, builder.directUpdatesToLeadersOnly);
    this.stateProvider = builder.stateProvider;
    this.retryExpiryTimeNano = builder.retryExpiryTimeNano;
    this.defaultCollection = builder.defaultCollection;
    this.collectionStateCache.timeToLiveMs =
        TimeUnit.MILLISECONDS.convert(builder.timeToLiveSeconds, TimeUnit.SECONDS);
    this.clientIsInternal = builder.httpClient == null;
    this.shutdownLBHttpSolrServer = builder.loadBalancedSolrClient == null;
    if (builder.lbClientBuilder != null) {
      propagateLBClientConfigOptions(builder);
      builder.loadBalancedSolrClient = builder.lbClientBuilder.build();
    }
    if (builder.loadBalancedSolrClient != null)
      builder.httpClient = builder.loadBalancedSolrClient.getHttpClient();
    this.myClient =
        (builder.httpClient == null) ? HttpClientUtil.createClient(null) : builder.httpClient;
    if (builder.loadBalancedSolrClient == null)
      builder.loadBalancedSolrClient = createLBHttpSolrClient(builder, myClient);
    this.lbClient = builder.loadBalancedSolrClient;
  }

  private void propagateLBClientConfigOptions(Builder builder) {
    final LBHttpSolrClient.Builder lbBuilder = builder.lbClientBuilder;
    lbBuilder.withConnectionTimeout(builder.connectionTimeoutMillis, TimeUnit.MILLISECONDS);
    lbBuilder.withSocketTimeout(builder.socketTimeoutMillis, TimeUnit.MILLISECONDS);
  }

  @Override
  protected Map<String, LBSolrClient.Req> createRoutes(
      UpdateRequest updateRequest,
      ModifiableSolrParams routableParams,
      DocCollection col,
      DocRouter router,
      Map<String, List<String>> urlMap,
      String idField) {
    return urlMap == null
        ? null
        : updateRequest.getRoutesToCollection(router, col, urlMap, routableParams, idField);
  }

  @Override
  protected RouteException getRouteException(
      SolrException.ErrorCode serverError,
      NamedList<Throwable> exceptions,
      Map<String, ? extends LBSolrClient.Req> routes) {
    return new RouteException(serverError, exceptions, routes);
  }

  @Override
  public void close() throws IOException {
    stateProvider.close();

    if (shutdownLBHttpSolrServer) {
      lbClient.close();
    }

    if (clientIsInternal && myClient != null) {
      HttpClientUtil.close(myClient);
    }

    super.close();
  }

  @Override
  public LBHttpSolrClient getLbClient() {
    return lbClient;
  }

  public HttpClient getHttpClient() {
    return myClient;
  }

  @Override
  public ClusterStateProvider getClusterStateProvider() {
    return stateProvider;
  }

  @Override
  protected boolean wasCommError(Throwable rootCause) {
    return rootCause instanceof ConnectTimeoutException
        || rootCause instanceof NoHttpResponseException;
  }

  private static LBHttpSolrClient createLBHttpSolrClient(
      Builder cloudSolrClientBuilder, HttpClient httpClient) {
    final LBHttpSolrClient.Builder lbBuilder = new LBHttpSolrClient.Builder();
    lbBuilder.withHttpClient(httpClient);
    lbBuilder.withConnectionTimeout(
        cloudSolrClientBuilder.connectionTimeoutMillis, TimeUnit.MILLISECONDS);
    lbBuilder.withSocketTimeout(cloudSolrClientBuilder.socketTimeoutMillis, TimeUnit.MILLISECONDS);
    lbBuilder.withRequestWriter(new JavaBinRequestWriter());
    lbBuilder.withResponseParser(new JavaBinResponseParser());

    return lbBuilder.build();
  }

  /** Constructs {@link CloudLegacySolrClient} instances from provided configuration. */
  public static class Builder extends SolrClientBuilder<Builder> {
    protected Collection<String> zkHosts = new ArrayList<>();
    protected List<String> solrUrls = new ArrayList<>();
    protected String zkChroot;
    protected LBHttpSolrClient loadBalancedSolrClient;
    protected LBHttpSolrClient.Builder lbClientBuilder;
    protected boolean shardLeadersOnly = true;
    protected boolean directUpdatesToLeadersOnly = false;
    protected boolean parallelUpdates = true;
    protected long retryExpiryTimeNano =
        TimeUnit.NANOSECONDS.convert(3, TimeUnit.SECONDS); // 3 seconds or 3 million nanos
    protected ClusterStateProvider stateProvider;
    private int zkConnectTimeout = SolrZkClientTimeout.DEFAULT_ZK_CONNECT_TIMEOUT;
    private int zkClientTimeout = SolrZkClientTimeout.DEFAULT_ZK_CLIENT_TIMEOUT;
    private boolean canUseZkACLs = true;

    /** Constructor for use by subclasses. This constructor was public prior to version 9.0 */
    protected Builder() {}

    /**
     * Provide a series of Solr URLs to be used when configuring {@link CloudLegacySolrClient}
     * instances. The solr client will use these urls to understand the cluster topology, which solr
     * nodes are active etc.
     *
     * <p>Provided Solr URLs are expected to point to the root Solr path
     * ("http://hostname:8983/solr"); they should not include any collections, cores, or other path
     * components.
     *
     * <p>Usage example:
     *
     * <pre>
     *   final List&lt;String&gt; solrBaseUrls = new ArrayList&lt;String&gt;();
     *   solrBaseUrls.add("http://solr1:8983/solr"); solrBaseUrls.add("http://solr2:8983/solr"); solrBaseUrls.add("http://solr3:8983/solr");
     *   final SolrClient client = new CloudSolrClient.Builder(solrBaseUrls).build();
     * </pre>
     */
    public Builder(List<String> solrUrls) {
      this.solrUrls = solrUrls;
    }

    /** Provide an already created {@link ClusterStateProvider} instance */
    public Builder(ClusterStateProvider stateProvider) {
      this.stateProvider = stateProvider;
    }

    /**
     * Provide a series of ZK hosts which will be used when configuring {@link
     * CloudLegacySolrClient} instances.
     *
     * <p>Usage example when Solr stores data at the ZooKeeper root ('/'):
     *
     * <pre>
     *   final List&lt;String&gt; zkServers = new ArrayList&lt;String&gt;();
     *   zkServers.add("zookeeper1:2181"); zkServers.add("zookeeper2:2181"); zkServers.add("zookeeper3:2181");
     *   final SolrClient client = new CloudSolrClient.Builder(zkServers, Optional.empty()).build();
     * </pre>
     *
     * Usage example when Solr data is stored in a ZooKeeper chroot:
     *
     * <pre>
     *    final List&lt;String&gt; zkServers = new ArrayList&lt;String&gt;();
     *    zkServers.add("zookeeper1:2181"); zkServers.add("zookeeper2:2181"); zkServers.add("zookeeper3:2181");
     *    final SolrClient client = new CloudSolrClient.Builder(zkServers, Optional.of("/solr")).build();
     *  </pre>
     *
     * @param zkHosts a List of at least one ZooKeeper host and port (e.g. "zookeeper1:2181")
     * @param zkChroot the path to the root ZooKeeper node containing Solr data. Provide {@code
     *     java.util.Optional.empty()} if no ZK chroot is used.
     */
    public Builder(List<String> zkHosts, Optional<String> zkChroot) {
      this.zkHosts = zkHosts;
      if (zkChroot.isPresent()) this.zkChroot = zkChroot.get();
    }

    /** Whether to use the default ZK ACLs when building a ZK Client. */
    public Builder canUseZkACLs(boolean canUseZkACLs) {
      this.canUseZkACLs = canUseZkACLs;
      return this;
    }

    /** Provides a {@link HttpClient} for the builder to use when creating clients. */
    public Builder withLBHttpSolrClientBuilder(LBHttpSolrClient.Builder lbHttpSolrClientBuilder) {
      this.lbClientBuilder = lbHttpSolrClientBuilder;
      return this;
    }

    /** Provides a {@link LBHttpSolrClient} for the builder to use when creating clients. */
    public Builder withLBHttpSolrClient(LBHttpSolrClient loadBalancedSolrClient) {
      this.loadBalancedSolrClient = loadBalancedSolrClient;
      return this;
    }

    /**
     * Sets the cache ttl for DocCollection Objects cached.
     *
     * @param seconds ttl value in seconds
     */
    public Builder withCollectionCacheTtl(int seconds) {
      assert seconds > 0;
      this.timeToLiveSeconds = seconds;
      return this;
    }

    /**
     * Tells {@link Builder} that created clients should be configured such that {@link
     * CloudSolrClient#isUpdatesToLeaders} returns <code>true</code>.
     *
     * @see #sendUpdatesToAnyReplica
     * @see CloudSolrClient#isUpdatesToLeaders
     */
    public Builder sendUpdatesOnlyToShardLeaders() {
      shardLeadersOnly = true;
      return this;
    }

    /**
     * Tells {@link Builder} that created clients should be configured such that {@link
     * CloudSolrClient#isUpdatesToLeaders} returns <code>false</code>.
     *
     * @see #sendUpdatesOnlyToShardLeaders
     * @see CloudSolrClient#isUpdatesToLeaders
     */
    public Builder sendUpdatesToAnyReplica() {
      shardLeadersOnly = false;
      return this;
    }

    /**
     * This method has no effect.
     *
     * <p>In older versions of Solr, this method was an incorrectly named equivalent to {@link
     * #sendUpdatesToAnyReplica}, which had no effect because that setting was ignored in the
     * created clients. When the underlying {@link CloudSolrClient} behavior was fixed, this method
     * was modified to be an explicit No-Op, since the implied behavior of sending updates to
     * <em>all</em> replicas has never been supported, and was never intended to be supported.
     *
     * @see #sendUpdatesOnlyToShardLeaders
     * @see #sendUpdatesToAnyReplica
     * @see CloudSolrClient#isUpdatesToLeaders
     * @see <a href="https://issues.apache.org/jira/browse/SOLR-6312">SOLR-6312</a>
     * @deprecated Never supported
     */
    @Deprecated
    public Builder sendUpdatesToAllReplicasInShard() {
      return this;
    }

    /**
     * Tells {@link Builder} that created clients should send direct updates to shard leaders only.
     *
     * <p>UpdateRequests whose leaders cannot be found will "fail fast" on the client side with a
     * {@link SolrException}
     *
     * @see #sendDirectUpdatesToAnyShardReplica
     * @see CloudSolrClient#isDirectUpdatesToLeadersOnly
     */
    public Builder sendDirectUpdatesToShardLeadersOnly() {
      directUpdatesToLeadersOnly = true;
      return this;
    }

    /**
     * Tells {@link Builder} that created clients can send updates to any shard replica (shard
     * leaders and non-leaders).
     *
     * <p>Shard leaders are still preferred, but the created clients will fall back to using other
     * replicas if a leader cannot be found.
     *
     * @see #sendDirectUpdatesToShardLeadersOnly
     * @see CloudSolrClient#isDirectUpdatesToLeadersOnly
     */
    public Builder sendDirectUpdatesToAnyShardReplica() {
      directUpdatesToLeadersOnly = false;
      return this;
    }

    /**
     * Tells {@link Builder} whether created clients should send shard updates serially or in
     * parallel
     *
     * <p>When an {@link UpdateRequest} affects multiple shards, {@link CloudLegacySolrClient}
     * splits it up and sends a request to each affected shard. This setting chooses whether those
     * sub-requests are sent serially or in parallel.
     *
     * <p>If not set, this defaults to 'true' and sends sub-requests in parallel.
     */
    public Builder withParallelUpdates(boolean parallelUpdates) {
      this.parallelUpdates = parallelUpdates;
      return this;
    }

    /**
     * Sets the Zk connection timeout
     *
     * @param zkConnectTimeout timeout value
     * @param unit time unit
     */
    public Builder withZkConnectTimeout(int zkConnectTimeout, TimeUnit unit) {
      this.zkConnectTimeout = Math.toIntExact(unit.toMillis(zkConnectTimeout));
      return this;
    }

    /**
     * Sets the Zk client session timeout
     *
     * @param zkClientTimeout timeout value
     * @param unit time unit
     */
    public Builder withZkClientTimeout(int zkClientTimeout, TimeUnit unit) {
      this.zkClientTimeout = Math.toIntExact(unit.toMillis(zkClientTimeout));
      return this;
    }

    /** Create a {@link CloudLegacySolrClient} based on the provided configuration. */
    public CloudLegacySolrClient build() {
      if (stateProvider == null) {
        if (!zkHosts.isEmpty() && !solrUrls.isEmpty()) {
          throw new IllegalArgumentException(
              "Both zkHost(s) & solrUrl(s) have been specified. Only specify one.");
        } else if (!zkHosts.isEmpty()) {
          this.stateProvider =
              ClusterStateProvider.newZkClusterStateProvider(zkHosts, zkChroot, canUseZkACLs);
          if (stateProvider instanceof SolrZkClientTimeoutAware timeoutAware) {
            timeoutAware.setZkClientTimeout(zkClientTimeout);
            timeoutAware.setZkConnectTimeout(zkConnectTimeout);
          }
        } else if (!this.solrUrls.isEmpty()) {
          try {
            stateProvider = new HttpClusterStateProvider(solrUrls, httpClient);
          } catch (Exception e) {
            throw new RuntimeException(
                "Couldn't initialize a HttpClusterStateProvider (is/are the "
                    + "Solr server(s), "
                    + solrUrls
                    + ", down?)",
                e);
          }
        } else {
          throw new IllegalArgumentException("Both zkHosts and solrUrl cannot be null.");
        }
      }
      return new CloudLegacySolrClient(this);
    }

    @Override
    public Builder getThis() {
      return this;
    }
  }
}
