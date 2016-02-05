/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.storeupgrade;

import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.api.KernelAPI;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.ha.HighlyAvailableGraphDatabase;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.impl.ha.ClusterManager;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.RecordStorageEngine;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.storemigration.StoreUpgrader;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.lifecycle.LifecycleException;
import org.neo4j.register.Register.DoubleLongRegister;
import org.neo4j.register.Registers;
import org.neo4j.server.Bootstrapper;
import org.neo4j.server.CommunityBootstrapper;
import org.neo4j.server.NeoServer;
import org.neo4j.server.ServerTestUtils;
import org.neo4j.server.configuration.ServerSettings;
import org.neo4j.server.database.Database;
import org.neo4j.test.SuppressOutput;
import org.neo4j.test.TargetDirectory;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.Unzip;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.neo4j.consistency.store.StoreAssertions.assertConsistentStore;
import static org.neo4j.helpers.collection.Iterables.concat;
import static org.neo4j.helpers.collection.Iterables.count;
import static org.neo4j.kernel.impl.ha.ClusterManager.allSeesAllAsAvailable;
import static org.neo4j.kernel.impl.ha.ClusterManager.clusterOfSize;

@RunWith( Enclosed.class )
public class StoreUpgradeIntegrationTest
{
    // NOTE: the zip files must contain the database files and NOT the graph.db folder itself!!!
    private static final List<Store[]> STORES19 = Collections.singletonList(
            new Store[]{new Store( "0.A.0-db.zip",
                    4 /* node count */,
                    4 /* last txId */,
                    selectivities(),
                    indexCounts()
            )} );
    private static final List<Store[]> STORES20 = Arrays.asList(
            new Store[]{new Store( "/upgrade/0.A.1-db.zip",
                    1071 /* node count */,
                    18 /* last txId */,
                    selectivities(),
                    indexCounts()
            )},
            new Store[]{new Store( "0.A.1-db2.zip",
                    180 /* node count */,
                    35 /* last txId */,
                    selectivities( 1.0, 1.0, 1.0, 1.0 ),
                    indexCounts( counts( 0, 1, 1, 1 ), counts( 0, 38, 38, 38 ),
                            counts( 0, 1, 1, 1 ), counts( 0, 133, 133, 133 ) )
            )} );
    private static final List<Store[]> STORES21 = Arrays.asList(
            new Store[]{new Store( "0.A.3-empty.zip",
                    0 /* node count */,
                    1 /* last txId */,
                    selectivities(),
                    indexCounts()
            )},
            new Store[]{new Store( "0.A.3-data.zip",
                    174 /* node count */,
                    30 /* last txId */,
                    selectivities( 1.0, 1.0, 1.0 ),
                    indexCounts( counts( 0, 38, 38, 38 ), counts( 0, 1, 1, 1 ), counts( 0, 133, 133, 133 ) )
            )} );
    private static final List<Store[]> STORES22 = Arrays.asList(
            new Store[]{new Store( "0.A.5-empty.zip",
                    0 /* node count */,
                    1 /* last txId */,
                    selectivities(),
                    indexCounts()
            )},
            new Store[]{new Store( "0.A.5-data.zip",
                    174 /* node count */,
                    30 /* last txId */,
                    selectivities( 1.0, 1.0, 1.0 ),
                    indexCounts( counts( 0, 38, 38, 38 ), counts( 0, 1, 1, 1 ), counts( 0, 133, 133, 133 ) )
            )} );
    private static final List<Store[]> STORES23 = Arrays.asList(
            new Store[]{new Store( "0.A.6-empty.zip",
                    0 /* node count */,
                    1 /* last txId */,
                    selectivities(),
                    indexCounts()
            )},
            new Store[]{new Store( "0.A.6-data.zip",
                    174 /* node count */,
                    30 /* last txId */,
                    selectivities( 1.0, 1.0, 1.0 ),
                    indexCounts( counts( 0, 38, 38, 38 ), counts( 0, 1, 1, 1 ), counts( 0, 133, 133, 133 ) )
            )} );

    @RunWith( Parameterized.class )
    public static class StoreUpgradeTest
    {
        @Parameterized.Parameter( 0 )
        public Store store;

        @Parameterized.Parameters( name = "{0}" )
        public static Collection<Store[]> stores()
        {
            return IteratorUtil.asCollection( Iterables.concat( STORES19, STORES20, STORES21, STORES22, STORES23 ) );
        }

        @Rule
        public SuppressOutput suppressOutput = SuppressOutput.suppressAll();
        @Rule
        public TargetDirectory.TestDirectory testDir = TargetDirectory.testDirForTest( getClass() );

