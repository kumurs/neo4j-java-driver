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
package org.neo4j.driver.internal.handlers;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.neo4j.driver.internal.InternalRecord;
import org.neo4j.driver.internal.async.AsyncConnection;
import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.internal.summary.InternalNotification;
import org.neo4j.driver.internal.summary.InternalPlan;
import org.neo4j.driver.internal.summary.InternalProfiledPlan;
import org.neo4j.driver.internal.summary.InternalResultSummary;
import org.neo4j.driver.internal.summary.InternalServerInfo;
import org.neo4j.driver.internal.summary.InternalSummaryCounters;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.summary.Notification;
import org.neo4j.driver.v1.summary.Plan;
import org.neo4j.driver.v1.summary.ProfiledPlan;
import org.neo4j.driver.v1.summary.ResultSummary;
import org.neo4j.driver.v1.summary.StatementType;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.neo4j.driver.internal.async.Futures.failedFuture;

public abstract class PullAllResponseHandler implements ResponseHandler
{
    private static final boolean TOUCH_AUTO_READ = false;

    private final Statement statement;
    private final RunResponseHandler runResponseHandler;
    protected final AsyncConnection connection;

    private final Queue<Record> records = new LinkedList<>();

    private boolean succeeded;
    private Throwable failure;
    private ResultSummary summary;

    private CompletableFuture<Record> recordFuture;
    private CompletableFuture<ResultSummary> summaryFuture;

    public PullAllResponseHandler( Statement statement, RunResponseHandler runResponseHandler,
            AsyncConnection connection )
    {
        this.statement = requireNonNull( statement );
        this.runResponseHandler = requireNonNull( runResponseHandler );
        this.connection = requireNonNull( connection );
    }

    @Override
    public synchronized void onSuccess( Map<String,Value> metadata )
    {
        summary = extractResultSummary( metadata );
        if ( summaryFuture != null )
        {
            summaryFuture.complete( summary );
            summaryFuture = null;
        }

        succeeded = true;
        afterSuccess();
        completeRecordFuture( null );
    }

    protected abstract void afterSuccess();

    @Override
    public synchronized void onFailure( Throwable error )
    {
        failure = error;
        afterFailure( error );
        failRecordFuture( error );
    }

    protected abstract void afterFailure( Throwable error );

    @Override
    public synchronized void onRecord( Value[] fields )
    {
        Record record = new InternalRecord( runResponseHandler.statementKeys(), fields );

        if ( recordFuture != null )
        {
            completeRecordFuture( record );
        }
        else
        {
            queueRecord( record );
        }
    }

    public synchronized CompletionStage<Record> nextAsync()
    {
        Record record = dequeueRecord();
        if ( record == null )
        {
            if ( succeeded )
            {
                return completedFuture( null );
            }

            if ( failure != null )
            {
                return failedFuture( failure );
            }

            if ( recordFuture == null )
            {
                recordFuture = new CompletableFuture<>();
            }
            return recordFuture;
        }
        else
        {
            return completedFuture( record );
        }
    }

    public synchronized CompletionStage<ResultSummary> summaryAsync()
    {
        if ( summary != null )
        {
            return completedFuture( summary );
        }
        else
        {
            if ( summaryFuture == null )
            {
                summaryFuture = new CompletableFuture<>();
            }
            return summaryFuture;
        }
    }

    private void queueRecord( Record record )
    {
        records.add( record );
        if ( TOUCH_AUTO_READ )
        {
            if ( records.size() > 10_000 )
            {
                connection.disableAutoRead();
            }
        }
    }

    private Record dequeueRecord()
    {
        Record record = records.poll();
        if ( TOUCH_AUTO_READ )
        {
            if ( record != null && records.size() < 100 )
            {
                connection.enableAutoRead();
            }
        }
        return record;
    }

    private void completeRecordFuture( Record record )
    {
        if ( recordFuture != null )
        {
            CompletableFuture<Record> future = recordFuture;
            recordFuture = null;
            future.complete( record );
        }
    }

    private void failRecordFuture( Throwable error )
    {
        if ( recordFuture != null )
        {
            CompletableFuture<Record> future = recordFuture;
            recordFuture = null;
            future.completeExceptionally( error );
        }
    }

    private ResultSummary extractResultSummary( Map<String,Value> metadata )
    {
        InternalServerInfo serverInfo = new InternalServerInfo( connection.serverAddress(),
                connection.serverVersion() );
        return new InternalResultSummary( statement, serverInfo, extractStatementType( metadata ),
                extractCounters( metadata ), extractPlan( metadata ), extractProfiledPlan( metadata ),
                extractNotifications( metadata ), runResponseHandler.resultAvailableAfter(),
                extractResultConsumedAfter( metadata ) );
    }

    private static StatementType extractStatementType( Map<String,Value> metadata )
    {
        Value typeValue = metadata.get( "type" );
        if ( typeValue != null )
        {
            return StatementType.fromCode( typeValue.asString() );
        }
        return null;
    }

    private static InternalSummaryCounters extractCounters( Map<String,Value> metadata )
    {
        Value countersValue = metadata.get( "stats" );
        if ( countersValue != null )
        {
            return new InternalSummaryCounters(
                    counterValue( countersValue, "nodes-created" ),
                    counterValue( countersValue, "nodes-deleted" ),
                    counterValue( countersValue, "relationships-created" ),
                    counterValue( countersValue, "relationships-deleted" ),
                    counterValue( countersValue, "properties-set" ),
                    counterValue( countersValue, "labels-added" ),
                    counterValue( countersValue, "labels-removed" ),
                    counterValue( countersValue, "indexes-added" ),
                    counterValue( countersValue, "indexes-removed" ),
                    counterValue( countersValue, "constraints-added" ),
                    counterValue( countersValue, "constraints-removed" )
            );
        }
        return null;
    }

    private static int counterValue( Value countersValue, String name )
    {
        Value value = countersValue.get( name );
        return value.isNull() ? 0 : value.asInt();
    }

    private static Plan extractPlan( Map<String,Value> metadata )
    {
        Value planValue = metadata.get( "plan" );
        if ( planValue != null )
        {
            return InternalPlan.EXPLAIN_PLAN_FROM_VALUE.apply( planValue );
        }
        return null;
    }

    private static ProfiledPlan extractProfiledPlan( Map<String,Value> metadata )
    {
        Value profiledPlanValue = metadata.get( "profile" );
        if ( profiledPlanValue != null )
        {
            return InternalProfiledPlan.PROFILED_PLAN_FROM_VALUE.apply( profiledPlanValue );
        }
        return null;
    }

    private static List<Notification> extractNotifications( Map<String,Value> metadata )
    {
        Value notificationsValue = metadata.get( "notifications" );
        if ( notificationsValue != null )
        {
            return notificationsValue.asList( InternalNotification.VALUE_TO_NOTIFICATION );
        }
        return Collections.emptyList();
    }

    private static long extractResultConsumedAfter( Map<String,Value> metadata )
    {
        Value resultConsumedAfterValue = metadata.get( "result_consumed_after" );
        if ( resultConsumedAfterValue != null )
        {
            return resultConsumedAfterValue.asLong();
        }
        return -1;
    }
}
