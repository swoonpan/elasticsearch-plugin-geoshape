package com.opendatasoft.elasticsearch.action.geo;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.search.type.*;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.BaseTransportRequestHandler;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportService;

import java.util.Map;
import java.util.Set;

/**
 * Created with IntelliJ IDEA.
 * User: clement
 * Date: 08/12/14
 * Time: 17:20
 * To change this template use File | Settings | File Templates.
 */
public class TransportGeoSimpleAction extends TransportAction<GeoSimpleRequest, SearchResponse>{


    private final ClusterService clusterService;
    private final TransportSearchDfsQueryThenFetchAction dfsQueryThenFetchAction;
    private final TransportSearchQueryThenFetchAction queryThenFetchAction;
    private final TransportSearchDfsQueryAndFetchAction dfsQueryAndFetchAction;
    private final TransportSearchQueryAndFetchAction queryAndFetchAction;
    private final TransportSearchScanAction scanAction;
    private final TransportSearchCountAction countAction;
    private final boolean optimizeSingleShard;

    @Inject
    public TransportGeoSimpleAction(Settings settings, ThreadPool threadPool,
                                 TransportService transportService, ClusterService clusterService,
                                 TransportSearchDfsQueryThenFetchAction dfsQueryThenFetchAction,
                                 TransportSearchQueryThenFetchAction queryThenFetchAction,
                                 TransportSearchDfsQueryAndFetchAction dfsQueryAndFetchAction,
                                 TransportSearchQueryAndFetchAction queryAndFetchAction,
                                 TransportSearchScanAction scanAction,
                                 TransportSearchCountAction countAction, ActionFilters actionFilters) {
        super(settings, GeoSimpleAction.NAME, threadPool, actionFilters);
        this.clusterService = clusterService;
        this.dfsQueryThenFetchAction = dfsQueryThenFetchAction;
        this.queryThenFetchAction = queryThenFetchAction;
        this.dfsQueryAndFetchAction = dfsQueryAndFetchAction;
        this.queryAndFetchAction = queryAndFetchAction;
        this.scanAction = scanAction;
        this.countAction = countAction;

        this.optimizeSingleShard = componentSettings.getAsBoolean("optimize_single_shard", true);

        transportService.registerHandler(GeoSimpleAction.NAME, new TransportHandler());
    }


    @Override
    protected void doExecute(GeoSimpleRequest GeoSimpleRequest, ActionListener<SearchResponse> listener) {
        SearchRequest searchRequest = GeoSimpleRequest.getSearchRequest();
        // optimize search type for cases where there is only one shard group to search on
        if (optimizeSingleShard && searchRequest.searchType() != SearchType.SCAN && searchRequest.searchType() != SearchType.COUNT) {
            try {
                ClusterState clusterState = clusterService.state();
                String[] concreteIndices = clusterState.metaData().concreteIndices(searchRequest.indicesOptions(), searchRequest.indices());
                Map<String, Set<String>> routingMap = clusterState.metaData().resolveSearchRouting(searchRequest.routing(), searchRequest.indices());
                int shardCount = clusterService.operationRouting().searchShardsCount(clusterState, searchRequest.indices(), concreteIndices, routingMap, searchRequest.preference());
                if (shardCount == 1) {
                    // if we only have one group, then we always want Q_A_F, no need for DFS, and no need to do THEN since we hit one shard
                    searchRequest.searchType(SearchType.QUERY_AND_FETCH);
                }
            } catch (IndexMissingException e) {
                // ignore this, we will notify the search response if its really the case
                // from the actual action
            } catch (Exception e) {
                logger.debug("failed to optimize search type, continue as normal", e);
            }
        }

        if (searchRequest.searchType() == SearchType.DFS_QUERY_THEN_FETCH) {
            dfsQueryThenFetchAction.execute(searchRequest, listener);
        } else if (searchRequest.searchType() == SearchType.QUERY_THEN_FETCH) {
            queryThenFetchAction.execute(searchRequest, listener);
        } else if (searchRequest.searchType() == SearchType.DFS_QUERY_AND_FETCH) {
            dfsQueryAndFetchAction.execute(searchRequest, listener);
        } else if (searchRequest.searchType() == SearchType.QUERY_AND_FETCH) {
            queryAndFetchAction.execute(searchRequest, listener);
        } else if (searchRequest.searchType() == SearchType.SCAN) {
            scanAction.execute(searchRequest, listener);
        } else if (searchRequest.searchType() == SearchType.COUNT) {
            countAction.execute(searchRequest, listener);
        }
    }

    private class TransportHandler extends BaseTransportRequestHandler<GeoSimpleRequest> {

        @Override
        public GeoSimpleRequest newInstance() {
            return new GeoSimpleRequest();
        }

        @Override
        public void messageReceived(GeoSimpleRequest request, final TransportChannel channel) throws Exception {
            // no need for a threaded listener
            request.listenerThreaded(false);
            execute(request, new ActionListener<SearchResponse>() {
                @Override
                public void onResponse(SearchResponse result) {
                    try {
                        channel.sendResponse(result);
                    } catch (Throwable e) {
                        onFailure(e);
                    }
                }

                @Override
                public void onFailure(Throwable e) {
                    try {
                        channel.sendResponse(e);
                    } catch (Exception e1) {
                        logger.warn("Failed to send response for search", e1);
                    }
                }
            });
        }

        @Override
        public String executor() {
            return ThreadPool.Names.SAME;
        }
    }
}