        @Test
        public void embeddedDatabaseShouldStartOnOlderStoreWhenUpgradeIsEnabled() throws Throwable
        {
            File dir = store.prepareDirectory( testDir.graphDbDir() );

            GraphDatabaseFactory factory = new TestGraphDatabaseFactory();
            GraphDatabaseBuilder builder = factory.newEmbeddedDatabaseBuilder( dir );
            builder.setConfig( GraphDatabaseSettings.allow_store_upgrade, "true" );
            builder.setConfig( GraphDatabaseSettings.pagecache_memory, "8m" );
            GraphDatabaseService db = builder.newGraphDatabase();
            try
            {
                checkInstance( store, (GraphDatabaseAPI) db );

            }
            finally
            {
                db.shutdown();
            }

            assertConsistentStore( dir );
        }

        @Test
        public void serverDatabaseShouldStartOnOlderStoreWhenUpgradeIsEnabled() throws Throwable
        {
            File dir = store.prepareDirectory( testDir.graphDbDir() );

            File configFile = new File( dir, "neo4j.conf" );
            Properties props = new Properties();
            props.putAll( ServerTestUtils.getDefaultRelativeProperties() );
            props.setProperty( ServerSettings.legacy_db_location.name(), dir.getAbsolutePath() );
            props.setProperty( GraphDatabaseSettings.allow_store_upgrade.name(), "true" );
            props.setProperty( GraphDatabaseSettings.pagecache_memory.name(), "8m" );
            props.store( new FileWriter( configFile ), "" );

            try
            {
                Bootstrapper bootstrapper = new CommunityBootstrapper();
                bootstrapper.start( configFile );
                try
                {
                    NeoServer server = bootstrapper.getServer();
                    Database database = server.getDatabase();
                    assertTrue( database.isRunning() );
                    checkInstance( store, database.getGraph() );
                }
                finally
                {
                    bootstrapper.stop();
                }
            }
            finally
            {
                System.clearProperty( ServerSettings.SERVER_CONFIG_FILE_KEY );
            }

            assertConsistentStore( dir );
        }

        @Test
        public void migratingOlderDataAndThanStartAClusterUsingTheNewerDataShouldWork() throws Throwable
        {
            // migrate the store using a single instance
            File dir = store.prepareDirectory( testDir.graphDbDir() );
            GraphDatabaseFactory factory = new TestGraphDatabaseFactory();
            GraphDatabaseBuilder builder = factory.newEmbeddedDatabaseBuilder( dir );
            builder.setConfig( GraphDatabaseSettings.allow_store_upgrade, "true" );
            builder.setConfig( GraphDatabaseSettings.pagecache_memory, "8m" );
            GraphDatabaseService db = builder.newGraphDatabase();
            try
            {
                checkInstance( store, (GraphDatabaseAPI) db );
            }
            finally
            {
                db.shutdown();
            }

            assertConsistentStore( dir );

            // start the cluster with the db migrated from the old instance
            File haDir = new File( dir.getParentFile(), "ha-stuff" );
            FileUtils.deleteRecursively( haDir );
            ClusterManager clusterManager = new ClusterManager.Builder( haDir )
                    .withSeedDir( dir ).withProvider( clusterOfSize( 2 ) ).build();

            clusterManager.start();

            ClusterManager.ManagedCluster cluster = clusterManager.getDefaultCluster();
            HighlyAvailableGraphDatabase master, slave;
            try
            {
                cluster.await( allSeesAllAsAvailable() );

                master = cluster.getMaster();
                checkInstance( store, master );
                slave = cluster.getAnySlave();
                checkInstance( store, slave );
            }
            finally
            {
                clusterManager.shutdown();
            }

            assertConsistentStore( new File( master.getStoreDir() ) );
            assertConsistentStore( new File( slave.getStoreDir() ) );
        }
    }

    public static class StoreUpgradeFailingTest
    {
        @Rule
        public TargetDirectory.TestDirectory testDir = TargetDirectory.testDirForTest( getClass() );

