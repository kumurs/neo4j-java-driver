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
package org.neo4j.driver.internal.cluster.loadbalancing;

import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.neo4j.driver.internal.ExplicitTransaction;
import org.neo4j.driver.internal.NetworkSession;
import org.neo4j.driver.internal.SessionResourcesHandler;
import org.neo4j.driver.internal.async.AsyncConnection;
import org.neo4j.driver.internal.async.Futures;
import org.neo4j.driver.internal.async.pool.AsyncConnectionPool;
import org.neo4j.driver.internal.cluster.AddressSet;
import org.neo4j.driver.internal.cluster.ClusterComposition;
import org.neo4j.driver.internal.cluster.ClusterRoutingTable;
import org.neo4j.driver.internal.cluster.Rediscovery;
import org.neo4j.driver.internal.cluster.RoutingPooledConnection;
import org.neo4j.driver.internal.cluster.RoutingTable;
import org.neo4j.driver.internal.net.BoltServerAddress;
import org.neo4j.driver.internal.retry.ExponentialBackoffRetryLogic;
import org.neo4j.driver.internal.retry.RetryLogic;
import org.neo4j.driver.internal.retry.RetrySettings;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.ConnectionPool;
import org.neo4j.driver.internal.spi.PooledConnection;
import org.neo4j.driver.internal.util.FakeClock;
import org.neo4j.driver.internal.util.SleeplessClock;
import org.neo4j.driver.v1.AccessMode;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException;
import org.neo4j.driver.v1.exceptions.SessionExpiredException;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.driver.internal.async.Futures.getBlocking;
import static org.neo4j.driver.internal.cluster.ClusterCompositionUtil.A;
import static org.neo4j.driver.internal.cluster.ClusterCompositionUtil.B;
import static org.neo4j.driver.internal.cluster.ClusterCompositionUtil.C;
import static org.neo4j.driver.internal.logging.DevNullLogging.DEV_NULL_LOGGING;
import static org.neo4j.driver.internal.net.BoltServerAddress.LOCAL_DEFAULT;
import static org.neo4j.driver.v1.AccessMode.READ;
import static org.neo4j.driver.v1.AccessMode.WRITE;
import static org.neo4j.driver.v1.util.TestUtil.asOrderedSet;

public class LoadBalancerTest
{
    @Test
    public void ensureRoutingShouldUpdateRoutingTableAndPurgeConnectionPoolWhenStale() throws Exception
    {
        // given
        ConnectionPool conns = mock( ConnectionPool.class );
        RoutingTable routingTable = mock( RoutingTable.class );
        Rediscovery rediscovery = mock( Rediscovery.class );
        Set<BoltServerAddress> set = singleton( new BoltServerAddress( "abc", 12 ) );
        when( routingTable.update( any( ClusterComposition.class ) ) ).thenReturn( set );

        // when
        LoadBalancer balancer = new LoadBalancer( conns, null, routingTable, rediscovery, GlobalEventExecutor.INSTANCE,
                DEV_NULL_LOGGING );

        // then
        assertNotNull( balancer );
        InOrder inOrder = inOrder( rediscovery, routingTable, conns );
        inOrder.verify( rediscovery ).lookupClusterComposition( routingTable, conns );
        inOrder.verify( routingTable ).update( any( ClusterComposition.class ) );
        inOrder.verify( conns ).purge( new BoltServerAddress( "abc", 12 ) );
    }

