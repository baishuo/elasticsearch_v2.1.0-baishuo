/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.support.single.instance;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.replication.ClusterStateCreationUtils;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.cluster.TestClusterService;
import org.elasticsearch.test.transport.CapturingTransport;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.ConnectTransportException;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportService;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.core.IsEqual.equalTo;

public class TransportInstanceSingleOperationActionTests extends ESTestCase {

    private static ThreadPool THREAD_POOL;

    private TestClusterService clusterService;
    private CapturingTransport transport;
    private TransportService transportService;

    private TestTransportInstanceSingleOperationAction action;

    public static class Request extends InstanceShardOperationRequest<Request> {
        public Request() {
        }
    }

    public static class Response extends ActionResponse {
        public Response() {
        }
    }

    class TestTransportInstanceSingleOperationAction extends TransportInstanceSingleOperationAction<Request, Response> {
        private final Map<ShardId, Object> shards = new HashMap<>();

        public TestTransportInstanceSingleOperationAction(Settings settings, String actionName, TransportService transportService, ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver, Class<Request> request) {
            super(settings, actionName, THREAD_POOL, TransportInstanceSingleOperationActionTests.this.clusterService, transportService, actionFilters, indexNameExpressionResolver, request);
        }

        public Map<ShardId, Object> getResults() {
            return shards;
        }

        @Override
        protected String executor() {
            return ThreadPool.Names.SAME;
        }

        @Override
        protected void shardOperation(Request request, ActionListener<Response> listener) {
            throw new UnsupportedOperationException("Not implemented in test class");
        }

        @Override
        protected Response newResponse() {
            return new Response();
        }

        @Override
        protected boolean resolveRequest(ClusterState state, Request request, ActionListener<Response> listener) {
            return true;
        }

        @Override
        protected ShardIterator shards(ClusterState clusterState, Request request) {
            return clusterState.routingTable().index(request.concreteIndex()).shard(request.shardId).primaryShardIt();
        }
    }

    class MyResolver extends IndexNameExpressionResolver {
        public MyResolver() {
            super(Settings.EMPTY);
        }

        @Override
        public String[] concreteIndices(ClusterState state, IndicesRequest request) {
            return request.indices();
        }
    }

    @BeforeClass
    public static void startThreadPool() {
        THREAD_POOL = new ThreadPool(TransportInstanceSingleOperationActionTests.class.getSimpleName());
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        transport = new CapturingTransport();
        clusterService = new TestClusterService(THREAD_POOL);
        transportService = new TransportService(transport, THREAD_POOL);
        transportService.start();
        action = new TestTransportInstanceSingleOperationAction(
                Settings.EMPTY,
                "indices:admin/test",
                transportService,
                new ActionFilters(new HashSet<ActionFilter>()),
                new MyResolver(),
                Request.class
        );
    }

    @AfterClass
    public static void destroyThreadPool() {
        ThreadPool.terminate(THREAD_POOL, 30, TimeUnit.SECONDS);
        // since static must set to null to be eligible for collection
        THREAD_POOL = null;
    }

    public void testGlobalBlock() {
        Request request = new Request();
        PlainActionFuture<Response> listener = new PlainActionFuture<>();
        ClusterBlocks.Builder block = ClusterBlocks.builder()
                .addGlobalBlock(new ClusterBlock(1, "", false, true, RestStatus.SERVICE_UNAVAILABLE, ClusterBlockLevel.ALL));
        clusterService.setState(ClusterState.builder(clusterService.state()).blocks(block));
        try {
            action.new AsyncSingleAction(request, listener).start();
            listener.get();
            fail("expected ClusterBlockException");
        } catch (Throwable t) {
            if (ExceptionsHelper.unwrap(t, ClusterBlockException.class) == null) {
                logger.info("expected ClusterBlockException  but got ", t);
                fail("expected ClusterBlockException");
            }
        }
    }

    public void testBasicRequestWorks() throws InterruptedException, ExecutionException, TimeoutException {
        Request request = new Request().index("test");
        request.shardId = 0;
        PlainActionFuture<Response> listener = new PlainActionFuture<>();
        clusterService.setState(ClusterStateCreationUtils.state("test", randomBoolean(), ShardRoutingState.STARTED));
        action.new AsyncSingleAction(request, listener).start();
        assertThat(transport.capturedRequests().length, equalTo(1));
        transport.handleResponse(transport.capturedRequests()[0].requestId, new Response());
        listener.get();
    }

    public void testFailureWithoutRetry() throws Exception {
        Request request = new Request().index("test");
        request.shardId = 0;
        PlainActionFuture<Response> listener = new PlainActionFuture<>();
        clusterService.setState(ClusterStateCreationUtils.state("test", randomBoolean(), ShardRoutingState.STARTED));

        action.new AsyncSingleAction(request, listener).start();
        assertThat(transport.capturedRequests().length, equalTo(1));
        long requestId = transport.capturedRequests()[0].requestId;
        transport.clear();
        // this should not trigger retry or anything and the listener should report exception immediately
        transport.handleResponse(requestId, new TransportException("a generic transport exception", new Exception("generic test exception")));

        try {
            // result should return immediately
            assertTrue(listener.isDone());
            listener.get();
            fail("this should fail with a transport exception");
        } catch (ExecutionException t) {
            if (ExceptionsHelper.unwrap(t, TransportException.class) == null) {
                logger.info("expected TransportException  but got ", t);
                fail("expected and TransportException");
            }
        }
    }