        @Test
        public void migratingFromANotCleanlyShutdownStoreShouldNotStartAndFail() throws Throwable
        {
            // migrate the store using a single instance
            File dir = Unzip.unzip( getClass(), "0.A.3-to-be-recovered.zip", testDir.graphDbDir() );
            new File( dir, "messages.log" ).delete(); // clear the log
            GraphDatabaseFactory factory = new TestGraphDatabaseFactory();
            GraphDatabaseBuilder builder = factory.newEmbeddedDatabaseBuilder( dir );
            builder.setConfig( GraphDatabaseSettings.allow_store_upgrade, "true" );
            builder.setConfig( GraphDatabaseSettings.pagecache_memory, "8m" );
            try
            {
                GraphDatabaseService db = builder.newGraphDatabase();
                db.shutdown();
                fail( "It should have failed." );
            }
            catch ( RuntimeException ex )
            {
                assertTrue( ex.getCause() instanceof LifecycleException );
                Throwable realException = ex.getCause().getCause();
                assertTrue( Exceptions.contains( realException, MetaDataStore.DEFAULT_NAME,
                        StoreUpgrader.UnexpectedUpgradingStoreVersionException.class ) );
            }
        }
    }

    @RunWith( Parameterized.class )
    public static class StoreUpgrade22Test
    {
        @Parameterized.Parameter( 0 )
        public Store store;

        @Parameterized.Parameters( name = "{0}" )
        public static Collection<Store[]> stores()
        {
            return IteratorUtil.asCollection( Iterables.concat( STORES21, STORES22, STORES23 ) );
        }

        @Rule
        public TargetDirectory.TestDirectory testDir = TargetDirectory.testDirForTest( getClass() );

        @Test
        public void shouldBeAbleToUpgradeAStoreWithoutIdFilesAsBackups() throws Throwable
        {
            File dir = store.prepareDirectory( testDir.graphDbDir() );

            // remove id files
            File[] idFiles = dir.listFiles( new FilenameFilter()
            {
                @Override
                public boolean accept( File dir, String name )
                {
                    return name.endsWith( ".id" );
                }
            } );

            for ( File idFile : idFiles )
            {
                assertTrue( idFile.delete() );
            }

            GraphDatabaseFactory factory = new TestGraphDatabaseFactory();
            GraphDatabaseBuilder builder = factory.newEmbeddedDatabaseBuilder( dir );
            builder.setConfig( GraphDatabaseSettings.allow_store_upgrade, "true" );
            GraphDatabaseService db = builder.newGraphDatabase();
            try
            {
                checkInstance( store, (GraphDatabaseAPI) db );

            }
            finally
            {
                db.shutdown();
            }

            assertConsistentStore( dir );
        }
    }

    private static class Store
    {
        private final String resourceName;
        final long expectedNodeCount;
        final long lastTxId;
        private final double[] indexSelectivity;
        final long[][] indexCounts;

        private Store( String resourceName, long expectedNodeCount, long lastTxId,
                double[] indexSelectivity, long[][] indexCounts )
        {
            this.resourceName = resourceName;
            this.expectedNodeCount = expectedNodeCount;
            this.lastTxId = lastTxId;
            this.indexSelectivity = indexSelectivity;
            this.indexCounts = indexCounts;
        }

        public File prepareDirectory( File targetDir ) throws IOException
        {
            Unzip.unzip( getClass(), resourceName, targetDir );
            new File( targetDir, "messages.log" ).delete(); // clear the log
            return targetDir;
        }

        @Override
        public String toString()
        {
            return "Store: " + resourceName;
        }

        public long indexes()
        {
            return indexCounts.length;
        }
    }

    private static void checkInstance( Store store, GraphDatabaseAPI db ) throws KernelException
    {
        checkProvidedParameters( store, db );
        checkGlobalNodeCount( store, db );
        checkLabelCounts( db );
        checkIndexCounts( store, db );
    }

    private static void checkIndexCounts( Store store, GraphDatabaseAPI db ) throws KernelException
    {
        KernelAPI kernel = db.getDependencyResolver().resolveDependency( KernelAPI.class );
        try ( KernelTransaction tx = kernel.newTransaction(); Statement statement = tx.acquireStatement() )
        {
            Iterator<IndexDescriptor> indexes = getAllIndexes( db );
            DoubleLongRegister register = Registers.newDoubleLongRegister();
            for ( int i = 0; indexes.hasNext(); i++ )
            {
                IndexDescriptor descriptor = indexes.next();

                // wait index to be online since sometimes we need to rebuild the indexes on migration
                awaitOnline( db, statement.readOperations(), descriptor );

                assertDoubleLongEquals( store.indexCounts[i][0], store.indexCounts[i][1],
                        statement.readOperations().indexUpdatesAndSize( descriptor, register ) );
                assertDoubleLongEquals( store.indexCounts[i][2], store.indexCounts[i][3],
                        statement.readOperations().indexSample( descriptor, register ) );
                double selectivity = statement.readOperations().indexUniqueValuesSelectivity( descriptor );
                assertEquals( store.indexSelectivity[i], selectivity, 0.0000001d );
            }
        }
    }

