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

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.neo4j.driver.ResultResourcesHandler;
import org.neo4j.driver.internal.NetworkSession;
import org.neo4j.driver.internal.async.AsyncConnection;
import org.neo4j.driver.internal.async.QueryRunner;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.util.ServerVersion;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.StatementResultCursor;
import org.neo4j.driver.v1.exceptions.ClientException;

import static org.neo4j.driver.internal.util.ServerVersion.v3_2_0;
import static org.neo4j.driver.v1.Values.parameters;

public class RoutingProcedureRunner
{
    static final String GET_SERVERS = "dbms.cluster.routing.getServers";
    static final String GET_ROUTING_TABLE_PARAM = "context";
    static final String GET_ROUTING_TABLE = "dbms.cluster.routing.getRoutingTable({" + GET_ROUTING_TABLE_PARAM + "})";

    private final RoutingContext context;

    public RoutingProcedureRunner( RoutingContext context )
    {
        this.context = context;
    }

    public RoutingProcedureResponse run( Connection connection )
    {
        Statement procedure = procedureStatement( ServerVersion.version( connection.server().version() ) );

        try
        {
            return new RoutingProcedureResponse( procedure, runProcedure( connection, procedure ) );
        }
        catch ( ClientException error )
        {
            return new RoutingProcedureResponse( procedure, error );
        }
    }

    public CompletionStage<RoutingProcedureResponse> run( CompletionStage<AsyncConnection> connectionStage )
    {
        return connectionStage.thenCompose( connection ->
        {
            Statement procedure = procedureStatement( connection.serverVersion() );
            return runProcedure( connection, procedure ).handle( ( records, error ) ->
            {
                if ( error != null )
                {
                    return handleError( procedure, error );
                }
                else
                {
                    return new RoutingProcedureResponse( procedure, records );
                }
            } );
        } );
    }

    List<Record> runProcedure( Connection connection, Statement procedure )
    {
        return NetworkSession.run( connection, procedure, ResultResourcesHandler.NO_OP ).list();
    }

    CompletionStage<List<Record>> runProcedure( AsyncConnection connection, Statement procedure )
    {
        return QueryRunner.runAsync( connection, procedure )
                .thenCompose( StatementResultCursor::listAsync );
    }

    private Statement procedureStatement( ServerVersion serverVersion )
    {
        if ( serverVersion.greaterThanOrEqual( v3_2_0 ) )
        {
            return new Statement( "CALL " + GET_ROUTING_TABLE,
                    parameters( GET_ROUTING_TABLE_PARAM, context.asMap() ) );
        }
        else
        {
            return new Statement( "CALL " + GET_SERVERS );
        }
    }

    private RoutingProcedureResponse handleError( Statement procedure, Throwable error )
    {
        if ( error instanceof ClientException )
        {
            return new RoutingProcedureResponse( procedure, error );
        }
        else
        {
            throw new CompletionException( error );
        }
    }
}