    @Test
    public void acquireShouldUpdateRoutingTableWhenKnownRoutingTableIsStale()
    {
        BoltServerAddress initialRouter = new BoltServerAddress( "initialRouter", 1 );
        BoltServerAddress reader1 = new BoltServerAddress( "reader-1", 2 );
        BoltServerAddress reader2 = new BoltServerAddress( "reader-1", 3 );
        BoltServerAddress writer1 = new BoltServerAddress( "writer-1", 4 );
        BoltServerAddress router1 = new BoltServerAddress( "router-1", 5 );

        AsyncConnectionPool asyncConnectionPool = newAsyncConnectionPoolMock();
        ClusterRoutingTable routingTable = new ClusterRoutingTable( new FakeClock(), initialRouter );

        Set<BoltServerAddress> readers = new LinkedHashSet<>( Arrays.asList( reader1, reader2 ) );
        Set<BoltServerAddress> writers = new LinkedHashSet<>( singletonList( writer1 ) );
        Set<BoltServerAddress> routers = new LinkedHashSet<>( singletonList( router1 ) );
        ClusterComposition clusterComposition = new ClusterComposition( 42, readers, writers, routers );
        Rediscovery rediscovery = mock( Rediscovery.class );
        when( rediscovery.lookupClusterCompositionAsync( routingTable, asyncConnectionPool ) )
                .thenReturn( completedFuture( clusterComposition ) );

        LoadBalancer loadBalancer =
                new LoadBalancer( null, asyncConnectionPool, routingTable, rediscovery, GlobalEventExecutor.INSTANCE,
                        DEV_NULL_LOGGING );

        assertNotNull( getBlocking( loadBalancer.acquireAsyncConnection( READ ) ) );

        verify( rediscovery ).lookupClusterCompositionAsync( routingTable, asyncConnectionPool );
        assertArrayEquals( new BoltServerAddress[]{reader1, reader2}, routingTable.readers().toArray() );
        assertArrayEquals( new BoltServerAddress[]{writer1}, routingTable.writers().toArray() );
        assertArrayEquals( new BoltServerAddress[]{router1}, routingTable.routers().toArray() );
    }

    @Test
    public void acquireShouldPurgeConnectionsWhenKnownRoutingTableIsStale()
    {
        BoltServerAddress initialRouter1 = new BoltServerAddress( "initialRouter-1", 1 );
        BoltServerAddress initialRouter2 = new BoltServerAddress( "initialRouter-2", 1 );
        BoltServerAddress reader = new BoltServerAddress( "reader", 2 );
        BoltServerAddress writer = new BoltServerAddress( "writer", 3 );
        BoltServerAddress router = new BoltServerAddress( "router", 4 );

        AsyncConnectionPool asyncConnectionPool = newAsyncConnectionPoolMock();
        ClusterRoutingTable routingTable = new ClusterRoutingTable( new FakeClock(), initialRouter1, initialRouter2 );

        Set<BoltServerAddress> readers = new HashSet<>( singletonList( reader ) );
        Set<BoltServerAddress> writers = new HashSet<>( singletonList( writer ) );
        Set<BoltServerAddress> routers = new HashSet<>( singletonList( router ) );
        ClusterComposition clusterComposition = new ClusterComposition( 42, readers, writers, routers );
        Rediscovery rediscovery = mock( Rediscovery.class );
        when( rediscovery.lookupClusterCompositionAsync( routingTable, asyncConnectionPool ) )
                .thenReturn( completedFuture( clusterComposition ) );

        LoadBalancer loadBalancer =
                new LoadBalancer( null, asyncConnectionPool, routingTable, rediscovery, GlobalEventExecutor.INSTANCE,
                        DEV_NULL_LOGGING );

        assertNotNull( getBlocking( loadBalancer.acquireAsyncConnection( READ ) ) );

        verify( rediscovery ).lookupClusterCompositionAsync( routingTable, asyncConnectionPool );
        verify( asyncConnectionPool ).purge( initialRouter1 );
        verify( asyncConnectionPool ).purge( initialRouter2 );
    }

    @Test
    public void shouldRefreshRoutingTableOnInitialization() throws Exception
    {
        // given & when
        final AtomicInteger refreshRoutingTableCounter = new AtomicInteger( 0 );
        LoadBalancer balancer = new LoadBalancer( mock( ConnectionPool.class ), null,
                mock( RoutingTable.class ), mock( Rediscovery.class ), GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING )
        {
            @Override
            synchronized void refreshRoutingTable()
            {
                refreshRoutingTableCounter.incrementAndGet();
            }
        };

        // then
        assertNotNull( balancer );
        assertThat( refreshRoutingTableCounter.get(), equalTo( 1 ) );
    }