    private static Iterator<IndexDescriptor> getAllIndexes( GraphDatabaseAPI db )
    {
        try ( Transaction ignored = db.beginTx() )
        {
            ThreadToStatementContextBridge bridge = db.getDependencyResolver()
                    .resolveDependency( ThreadToStatementContextBridge.class );
            Statement statement = bridge.get();
            return concat(
                    statement.readOperations().indexesGetAll(),
                    statement.readOperations().uniqueIndexesGetAll()
            );
        }
    }

    private static void checkLabelCounts( GraphDatabaseAPI db )
    {
        try ( Transaction ignored = db.beginTx() )
        {
            HashMap<Label,Long> counts = new HashMap<>();
            for ( Node node : db.getAllNodes() )
            {
                for ( Label label : node.getLabels() )
                {
                    Long count = counts.get( label );
                    if ( count != null )
                    {
                        counts.put( label, count + 1 );
                    }
                    else
                    {
                        counts.put( label, 1l );
                    }
                }
            }

            ThreadToStatementContextBridge bridge = db.getDependencyResolver()
                    .resolveDependency( ThreadToStatementContextBridge.class );
            Statement statement = bridge.get();

            for ( Map.Entry<Label,Long> entry : counts.entrySet() )
            {
                assertEquals(
                        entry.getValue().longValue(),
                        statement.readOperations().countsForNode(
                                statement.readOperations().labelGetForName( entry.getKey().name() ) )
                );
            }
        }
    }

    private static void checkGlobalNodeCount( Store store, GraphDatabaseAPI db )
    {
        try ( Transaction ignored = db.beginTx() )
        {
            ThreadToStatementContextBridge bridge = db.getDependencyResolver()
                    .resolveDependency( ThreadToStatementContextBridge.class );
            Statement statement = bridge.get();

            assertThat( statement.readOperations().countsForNode( -1 ), is( store.expectedNodeCount ) );
        }
    }

    private static void checkProvidedParameters( Store store, GraphDatabaseAPI db )
    {
        try ( Transaction ignored = db.beginTx() )
        {
            // count nodes
            long nodeCount = count( db.getAllNodes() );
            assertThat( nodeCount, is( store.expectedNodeCount ) );

            // count indexes
            long indexCount = count( db.schema().getIndexes() );
            assertThat( indexCount, is( store.indexes() ) );

            // check last committed tx
            TransactionIdStore txIdStore = db.getDependencyResolver().resolveDependency( TransactionIdStore.class );
            long lastCommittedTxId = txIdStore.getLastCommittedTransactionId();

            try ( Statement statement = db.getDependencyResolver()
                    .resolveDependency( ThreadToStatementContextBridge.class )
                    .getKernelTransactionBoundToThisThread( true ).acquireStatement() )
            {
                long countsTxId = db.getDependencyResolver().resolveDependency( RecordStorageEngine.class )
                        .testAccessNeoStores().getCounts().txId();
                assertEquals( lastCommittedTxId, countsTxId );
                assertThat( lastCommittedTxId, is( store.lastTxId ) );
            }
        }
    }

    private static void assertDoubleLongEquals( long expectedFirst, long expectedSecond, DoubleLongRegister register )
    {
        long first = register.readFirst();
        long second = register.readSecond();
        String msg = String.format( "Expected (%d,%d), got (%d,%d)", expectedFirst, expectedSecond, first, second );
        assertEquals( msg, expectedFirst, first );
        assertEquals( msg, expectedSecond, second );
    }

    private static double[] selectivities( double... selectivity )
    {
        return selectivity;
    }

    private static long[][] indexCounts( long[]... counts )
    {
        return counts;
    }

    private static long[] counts( long upgrade, long size, long unique, long sampleSize )
    {
        return new long[]{upgrade, size, unique, sampleSize};
    }

    private static IndexDescriptor awaitOnline( GraphDatabaseAPI db,
            ReadOperations readOperations, IndexDescriptor index ) throws KernelException
    {
        long start = System.currentTimeMillis();
        long end = start + 20_000;
        while ( System.currentTimeMillis() < end )
        {
            switch ( readOperations.indexGetState( index ) )
            {
            case ONLINE:
                return index;

            case FAILED:
                throw new IllegalStateException( "Index failed instead of becoming ONLINE" );

            default:
                break;
            }

            try
            {
                Thread.sleep( 100 );
            }
            catch ( InterruptedException e )
            {
                // ignored
            }
        }
        throw new IllegalStateException( "Index did not become ONLINE within reasonable time" );
    }
}
