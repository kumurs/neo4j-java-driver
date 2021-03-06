/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
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
package org.neo4j.driver.internal.cluster;

import io.netty.util.concurrent.EventExecutorGroup;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.neo4j.driver.internal.async.AsyncConnection;
import org.neo4j.driver.internal.async.pool.AsyncConnectionPool;
import org.neo4j.driver.internal.net.BoltServerAddress;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.ConnectionPool;
import org.neo4j.driver.internal.util.Clock;
import org.neo4j.driver.v1.Logger;
import org.neo4j.driver.v1.exceptions.SecurityException;
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException;

import static java.lang.String.format;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class Rediscovery
{
    private static final String NO_ROUTERS_AVAILABLE = "Could not perform discovery. No routing servers available.";

    private final BoltServerAddress initialRouter;
    private final RoutingSettings settings;
    private final Clock clock;
    private final Logger logger;
    private final ClusterCompositionProvider provider;
    private final HostNameResolver hostNameResolver;
    private final EventExecutorGroup eventExecutorGroup;

    private volatile boolean useInitialRouter;

    public Rediscovery( BoltServerAddress initialRouter, RoutingSettings settings, ClusterCompositionProvider provider,
            EventExecutorGroup eventExecutorGroup, HostNameResolver hostNameResolver, Clock clock, Logger logger )
    {
        // todo: set useInitialRouter to true when driver only does async
        this( initialRouter, settings, provider, hostNameResolver, eventExecutorGroup, clock, logger, false );
    }

    // Test-only constructor
    public Rediscovery( BoltServerAddress initialRouter, RoutingSettings settings, ClusterCompositionProvider provider,
            HostNameResolver hostNameResolver, EventExecutorGroup eventExecutorGroup, Clock clock, Logger logger,
            boolean useInitialRouter )
    {
        this.initialRouter = initialRouter;
        this.settings = settings;
        this.clock = clock;
        this.logger = logger;
        this.provider = provider;
        this.hostNameResolver = hostNameResolver;
        this.eventExecutorGroup = eventExecutorGroup;
        this.useInitialRouter = useInitialRouter;
    }

    /**
     * Given the current routing table and connection pool, use the connection composition provider to fetch a new
     * cluster composition, which would be used to update the routing table and connection pool.
     *
     * @param routingTable current routing table.
     * @param connections connection pool.
     * @return new cluster composition.
     */
    public ClusterComposition lookupClusterComposition( RoutingTable routingTable, ConnectionPool connections )
    {
        int failures = 0;

        for ( long start = clock.millis(), delay = 0; ; delay = Math.max( settings.retryTimeoutDelay(), delay * 2 ) )
        {
            long waitTime = start + delay - clock.millis();
            sleep( waitTime );
            start = clock.millis();

            ClusterComposition composition = lookup( routingTable, connections );
            if ( composition != null )
            {
                return composition;
            }

            if ( ++failures >= settings.maxRoutingFailures() )
            {
                throw new ServiceUnavailableException( NO_ROUTERS_AVAILABLE );
            }
        }
    }

    public CompletionStage<ClusterComposition> lookupClusterCompositionAsync( RoutingTable routingTable,
            AsyncConnectionPool connectionPool )
    {
        CompletableFuture<ClusterComposition> result = new CompletableFuture<>();
        lookupClusterComposition( routingTable, connectionPool, 0, 0, result );
        return result;
    }

    private void lookupClusterComposition( RoutingTable routingTable, AsyncConnectionPool pool,
            int failures, long previousDelay, CompletableFuture<ClusterComposition> result )
    {
        if ( failures >= settings.maxRoutingFailures() )
        {
            result.completeExceptionally( new ServiceUnavailableException( NO_ROUTERS_AVAILABLE ) );
            return;
        }

        lookupAsync( routingTable, pool ).whenComplete( ( composition, error ) ->
        {
            if ( error != null )
            {
                result.completeExceptionally( error );
            }
            else if ( composition != null )
            {
                result.complete( composition );
            }
            else
            {
                long nextDelay = Math.max( settings.retryTimeoutDelay(), previousDelay * 2 );
                logger.info( "Unable to fetch new routing table, will try again in " + nextDelay + "ms" );
                eventExecutorGroup.next().schedule(
                        () -> lookupClusterComposition( routingTable, pool, failures + 1, nextDelay, result ),
                        nextDelay, TimeUnit.MILLISECONDS
                );
            }
        } );
    }

    private ClusterComposition lookup( RoutingTable routingTable, ConnectionPool connections )
    {
        ClusterComposition composition;

        if ( useInitialRouter )
        {
            composition = lookupOnInitialRouterThenOnKnownRouters( routingTable, connections );
            useInitialRouter = false;
        }
        else
        {
            composition = lookupOnKnownRoutersThenOnInitialRouter( routingTable, connections );
        }

        if ( composition != null && !composition.hasWriters() )
        {
            useInitialRouter = true;
        }

        return composition;
    }

    private CompletionStage<ClusterComposition> lookupAsync( RoutingTable routingTable,
            AsyncConnectionPool connectionPool )
    {
        CompletionStage<ClusterComposition> compositionStage;

        if ( useInitialRouter )
        {
            compositionStage = lookupOnInitialRouterThenOnKnownRoutersAsync( routingTable, connectionPool );
            useInitialRouter = false;
        }
        else
        {
            compositionStage = lookupOnKnownRoutersThenOnInitialRouterAsync( routingTable, connectionPool );
        }

        return compositionStage.whenComplete( ( composition, error ) ->
        {
            if ( composition != null && !composition.hasWriters() )
            {
                useInitialRouter = true;
            }
        } );
    }

    private ClusterComposition lookupOnKnownRoutersThenOnInitialRouter( RoutingTable routingTable,
            ConnectionPool connections )
    {
        Set<BoltServerAddress> seenServers = new HashSet<>();
        ClusterComposition composition = lookupOnKnownRouters( routingTable, connections, seenServers );
        if ( composition == null )
        {
            return lookupOnInitialRouter( routingTable, connections, seenServers );
        }
        return composition;
    }

    private CompletionStage<ClusterComposition> lookupOnKnownRoutersThenOnInitialRouterAsync( RoutingTable routingTable,
            AsyncConnectionPool connectionPool )
    {
        Set<BoltServerAddress> seenServers = new HashSet<>();
        return lookupOnKnownRoutersAsync( routingTable, connectionPool, seenServers ).thenCompose( composition ->
        {
            if ( composition != null )
            {
                return completedFuture( composition );
            }
            return lookupOnInitialRouterAsync( routingTable, connectionPool, seenServers );
        } );
    }

    private ClusterComposition lookupOnInitialRouterThenOnKnownRouters( RoutingTable routingTable,
            ConnectionPool connections )
    {
        Set<BoltServerAddress> seenServers = Collections.emptySet();
        ClusterComposition composition = lookupOnInitialRouter( routingTable, connections, seenServers );
        if ( composition == null )
        {
            return lookupOnKnownRouters( routingTable, connections, new HashSet<BoltServerAddress>() );
        }
        return composition;
    }

    private CompletionStage<ClusterComposition> lookupOnInitialRouterThenOnKnownRoutersAsync( RoutingTable routingTable,
            AsyncConnectionPool connectionPool )
    {
        Set<BoltServerAddress> seenServers = Collections.emptySet();
        return lookupOnInitialRouterAsync( routingTable, connectionPool, seenServers ).thenCompose( composition ->
        {
            if ( composition != null )
            {
                return completedFuture( composition );
            }
            return lookupOnKnownRoutersAsync( routingTable, connectionPool, new HashSet<>() );
        } );
    }

    private ClusterComposition lookupOnKnownRouters( RoutingTable routingTable, ConnectionPool connections,
            Set<BoltServerAddress> seenServers )
    {
        BoltServerAddress[] addresses = routingTable.routers().toArray();

        for ( BoltServerAddress address : addresses )
        {
            ClusterComposition composition = lookupOnRouter( address, routingTable, connections );
            if ( composition != null )
            {
                return composition;
            }
            else
            {
                seenServers.add( address );
            }
        }

        return null;
    }

    private CompletionStage<ClusterComposition> lookupOnKnownRoutersAsync( RoutingTable routingTable,
            AsyncConnectionPool connectionPool, Set<BoltServerAddress> seenServers )
    {
        BoltServerAddress[] addresses = routingTable.routers().toArray();

        CompletableFuture<ClusterComposition> result = completedFuture( null );
        for ( BoltServerAddress address : addresses )
        {
            result = result.thenCompose( composition ->
            {
                if ( composition != null )
                {
                    return completedFuture( composition );
                }
                else
                {
                    return lookupOnRouterAsync( address, routingTable, connectionPool )
                            .whenComplete( ( ignore, error ) -> seenServers.add( address ) );
                }
            } );
        }
        return result;
    }

    private ClusterComposition lookupOnInitialRouter( RoutingTable routingTable,
            ConnectionPool connections, Set<BoltServerAddress> seenServers )
    {
        Set<BoltServerAddress> ips = hostNameResolver.resolve( initialRouter );
        ips.removeAll( seenServers );
        for ( BoltServerAddress address : ips )
        {
            ClusterComposition composition = lookupOnRouter( address, routingTable, connections );
            if ( composition != null )
            {
                return composition;
            }
        }

        return null;
    }

    private CompletionStage<ClusterComposition> lookupOnInitialRouterAsync( RoutingTable routingTable,
            AsyncConnectionPool connectionPool, Set<BoltServerAddress> seenServers )
    {
        Set<BoltServerAddress> addresses = hostNameResolver.resolve( initialRouter );
        addresses.removeAll( seenServers );

        CompletableFuture<ClusterComposition> result = completedFuture( null );
        for ( BoltServerAddress address : addresses )
        {
            result = result.thenCompose( composition ->
            {
                if ( composition != null )
                {
                    return completedFuture( composition );
                }
                return lookupOnRouterAsync( address, routingTable, connectionPool );
            } );
        }
        return result;
    }

    private ClusterComposition lookupOnRouter( BoltServerAddress routerAddress, RoutingTable routingTable,
            ConnectionPool connections )
    {
        ClusterCompositionResponse response;
        try ( Connection connection = connections.acquire( routerAddress ) )
        {
            response = provider.getClusterComposition( connection );
        }
        catch ( SecurityException e )
        {
            // auth error happened, terminate the discovery procedure immediately
            throw e;
        }
        catch ( Throwable t )
        {
            // connection turned out to be broken
            logger.error( format( "Failed to connect to routing server '%s'.", routerAddress ), t );
            routingTable.forget( routerAddress );
            return null;
        }

        ClusterComposition cluster = response.clusterComposition();
        logger.info( "Got cluster composition %s", cluster );
        return cluster;
    }

    private CompletionStage<ClusterComposition> lookupOnRouterAsync( BoltServerAddress routerAddress,
            RoutingTable routingTable, AsyncConnectionPool connectionPool )
    {
        CompletionStage<AsyncConnection> connectionStage = connectionPool.acquire( routerAddress );

        return provider.getClusterComposition( connectionStage ).handle( ( response, error ) ->
        {
            if ( error != null )
            {
                return handleRoutingProcedureError( error, routingTable, routerAddress );
            }
            else
            {
                ClusterComposition cluster = response.clusterComposition();
                logger.info( "Got cluster composition %s", cluster );
                return cluster;
            }
        } );
    }

    private ClusterComposition handleRoutingProcedureError( Throwable error, RoutingTable routingTable,
            BoltServerAddress routerAddress )
    {
        if ( error instanceof SecurityException )
        {
            // auth error happened, terminate the discovery procedure immediately
            throw new CompletionException( error );
        }
        else
        {
            // connection turned out to be broken
            logger.error( format( "Failed to connect to routing server '%s'.", routerAddress ), error );
            routingTable.forget( routerAddress );
            return null;
        }
    }

    private void sleep( long millis )
    {
        if ( millis > 0 )
        {
            try
            {
                clock.sleep( millis );
            }
            catch ( InterruptedException e )
            {
                // restore the interrupted status
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableException( "Thread was interrupted while performing discovery", e );
            }
        }
    }
}