    public void testSuccessAfterRetryWithClusterStateUpdate() throws Exception {
        Request request = new Request().index("test");
        request.shardId = 0;
        PlainActionFuture<Response> listener = new PlainActionFuture<>();
        boolean local = randomBoolean();
        clusterService.setState(ClusterStateCreationUtils.state("test", local, ShardRoutingState.INITIALIZING));
        action.new AsyncSingleAction(request, listener).start();
        // this should fail because primary not initialized
        assertThat(transport.capturedRequests().length, equalTo(0));
        clusterService.setState(ClusterStateCreationUtils.state("test", local, ShardRoutingState.STARTED));
        // this time it should work
        assertThat(transport.capturedRequests().length, equalTo(1));
        transport.handleResponse(transport.capturedRequests()[0].requestId, new Response());
        listener.get();
    }

    public void testSuccessAfterRetryWithExcpetionFromTransport() throws Exception {
        Request request = new Request().index("test");
        request.shardId = 0;
        PlainActionFuture<Response> listener = new PlainActionFuture<>();
        boolean local = randomBoolean();
        clusterService.setState(ClusterStateCreationUtils.state("test", local, ShardRoutingState.STARTED));
        action.new AsyncSingleAction(request, listener).start();
        assertThat(transport.capturedRequests().length, equalTo(1));
        long requestId = transport.capturedRequests()[0].requestId;
        transport.clear();
        DiscoveryNode node = clusterService.state().getNodes().getLocalNode();
        transport.handleResponse(requestId, new ConnectTransportException(node, "test exception"));
        // trigger cluster state observer
        clusterService.setState(ClusterStateCreationUtils.state("test", local, ShardRoutingState.STARTED));
        assertThat(transport.capturedRequests().length, equalTo(1));
        transport.handleResponse(transport.capturedRequests()[0].requestId, new Response());
        listener.get();
    }

    public void testRetryOfAnAlreadyTimedOutRequest() throws Exception {
        Request request = new Request().index("test").timeout(new TimeValue(0, TimeUnit.MILLISECONDS));
        request.shardId = 0;
        PlainActionFuture<Response> listener = new PlainActionFuture<>();
        clusterService.setState(ClusterStateCreationUtils.state("test", randomBoolean(), ShardRoutingState.STARTED));
        action.new AsyncSingleAction(request, listener).start();
        assertThat(transport.capturedRequests().length, equalTo(1));
        long requestId = transport.capturedRequests()[0].requestId;
        transport.clear();
        DiscoveryNode node = clusterService.state().getNodes().getLocalNode();
        transport.handleResponse(requestId, new ConnectTransportException(node, "test exception"));

        // wait until the timeout was triggered and we actually tried to send for the second time
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertThat(transport.capturedRequests().length, equalTo(1));
            }
        });

        // let it fail the second time too
        requestId = transport.capturedRequests()[0].requestId;
        transport.handleResponse(requestId, new ConnectTransportException(node, "test exception"));
        try {
            // result should return immediately
            assertTrue(listener.isDone());
            listener.get();
            fail("this should fail with a transport exception");
        } catch (ExecutionException t) {
            if (ExceptionsHelper.unwrap(t, ConnectTransportException.class) == null) {
                logger.info("expected ConnectTransportException  but got ", t);
                fail("expected and ConnectTransportException");
            }
        }
    }

    public void testUnresolvableRequestDoesNotHang() throws InterruptedException, ExecutionException, TimeoutException {
        action = new TestTransportInstanceSingleOperationAction(
                Settings.EMPTY,
                "indices:admin/test_unresolvable",
                transportService,
                new ActionFilters(new HashSet<ActionFilter>()),
                new MyResolver(),
                Request.class
        ) {
            @Override
            protected boolean resolveRequest(ClusterState state, Request request, ActionListener<Response> listener) {
                return false;
            }
        };
        Request request = new Request().index("test");
        request.shardId = 0;
        PlainActionFuture<Response> listener = new PlainActionFuture<>();
        clusterService.setState(ClusterStateCreationUtils.state("test", randomBoolean(), ShardRoutingState.STARTED));
        action.new AsyncSingleAction(request, listener).start();
        assertThat(transport.capturedRequests().length, equalTo(0));
        try {
            listener.get();
        } catch (Throwable t) {
            if (ExceptionsHelper.unwrap(t, IllegalStateException.class) == null) {
                logger.info("expected IllegalStateException  but got ", t);
                fail("expected and IllegalStateException");
            }
        }
    }
}