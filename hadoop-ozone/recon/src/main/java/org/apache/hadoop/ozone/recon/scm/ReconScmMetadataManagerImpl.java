/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.recon.scm;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.ha.SequenceIdGenerator;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStoreImpl;
import org.apache.hadoop.hdds.scm.server.OzoneStorageContainerManager;
import org.apache.hadoop.hdds.utils.db.DBColumnFamilyDefinition;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.DBStoreBuilder;
import org.apache.hadoop.hdds.utils.db.RDBStore;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.recon.ReconUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;

import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_SCM_SNAPSHOT_DB;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_SCM_DB_DIR;

/**
 * Recon's implementation of the SCM Metadata manager. By extending and
 * relying on the SCMMetadataStoreImpl, we can make sure all changes made to
 * schema in SCM will be automatically picked up by Recon.
 */
@Singleton
public class ReconScmMetadataManagerImpl extends SCMMetadataStoreImpl
    implements ReconScmMetadataManager {

  private static final Logger LOG =
      LoggerFactory.getLogger(ReconScmMetadataManagerImpl.class);

  private OzoneConfiguration ozoneConfiguration;
  private ReconUtils reconUtils;
  private OzoneStorageContainerManager ozoneStorageContainerManager;
  private SequenceIdGenerator sequenceIdGen;
  private ReconNodeManager nodeManager;

  @Inject
  public ReconScmMetadataManagerImpl(OzoneConfiguration configuration,
                                     ReconUtils reconUtils) {
    this.reconUtils = reconUtils;
    this.ozoneConfiguration = configuration;
  }

  @Override
  public void start(OzoneConfiguration configuration) throws IOException {
    LOG.info("Starting ReconScmMetadataManagerImpl...");
    File reconDbDir =
        reconUtils.getReconDbDir(configuration, OZONE_RECON_SCM_DB_DIR);
    File lastKnownSCMSnapshot =
        reconUtils.getLastKnownDB(reconDbDir, RECON_SCM_SNAPSHOT_DB);
    if (lastKnownSCMSnapshot != null) {
      LOG.info("Last known snapshot for SCM : {}", lastKnownSCMSnapshot.getAbsolutePath());
    }
  }

  /**
   * This method configures the DBStore with tables and codecs based on the
   * provided Recon SCM snapshot database definition. Each table from the
   * database definition is added to the DBStore, along with its associated
   * key and value codecs for serialization and deserialization purposes.
   *
   * @param dbFile The file location for the database.
   * @param definition the recon scm db definition for scm db snapshot.
   * @return A DBStore instance configured with tables and codecs.
   */
  private DBStore createDBAndAddSCMTablesAndCodecs(File dbFile,
                                                   ReconSCMSnapshotDBDefinition definition) throws IOException {
    DBStoreBuilder dbStoreBuilder =
        DBStoreBuilder.newBuilder(ozoneConfiguration)
            .setName(dbFile.getName())
            .setPath(dbFile.toPath().getParent());
    for (DBColumnFamilyDefinition columnFamily :
        definition.getColumnFamilies()) {
      dbStoreBuilder.addTable(columnFamily.getName());
      dbStoreBuilder.addCodec(columnFamily.getKeyType(),
          columnFamily.getKeyCodec());
      dbStoreBuilder.addCodec(columnFamily.getValueType(),
          columnFamily.getValueCodec());
    }
    return dbStoreBuilder.build();
  }

  /**
   * Replace existing DB instance with new one.
   *
   * @param dbFile new DB file location.
   */
  private void initializeRdbStoreWithFile(File dbFile)
      throws IOException {
    try {
      DBStore newStore = createDBAndAddSCMTablesAndCodecs(
          dbFile, new ReconSCMSnapshotDBDefinition());

      sequenceIdGen.reinitialize(
          ReconSCMSnapshotDBDefinition.SEQUENCE_ID.getTable(newStore));
      ozoneStorageContainerManager.getPipelineManager().reinitialize(
          ReconSCMSnapshotDBDefinition.PIPELINES.getTable(newStore));
      ozoneStorageContainerManager.getContainerManager().reinitialize(
          ReconSCMSnapshotDBDefinition.CONTAINERS.getTable(newStore));

      setStore(newStore);
      ozoneStorageContainerManager.setStore(newStore);
      LOG.info("Created SCM DB handle from snapshot at {} and sequence Id - {}.", dbFile.getAbsolutePath(),
          getLastSequenceNumberFromDB());
    } catch (IOException ioEx) {
      LOG.error("Unable to initialize Recon SCM DB snapshot store.", ioEx);
    }
    if (getStore() != null) {
      initializeScmTables();
    }
  }

  /**
   * Refresh the DB instance to point to a new location. Get rid of the old
   * DB instance.
   *
   * @param newDbLocation New location of the SCM Snapshot DB.
   */
  @Override
  public void updateScmDB(File newDbLocation) throws IOException {
    DBStore current = ozoneStorageContainerManager.getStore();
    if (null != current) {
      File oldDBLocation = current.getDbLocation();
      if (oldDBLocation.exists()) {
        LOG.info("Cleaning up old SCM snapshot db at {}.",
            oldDBLocation.getAbsolutePath());
        FileUtils.deleteDirectory(oldDBLocation);
      }
    }
    try {
      initializeRdbStoreWithFile(newDbLocation);
    } finally {
      // Always close DBStore if it's replaced.
      if (current != null && current != ozoneStorageContainerManager.getStore()) {
        current.close();
      }
    }
  }

  /**
   * Get SCM metadata RocksDB's latest sequence number.
   * @return latest sequence number.
   */
  @Override
  public long getLastSequenceNumberFromDB() {
    RDBStore rocksDBStore = (RDBStore) getStore();
    if (null == rocksDBStore) {
      return 0;
    } else {
      try {
        return rocksDBStore.getDb().getLatestSequenceNumber();
      } catch (IOException e) {
        LOG.error("Failed to access RocksDB at location: {}. Ensure the database path is correct and accessible.",
              rocksDBStore.getDbLocation().getAbsolutePath());
        return 0;
      }
    }
  }

  /**
   * Return table mapped to the specified table name.
   *
   * @param tableName
   * @return Table
   */
  @Override
  public Table getTable(String tableName) {
    Table table = getTableMap().get(tableName);
    if (table == null) {
      throw  new IllegalArgumentException("Unknown table " + tableName);
    }
    return table;
  }

  @Override
  public void setOzoneStorageContainerManager(
      OzoneStorageContainerManager ozoneStorageContainerManager) {
    this.ozoneStorageContainerManager = ozoneStorageContainerManager;
  }

  @Override
  public void setSequenceIdGen(SequenceIdGenerator sequenceIdGen) {
    this.sequenceIdGen = sequenceIdGen;
  }

  @Override
  public void setNodeManager(ReconNodeManager nodeManager) {
    this.nodeManager = nodeManager;
  }

  @Override
  public OzoneStorageContainerManager getOzoneStorageContainerManager() {
    return ozoneStorageContainerManager;
  }

  @Override
  public SequenceIdGenerator getSequenceIdGen() {
    return sequenceIdGen;
  }

  @Override
  public ReconNodeManager getNodeManager() {
    return nodeManager;
  }

}