    @Test
    public void shouldEnsureRoutingWhenAcquireConn() throws Exception
    {
        // given
        PooledConnection writerConn = mock( PooledConnection.class );
        PooledConnection readConn = mock( PooledConnection.class );
        LoadBalancer balancer = setupLoadBalancer( writerConn, readConn );
        LoadBalancer spy = spy( balancer );

        // when
        Connection connection = spy.acquireConnection( READ );
        connection.init( "Test", Collections.<String,Value>emptyMap() );

        // then
        verify( spy ).ensureRouting( READ );
        verify( readConn ).init( "Test", Collections.<String,Value>emptyMap() );
    }

    @Test
    public void shouldAcquireReaderOrWriterConn() throws Exception
    {
        PooledConnection writerConn = mock( PooledConnection.class );
        PooledConnection readConn = mock( PooledConnection.class );
        LoadBalancer balancer = setupLoadBalancer( writerConn, readConn );

        Connection acquiredReadConn = balancer.acquireConnection( READ );
        acquiredReadConn.init( "TestRead", Collections.<String,Value>emptyMap() );
        verify( readConn ).init( "TestRead", Collections.<String,Value>emptyMap() );

        Connection acquiredWriteConn = balancer.acquireConnection( WRITE );
        acquiredWriteConn.init( "TestWrite", Collections.<String,Value>emptyMap() );
        verify( writerConn ).init( "TestWrite", Collections.<String,Value>emptyMap() );
    }

    @Test
    public void shouldForgetAddressAndItsConnectionsOnServiceUnavailableWhileClosingTx()
    {
        RoutingTable routingTable = mock( RoutingTable.class );
        ConnectionPool connectionPool = mock( ConnectionPool.class );
        AsyncConnectionPool asyncConnectionPool = mock( AsyncConnectionPool.class );

        Rediscovery rediscovery = mock( Rediscovery.class );
        LoadBalancer loadBalancer = new LoadBalancer( connectionPool, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );
        BoltServerAddress address = new BoltServerAddress( "host", 42 );

        PooledConnection connection = newConnectionWithFailingSync( address );
        Connection routingConnection = new RoutingPooledConnection( connection, loadBalancer, AccessMode.WRITE );
        Transaction tx = new ExplicitTransaction( routingConnection, mock( SessionResourcesHandler.class ) );

        try
        {
            tx.close();
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( SessionExpiredException.class ) );
            assertThat( e.getCause(), instanceOf( ServiceUnavailableException.class ) );
        }

        verify( routingTable ).forget( address );
        verify( connectionPool ).purge( address );
    }

    @Test
    public void shouldForgetAddressAndItsConnectionsOnServiceUnavailableWhileClosingSession()
    {
        BoltServerAddress address = new BoltServerAddress( "host", 42 );
        RoutingTable routingTable = mock( RoutingTable.class );
        AddressSet addressSet = mock( AddressSet.class );
        when( addressSet.toArray() ).thenReturn( new BoltServerAddress[]{address} );
        when( routingTable.writers() ).thenReturn( addressSet );
        ConnectionPool connectionPool = mock( ConnectionPool.class );
        AsyncConnectionPool asyncConnectionPool = mock( AsyncConnectionPool.class );
        PooledConnection connectionWithFailingSync = newConnectionWithFailingSync( address );
        when( connectionPool.acquire( any( BoltServerAddress.class ) ) ).thenReturn( connectionWithFailingSync );
        Rediscovery rediscovery = mock( Rediscovery.class );
        LoadBalancer loadBalancer = new LoadBalancer( connectionPool, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );

        Session session = newSession( loadBalancer );
        // begin transaction to make session obtain a connection
        session.beginTransaction();

        session.close();

        verify( routingTable ).forget( address );
        verify( connectionPool ).purge( address );
    }

    @Test
    public void shouldRediscoverOnReadWhenRoutingTableIsStaleForReads()
    {
        testRediscoveryWhenStale( READ );
    }

    @Test
    public void shouldRediscoverOnWriteWhenRoutingTableIsStaleForWrites()
    {
        testRediscoveryWhenStale( WRITE );
    }

    @Test
    public void shouldRediscoverOnReadWhenRoutingTableIsStaleForReadsAsync()
    {
        testRediscoveryWhenStaleAsync( READ );
    }

    @Test
    public void shouldRediscoverOnWriteWhenRoutingTableIsStaleForWritesAsync()
    {
        testRediscoveryWhenStaleAsync( WRITE );
    }

    @Test
    public void shouldNotRediscoverOnReadWhenRoutingTableIsStaleForWritesButNotReads()
    {
        testNoRediscoveryWhenNotStale( WRITE, READ );
    }

    @Test
    public void shouldNotRediscoverOnWriteWhenRoutingTableIsStaleForReadsButNotWrites()
    {
        testNoRediscoveryWhenNotStale( READ, WRITE );
    }

    @Test
    public void shouldNotRediscoverOnReadWhenRoutingTableIsStaleForWritesButNotReadsAsync()
    {
        testNoRediscoveryWhenNotStaleAsync( WRITE, READ );
    }

    @Test
    public void shouldNotRediscoverOnWriteWhenRoutingTableIsStaleForReadsButNotWritesAsync()
    {
        testNoRediscoveryWhenNotStaleAsync( READ, WRITE );
    }

    @Test
    public void shouldThrowWhenRediscoveryReturnsNoSuitableServers()
    {
        ConnectionPool connections = mock( ConnectionPool.class );
        AsyncConnectionPool asyncConnectionPool = mock( AsyncConnectionPool.class );
        RoutingTable routingTable = mock( RoutingTable.class );
        when( routingTable.isStaleFor( any( AccessMode.class ) ) ).thenReturn( true );
        Rediscovery rediscovery = mock( Rediscovery.class );
        when( routingTable.readers() ).thenReturn( new AddressSet() );
        when( routingTable.writers() ).thenReturn( new AddressSet() );

        LoadBalancer loadBalancer = new LoadBalancer( connections, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );

        try
        {
            loadBalancer.acquireConnection( READ );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( SessionExpiredException.class ) );
            assertThat( e.getMessage(), startsWith( "Failed to obtain connection towards READ server" ) );
        }

        try
        {
            loadBalancer.acquireConnection( WRITE );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( SessionExpiredException.class ) );
            assertThat( e.getMessage(), startsWith( "Failed to obtain connection towards WRITE server" ) );
        }
    }

    @Test
    public void shouldThrowWhenRediscoveryReturnsNoSuitableServersAsync()
    {
        AsyncConnectionPool asyncConnectionPool = newAsyncConnectionPoolMock();
        RoutingTable routingTable = mock( RoutingTable.class );
        when( routingTable.isStaleFor( any( AccessMode.class ) ) ).thenReturn( true );
        Rediscovery rediscovery = mock( Rediscovery.class );
        ClusterComposition emptyClusterComposition = new ClusterComposition( 42, emptySet(), emptySet(), emptySet() );
        when( rediscovery.lookupClusterCompositionAsync( routingTable, asyncConnectionPool ) )
                .thenReturn( completedFuture( emptyClusterComposition ) );
        when( routingTable.readers() ).thenReturn( new AddressSet() );
        when( routingTable.writers() ).thenReturn( new AddressSet() );

        LoadBalancer loadBalancer = new LoadBalancer( null, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );

        try
        {
            getBlocking( loadBalancer.acquireAsyncConnection( READ ) );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( SessionExpiredException.class ) );
            assertThat( e.getMessage(), startsWith( "Failed to obtain connection towards READ server" ) );
        }

        try
        {
            getBlocking( loadBalancer.acquireAsyncConnection( WRITE ) );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( SessionExpiredException.class ) );
            assertThat( e.getMessage(), startsWith( "Failed to obtain connection towards WRITE server" ) );
        }
    }

    @Test
    public void shouldSelectLeastConnectedAddress()
    {
        ConnectionPool connectionPool = newConnectionPoolMock();
        AsyncConnectionPool asyncConnectionPool = newAsyncConnectionPoolMock();
        when( connectionPool.activeConnections( A ) ).thenReturn( 0 );
        when( connectionPool.activeConnections( B ) ).thenReturn( 20 );
        when( connectionPool.activeConnections( C ) ).thenReturn( 0 );

        RoutingTable routingTable = mock( RoutingTable.class );
        AddressSet readerAddresses = mock( AddressSet.class );
        when( readerAddresses.toArray() ).thenReturn( new BoltServerAddress[]{A, B, C} );
        when( routingTable.readers() ).thenReturn( readerAddresses );

        Rediscovery rediscovery = mock( Rediscovery.class );

        LoadBalancer loadBalancer = new LoadBalancer( connectionPool, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );

        Set<BoltServerAddress> seenAddresses = new HashSet<>();
        for ( int i = 0; i < 10; i++ )
        {
            PooledConnection connection = loadBalancer.acquireConnection( READ );
            seenAddresses.add( connection.boltServerAddress() );
        }

        // server B should never be selected because it has many active connections
        assertEquals( 2, seenAddresses.size() );
        assertTrue( seenAddresses.containsAll( Arrays.asList( A, C ) ) );
    }

    @Test
    public void shouldSelectLeastConnectedAddressAsync()
    {
        AsyncConnectionPool asyncConnectionPool = newAsyncConnectionPoolMock();

        when( asyncConnectionPool.activeConnections( A ) ).thenReturn( 0 );
        when( asyncConnectionPool.activeConnections( B ) ).thenReturn( 20 );
        when( asyncConnectionPool.activeConnections( C ) ).thenReturn( 0 );

        RoutingTable routingTable = mock( RoutingTable.class );
        AddressSet readerAddresses = mock( AddressSet.class );
        when( readerAddresses.toArray() ).thenReturn( new BoltServerAddress[]{A, B, C} );
        when( routingTable.readers() ).thenReturn( readerAddresses );

        Rediscovery rediscovery = mock( Rediscovery.class );

        LoadBalancer loadBalancer =
                new LoadBalancer( null, asyncConnectionPool, routingTable, rediscovery, GlobalEventExecutor.INSTANCE,
                        DEV_NULL_LOGGING );

        Set<BoltServerAddress> seenAddresses = new HashSet<>();
        for ( int i = 0; i < 10; i++ )
        {
            AsyncConnection connection = getBlocking( loadBalancer.acquireAsyncConnection( READ ) );
            seenAddresses.add( connection.serverAddress() );
        }

        // server B should never be selected because it has many active connections
        assertEquals( 2, seenAddresses.size() );
        assertTrue( seenAddresses.containsAll( Arrays.asList( A, C ) ) );
    }

    @Test
    public void shouldRoundRobinWhenNoActiveConnections()
    {
        ConnectionPool connectionPool = newConnectionPoolMock();
        AsyncConnectionPool asyncConnectionPool = newAsyncConnectionPoolMock();

        RoutingTable routingTable = mock( RoutingTable.class );
        AddressSet readerAddresses = mock( AddressSet.class );
        when( readerAddresses.toArray() ).thenReturn( new BoltServerAddress[]{A, B, C} );
        when( routingTable.readers() ).thenReturn( readerAddresses );

        Rediscovery rediscovery = mock( Rediscovery.class );

        LoadBalancer loadBalancer = new LoadBalancer( connectionPool, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );

        Set<BoltServerAddress> seenAddresses = new HashSet<>();
        for ( int i = 0; i < 10; i++ )
        {
            PooledConnection connection = loadBalancer.acquireConnection( READ );
            seenAddresses.add( connection.boltServerAddress() );
        }

        assertEquals( 3, seenAddresses.size() );
        assertTrue( seenAddresses.containsAll( Arrays.asList( A, B, C ) ) );
    }

    @Test
    public void shouldRoundRobinWhenNoActiveConnectionsAsync()
    {
        ConnectionPool connectionPool = newConnectionPoolMock();
        AsyncConnectionPool asyncConnectionPool = newAsyncConnectionPoolMock();

        RoutingTable routingTable = mock( RoutingTable.class );
        AddressSet readerAddresses = mock( AddressSet.class );
        when( readerAddresses.toArray() ).thenReturn( new BoltServerAddress[]{A, B, C} );
        when( routingTable.readers() ).thenReturn( readerAddresses );

        Rediscovery rediscovery = mock( Rediscovery.class );

        LoadBalancer loadBalancer = new LoadBalancer( connectionPool, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );

        Set<BoltServerAddress> seenAddresses = new HashSet<>();
        for ( int i = 0; i < 10; i++ )
        {
            AsyncConnection connection = getBlocking( loadBalancer.acquireAsyncConnection( READ ) );
            seenAddresses.add( connection.serverAddress() );
        }

        assertEquals( 3, seenAddresses.size() );
        assertTrue( seenAddresses.containsAll( Arrays.asList( A, B, C ) ) );
    }

    @Test
    public void shouldTryMultipleServersAfterRediscovery()
    {
        Set<BoltServerAddress> unavailableAddresses = asOrderedSet( A );
        AsyncConnectionPool asyncConnectionPool = newAsyncConnectionPoolMockWithFailures( unavailableAddresses );

        ClusterRoutingTable routingTable = new ClusterRoutingTable( new FakeClock(), A );
        Rediscovery rediscovery = mock( Rediscovery.class );
        ClusterComposition clusterComposition = new ClusterComposition( 42,
                asOrderedSet( A, B ), asOrderedSet( A, B ), asOrderedSet( A, B ) );
        when( rediscovery.lookupClusterCompositionAsync( any(), any() ) )
                .thenReturn( completedFuture( clusterComposition ) );

        LoadBalancer loadBalancer = new LoadBalancer( null, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );

        AsyncConnection connection = getBlocking( loadBalancer.acquireAsyncConnection( READ ) );

        assertNotNull( connection );
        assertEquals( B, connection.serverAddress() );
        // routing table should've forgotten A
        assertArrayEquals( new BoltServerAddress[]{B}, routingTable.readers().toArray() );
    }

    private void testRediscoveryWhenStale( AccessMode mode )
    {
        ConnectionPool connections = mock( ConnectionPool.class );
        when( connections.acquire( LOCAL_DEFAULT ) ).thenReturn( mock( PooledConnection.class ) );

        RoutingTable routingTable = newStaleRoutingTableMock( mode );
        Rediscovery rediscovery = newRediscoveryMock();

        LoadBalancer loadBalancer = new LoadBalancer( connections, null, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );
        verify( rediscovery ).lookupClusterComposition( routingTable, connections );

        assertNotNull( loadBalancer.acquireConnection( mode ) );
        verify( routingTable ).isStaleFor( mode );
        verify( rediscovery, times( 2 ) ).lookupClusterComposition( routingTable, connections );
    }

    private void testRediscoveryWhenStaleAsync( AccessMode mode )
    {
        AsyncConnectionPool asyncConnectionPool = mock( AsyncConnectionPool.class );
        when( asyncConnectionPool.acquire( LOCAL_DEFAULT ) )
                .thenReturn( completedFuture( mock( AsyncConnection.class ) ) );

        RoutingTable routingTable = newStaleRoutingTableMock( mode );
        Rediscovery rediscovery = newRediscoveryMock();

        LoadBalancer loadBalancer =
                new LoadBalancer( null, asyncConnectionPool, routingTable, rediscovery, GlobalEventExecutor.INSTANCE,
                        DEV_NULL_LOGGING );
        AsyncConnection connection = getBlocking( loadBalancer.acquireAsyncConnection( mode ) );
        assertNotNull( connection );

        verify( routingTable ).isStaleFor( mode );
        verify( rediscovery ).lookupClusterCompositionAsync( routingTable, asyncConnectionPool );
    }

    private void testNoRediscoveryWhenNotStale( AccessMode staleMode, AccessMode notStaleMode )
    {
        ConnectionPool connections = mock( ConnectionPool.class );
        when( connections.acquire( LOCAL_DEFAULT ) ).thenReturn( mock( PooledConnection.class ) );

        RoutingTable routingTable = newStaleRoutingTableMock( staleMode );
        Rediscovery rediscovery = newRediscoveryMock();

        LoadBalancer loadBalancer = new LoadBalancer( connections, null, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );
        verify( rediscovery ).lookupClusterComposition( routingTable, connections );

        assertNotNull( loadBalancer.acquireConnection( notStaleMode ) );
        verify( routingTable ).isStaleFor( notStaleMode );
        verify( rediscovery ).lookupClusterComposition( routingTable, connections );
    }

    private void testNoRediscoveryWhenNotStaleAsync( AccessMode staleMode, AccessMode notStaleMode )
    {
        AsyncConnectionPool asyncConnectionPool = mock( AsyncConnectionPool.class );
        when( asyncConnectionPool.acquire( LOCAL_DEFAULT ) )
                .thenReturn( completedFuture( mock( AsyncConnection.class ) ) );

        RoutingTable routingTable = newStaleRoutingTableMock( staleMode );
        Rediscovery rediscovery = newRediscoveryMock();

        LoadBalancer loadBalancer = new LoadBalancer( null, asyncConnectionPool, routingTable, rediscovery,
                GlobalEventExecutor.INSTANCE, DEV_NULL_LOGGING );

        assertNotNull( getBlocking( loadBalancer.acquireAsyncConnection( notStaleMode ) ) );
        verify( routingTable ).isStaleFor( notStaleMode );
        verify( rediscovery, never() ).lookupClusterCompositionAsync( routingTable, asyncConnectionPool );
    }

    private LoadBalancer setupLoadBalancer( PooledConnection writerConn, PooledConnection readConn )
    {
        BoltServerAddress writer = mock( BoltServerAddress.class );
        BoltServerAddress reader = mock( BoltServerAddress.class );

        ConnectionPool connPool = mock( ConnectionPool.class );
        when( connPool.acquire( writer ) ).thenReturn( writerConn );
        when( connPool.acquire( reader ) ).thenReturn( readConn );

        AsyncConnectionPool asyncConnectionPool = mock( AsyncConnectionPool.class );

        AddressSet writerAddrs = mock( AddressSet.class );
        when( writerAddrs.toArray() ).thenReturn( new BoltServerAddress[]{writer} );

        AddressSet readerAddrs = mock( AddressSet.class );
        when( readerAddrs.toArray() ).thenReturn( new BoltServerAddress[]{reader} );

        RoutingTable routingTable = mock( RoutingTable.class );
        when( routingTable.readers() ).thenReturn( readerAddrs );
        when( routingTable.writers() ).thenReturn( writerAddrs );

        Rediscovery rediscovery = mock( Rediscovery.class );

        return new LoadBalancer( connPool, asyncConnectionPool, routingTable, rediscovery, GlobalEventExecutor.INSTANCE,
                DEV_NULL_LOGGING );
    }

    private static Session newSession( LoadBalancer loadBalancer )
    {
        SleeplessClock clock = new SleeplessClock();
        RetryLogic retryLogic = new ExponentialBackoffRetryLogic( RetrySettings.DEFAULT, GlobalEventExecutor.INSTANCE,
                clock, DEV_NULL_LOGGING );
        return new NetworkSession( loadBalancer, AccessMode.WRITE, retryLogic, DEV_NULL_LOGGING );
    }

    private static PooledConnection newConnectionWithFailingSync( BoltServerAddress address )
    {
        PooledConnection connection = mock( PooledConnection.class );
        doReturn( true ).when( connection ).isOpen();
        doReturn( address ).when( connection ).boltServerAddress();
        ServiceUnavailableException closeError = new ServiceUnavailableException( "Oh!" );
        doThrow( closeError ).when( connection ).sync();
        return connection;
    }

    private static RoutingTable newStaleRoutingTableMock( AccessMode mode )
    {
        RoutingTable routingTable = mock( RoutingTable.class );
        when( routingTable.isStaleFor( mode ) ).thenReturn( true );
        when( routingTable.update( any( ClusterComposition.class ) ) ).thenReturn( new HashSet<BoltServerAddress>() );

        AddressSet addresses = new AddressSet();
        addresses.update( new HashSet<>( singletonList( LOCAL_DEFAULT ) ), new HashSet<BoltServerAddress>() );
        when( routingTable.readers() ).thenReturn( addresses );
        when( routingTable.writers() ).thenReturn( addresses );

        return routingTable;
    }

    private static Rediscovery newRediscoveryMock()
    {
        Rediscovery rediscovery = mock( Rediscovery.class );
        Set<BoltServerAddress> noServers = Collections.<BoltServerAddress>emptySet();
        ClusterComposition clusterComposition = new ClusterComposition( 1, noServers, noServers, noServers );
        when( rediscovery.lookupClusterComposition( any( RoutingTable.class ), any( ConnectionPool.class ) ) )
                .thenReturn( clusterComposition );
        when( rediscovery.lookupClusterCompositionAsync( any( RoutingTable.class ), any( AsyncConnectionPool.class ) ) )
                .thenReturn( completedFuture( clusterComposition ) );
        return rediscovery;
    }

    private static ConnectionPool newConnectionPoolMock()
    {
        ConnectionPool connectionPool = mock( ConnectionPool.class );
        when( connectionPool.acquire( any( BoltServerAddress.class ) ) ).then( new Answer<PooledConnection>()
        {
            @Override
            public PooledConnection answer( InvocationOnMock invocation ) throws Throwable
            {
                BoltServerAddress requestedAddress = invocation.getArgumentAt( 0, BoltServerAddress.class );
                PooledConnection connection = mock( PooledConnection.class );
                when( connection.boltServerAddress() ).thenReturn( requestedAddress );
                return connection;
            }
        } );
        return connectionPool;
    }

    private static AsyncConnectionPool newAsyncConnectionPoolMock()
    {
        return newAsyncConnectionPoolMockWithFailures( emptySet() );
    }

    private static AsyncConnectionPool newAsyncConnectionPoolMockWithFailures(
            Set<BoltServerAddress> unavailableAddresses )
    {
        AsyncConnectionPool pool = mock( AsyncConnectionPool.class );
        when( pool.acquire( any( BoltServerAddress.class ) ) ).then( invocation ->
        {
            BoltServerAddress requestedAddress = invocation.getArgumentAt( 0, BoltServerAddress.class );
            if ( unavailableAddresses.contains( requestedAddress ) )
            {
                return Futures.failedFuture( new ServiceUnavailableException( requestedAddress + " is unavailable!" ) );
            }
            AsyncConnection connection = mock( AsyncConnection.class );
            when( connection.serverAddress() ).thenReturn( requestedAddress );
            return completedFuture( connection );
        } );
        return pool;
    }
}
