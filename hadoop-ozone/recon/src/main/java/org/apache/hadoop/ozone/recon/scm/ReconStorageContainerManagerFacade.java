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

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Preconditions;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.hdds.scm.PlacementPolicy;
import org.apache.hadoop.hdds.scm.ScmUtils;
import org.apache.hadoop.hdds.scm.block.BlockManager;
import org.apache.hadoop.hdds.scm.container.CloseContainerEventHandler;
import org.apache.hadoop.hdds.scm.container.ContainerActionsHandler;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.ContainerManager;
import org.apache.hadoop.hdds.scm.container.ContainerNotFoundException;
import org.apache.hadoop.hdds.scm.container.ContainerReplica;
import org.apache.hadoop.hdds.scm.container.ContainerReportHandler;
import org.apache.hadoop.hdds.scm.container.IncrementalContainerReportHandler;
import org.apache.hadoop.hdds.scm.container.common.helpers.ContainerWithPipeline;
import org.apache.hadoop.hdds.scm.container.replication.ContainerReplicaPendingOps;
import org.apache.hadoop.hdds.scm.container.replication.ReplicationManager;
import org.apache.hadoop.hdds.scm.container.balancer.ContainerBalancer;
import org.apache.hadoop.hdds.scm.container.placement.algorithms.ContainerPlacementPolicyFactory;
import org.apache.hadoop.hdds.scm.container.placement.algorithms.SCMContainerPlacementMetrics;
import org.apache.hadoop.hdds.scm.events.SCMEvents;
import org.apache.hadoop.hdds.scm.ha.SCMHAManagerStub;
import org.apache.hadoop.hdds.scm.ha.SCMContext;
import org.apache.hadoop.hdds.scm.ha.SCMNodeDetails;
import org.apache.hadoop.hdds.scm.ha.SCMHAManager;
import org.apache.hadoop.hdds.scm.ha.SequenceIdGenerator;
import org.apache.hadoop.hdds.scm.metadata.SCMDBTransactionBufferImpl;
import org.apache.hadoop.hdds.scm.net.NetworkTopology;
import org.apache.hadoop.hdds.scm.net.NetworkTopologyImpl;
import org.apache.hadoop.hdds.scm.node.DeadNodeHandler;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.node.NodeReportHandler;
import org.apache.hadoop.hdds.scm.node.StaleNodeHandler;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineActionHandler;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.scm.safemode.SafeModeManager;
import org.apache.hadoop.hdds.scm.server.OzoneStorageContainerManager;
import org.apache.hadoop.hdds.scm.server.SCMStorageConfig;
import org.apache.hadoop.hdds.server.events.EventExecutor;
import org.apache.hadoop.hdds.server.events.EventQueue;
import org.apache.hadoop.hdds.server.events.FixedThreadPoolWithAffinityExecutor;
import org.apache.hadoop.hdds.upgrade.HDDSLayoutVersionManager;
import org.apache.hadoop.hdds.utils.HddsServerUtil;
import org.apache.hadoop.hdds.utils.db.DBCheckpoint;
import org.apache.hadoop.hdds.utils.db.DBColumnFamilyDefinition;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.DBStoreBuilder;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.hdds.utils.db.Table.KeyValue;
import org.apache.hadoop.hdds.utils.db.TableIterator;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ozone.common.MonotonicClock;
import org.apache.hadoop.ozone.common.statemachine.InvalidStateTransitionException;
import org.apache.hadoop.ozone.recon.ReconServerConfigKeys;
import org.apache.hadoop.ozone.recon.ReconUtils;
import org.apache.hadoop.ozone.recon.fsck.ContainerHealthTask;
import org.apache.hadoop.ozone.recon.persistence.ContainerHealthSchemaManager;
import org.apache.hadoop.ozone.recon.spi.ReconContainerMetadataManager;
import org.apache.hadoop.ozone.recon.spi.StorageContainerServiceProvider;
import org.apache.hadoop.ozone.recon.tasks.ReconTaskConfig;
import com.google.inject.Inject;
import static org.apache.hadoop.hdds.recon.ReconConfigKeys.RECON_SCM_CONFIG_PREFIX;
import static org.apache.hadoop.hdds.scm.server.StorageContainerManager.buildRpcServerStartMessage;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_URI_DELIMITER;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_SCM_SNAPSHOT_TASK_INITIAL_DELAY;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_SCM_SNAPSHOT_TASK_INITIAL_DELAY_DEFAULT;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_SCM_SNAPSHOT_TASK_INTERVAL_DEFAULT;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_SCM_SNAPSHOT_TASK_INTERVAL_DELAY;

import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher.ContainerReport;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher.ContainerReportFromDatanode;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher.IncrementalContainerReportFromDatanode;

import org.apache.ratis.util.ExitUtils;
import org.hadoop.ozone.recon.schema.tables.daos.ReconTaskStatusDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recon's 'lite' version of SCM.
 */
public class ReconStorageContainerManagerFacade
    implements OzoneStorageContainerManager {

  // TODO: Fix Recon.

  private static final Logger LOG = LoggerFactory
      .getLogger(ReconStorageContainerManagerFacade.class);

  private final OzoneConfiguration ozoneConfiguration;
  private final ReconDatanodeProtocolServer datanodeProtocolServer;
  private final EventQueue eventQueue;
  private final SCMContext scmContext;
  private final SCMStorageConfig scmStorageConfig;
  private final SCMNodeDetails reconNodeDetails;
  private final SCMHAManager scmhaManager;
  private final SequenceIdGenerator sequenceIdGen;

  private DBStore dbStore;
  private ReconNodeManager nodeManager;
  private ReconPipelineManager pipelineManager;
  private ReconContainerManager containerManager;
  private NetworkTopology clusterMap;
  private StorageContainerServiceProvider scmServiceProvider;
  private Set<ReconScmTask> reconScmTasks = new HashSet<>();
  private SCMContainerPlacementMetrics placementMetrics;
  private PlacementPolicy containerPlacementPolicy;
  private HDDSLayoutVersionManager scmLayoutVersionManager;

  private ScheduledExecutorService scheduler;

  private AtomicBoolean isSyncDataFromSCMRunning;

  @Inject
  public ReconStorageContainerManagerFacade(OzoneConfiguration conf,
      StorageContainerServiceProvider scmServiceProvider,
      ReconTaskStatusDao reconTaskStatusDao,
      ContainerHealthSchemaManager containerHealthSchemaManager,
      ReconContainerMetadataManager reconContainerMetadataManager,
      ReconUtils reconUtils) throws IOException {
    reconNodeDetails = getReconNodeDetails(conf);
    this.eventQueue = new EventQueue();
    eventQueue.setSilent(true);
    this.scmContext = new SCMContext.Builder()
        .setIsPreCheckComplete(true)
        .setSCM(this)
        .build();
    this.ozoneConfiguration = getReconScmConfiguration(conf);
    this.scmStorageConfig = new ReconStorageConfig(conf, reconUtils);
    this.clusterMap = new NetworkTopologyImpl(conf);
    this.dbStore = DBStoreBuilder
        .createDBStore(ozoneConfiguration, new ReconSCMDBDefinition());

    this.scmLayoutVersionManager =
        new HDDSLayoutVersionManager(scmStorageConfig.getLayoutVersion());
    this.scmhaManager = SCMHAManagerStub.getInstance(
        true, new SCMDBTransactionBufferImpl());
    this.sequenceIdGen = new SequenceIdGenerator(
        conf, scmhaManager, ReconSCMDBDefinition.SEQUENCE_ID.getTable(dbStore));
    this.nodeManager =
        new ReconNodeManager(conf, scmStorageConfig, eventQueue, clusterMap,
            ReconSCMDBDefinition.NODES.getTable(dbStore),
            this.scmLayoutVersionManager);
    placementMetrics = SCMContainerPlacementMetrics.create();
    this.containerPlacementPolicy =
        ContainerPlacementPolicyFactory.getPolicy(conf, nodeManager,
            clusterMap, true, placementMetrics);
    this.datanodeProtocolServer = new ReconDatanodeProtocolServer(
        conf, this, eventQueue);
    this.pipelineManager = ReconPipelineManager.newReconPipelineManager(
        conf,
        nodeManager,
        ReconSCMDBDefinition.PIPELINES.getTable(dbStore),
        eventQueue,
        scmhaManager,
        scmContext);
    ContainerReplicaPendingOps pendingOps = new ContainerReplicaPendingOps(
        conf, new MonotonicClock(ZoneId.systemDefault()));
    this.containerManager = new ReconContainerManager(conf,
        dbStore,
        ReconSCMDBDefinition.CONTAINERS.getTable(dbStore),
        pipelineManager, scmServiceProvider,
        containerHealthSchemaManager, reconContainerMetadataManager,
        scmhaManager, sequenceIdGen, pendingOps);
    this.scmServiceProvider = scmServiceProvider;
    this.isSyncDataFromSCMRunning = new AtomicBoolean();

    NodeReportHandler nodeReportHandler =
        new NodeReportHandler(nodeManager);

    SafeModeManager safeModeManager = new ReconSafeModeManager();
    ReconPipelineReportHandler pipelineReportHandler =
        new ReconPipelineReportHandler(safeModeManager,
            pipelineManager, scmContext, conf, scmServiceProvider);

    PipelineActionHandler pipelineActionHandler =
        new PipelineActionHandler(pipelineManager, scmContext, conf);

    StaleNodeHandler staleNodeHandler =
        new StaleNodeHandler(nodeManager, pipelineManager, conf);
    DeadNodeHandler deadNodeHandler = new ReconDeadNodeHandler(nodeManager,
        pipelineManager, containerManager, scmServiceProvider);

    ContainerReportHandler containerReportHandler =
        new ReconContainerReportHandler(nodeManager, containerManager);

    IncrementalContainerReportHandler icrHandler =
        new ReconIncrementalContainerReportHandler(nodeManager,
            containerManager, scmContext);
    CloseContainerEventHandler closeContainerHandler =
        new CloseContainerEventHandler(
            pipelineManager, containerManager, scmContext);
    ContainerActionsHandler actionsHandler = new ContainerActionsHandler();
    ReconNewNodeHandler newNodeHandler = new ReconNewNodeHandler(nodeManager);

    // Use the same executor for both ICR and FCR.
    // The Executor maps the event to a thread for DN.
    // Dispatcher should always dispatch FCR first followed by ICR
    List<BlockingQueue<ContainerReport>> queues
        = ScmUtils.initContainerReportQueue(ozoneConfiguration);
    List<ThreadPoolExecutor> executors
        = FixedThreadPoolWithAffinityExecutor.initializeExecutorPool(queues);
    Map<String, FixedThreadPoolWithAffinityExecutor> reportExecutorMap
        = new ConcurrentHashMap<>();
    EventExecutor<ContainerReportFromDatanode>
        containerReportExecutors =
        new FixedThreadPoolWithAffinityExecutor<>(
            EventQueue.getExecutorName(SCMEvents.CONTAINER_REPORT,
                containerReportHandler),
            containerReportHandler, queues, eventQueue,
            ContainerReportFromDatanode.class, executors,
            reportExecutorMap);
    EventExecutor<IncrementalContainerReportFromDatanode>
        incrementalReportExecutors =
        new FixedThreadPoolWithAffinityExecutor<>(
            EventQueue.getExecutorName(
                SCMEvents.INCREMENTAL_CONTAINER_REPORT,
                icrHandler),
            icrHandler, queues, eventQueue,
            IncrementalContainerReportFromDatanode.class, executors,
            reportExecutorMap);
    eventQueue.addHandler(SCMEvents.CONTAINER_REPORT, containerReportExecutors,
        containerReportHandler);
    eventQueue.addHandler(SCMEvents.INCREMENTAL_CONTAINER_REPORT,
        incrementalReportExecutors, icrHandler);
    eventQueue.addHandler(SCMEvents.DATANODE_COMMAND, nodeManager);
    eventQueue.addHandler(SCMEvents.NODE_REPORT, nodeReportHandler);
    eventQueue.addHandler(SCMEvents.PIPELINE_REPORT, pipelineReportHandler);
    eventQueue.addHandler(SCMEvents.PIPELINE_ACTIONS, pipelineActionHandler);
    eventQueue.addHandler(SCMEvents.STALE_NODE, staleNodeHandler);
    eventQueue.addHandler(SCMEvents.DEAD_NODE, deadNodeHandler);
    eventQueue.addHandler(SCMEvents.CONTAINER_ACTIONS, actionsHandler);
    eventQueue.addHandler(SCMEvents.CLOSE_CONTAINER, closeContainerHandler);
    eventQueue.addHandler(SCMEvents.NEW_NODE, newNodeHandler);

    ReconTaskConfig reconTaskConfig = conf.getObject(ReconTaskConfig.class);
    reconScmTasks.add(new PipelineSyncTask(
        pipelineManager,
        nodeManager,
        scmServiceProvider,
        reconTaskStatusDao,
        reconTaskConfig));
    reconScmTasks.add(new ContainerHealthTask(
        containerManager,
        scmServiceProvider,
        reconTaskStatusDao, containerHealthSchemaManager,
        containerPlacementPolicy,
        reconTaskConfig));
  }

  /**
   *  For every config key which is prefixed by 'recon.scmconfig', create a new
   *  config key without the prefix keeping the same value.
   *  For example, if recon.scm.a.b. = xyz, we add a new config like
   *  a.b.c = xyz. This is done to override Recon's passive SCM configs if
   *  needed.
   * @param configuration configuration object.
   * @return same configuration object with possible added elements.
   */
  private OzoneConfiguration getReconScmConfiguration(
      OzoneConfiguration configuration) {
    OzoneConfiguration reconScmConfiguration =
        new OzoneConfiguration(configuration);
    Map<String, String> reconScmConfigs =
        configuration.getPropsMatchPrefixAndTrimPrefix(RECON_SCM_CONFIG_PREFIX);
    for (Map.Entry<String, String> entry : reconScmConfigs.entrySet()) {
      reconScmConfiguration.set(entry.getKey(), entry.getValue());
    }
    return reconScmConfiguration;
  }

  private SCMNodeDetails getReconNodeDetails(OzoneConfiguration conf) {
    SCMNodeDetails.Builder builder = new SCMNodeDetails.Builder();
    builder.setDatanodeProtocolServerAddress(
        HddsServerUtil.getReconDataNodeBindAddress(conf));
    return builder.build();
  }

  /**
   * Start the Recon SCM subsystems.
   */
  @Override
  public void start() {
    if (LOG.isInfoEnabled()) {
      LOG.info(buildRpcServerStartMessage(
          "Recon ScmDatanodeProtocol RPC server",
          getDatanodeProtocolServer().getDatanodeRpcAddress()));
    }
    scheduler = Executors.newScheduledThreadPool(1);
    boolean isSCMSnapshotEnabled = ozoneConfiguration.getBoolean(
        ReconServerConfigKeys.OZONE_RECON_SCM_SNAPSHOT_ENABLED,
        ReconServerConfigKeys.OZONE_RECON_SCM_SNAPSHOT_ENABLED_DEFAULT);
    if (isSCMSnapshotEnabled) {
      initializeSCMDB();
      LOG.info("SCM DB initialized");
    } else {
      initializePipelinesFromScm();
    }
    LOG.debug("Started the SCM Container Info sync scheduler.");
    long interval = ozoneConfiguration.getTimeDuration(
        OZONE_RECON_SCM_SNAPSHOT_TASK_INTERVAL_DELAY,
        OZONE_RECON_SCM_SNAPSHOT_TASK_INTERVAL_DEFAULT, TimeUnit.MILLISECONDS);
    long initialDelay = ozoneConfiguration.getTimeDuration(
        OZONE_RECON_SCM_SNAPSHOT_TASK_INITIAL_DELAY,
        OZONE_RECON_SCM_SNAPSHOT_TASK_INITIAL_DELAY_DEFAULT,
        TimeUnit.MILLISECONDS);
    // This periodic sync with SCM container cache is needed because during
    // the window when recon will be down and any container being added
    // newly and went missing, that container will not be reported as missing by
    // recon till there is a difference of container count equivalent to
    // threshold value defined in "ozone.recon.scm.container.threshold"
    // between SCM container cache and recon container cache.
    scheduler.scheduleWithFixedDelay(() -> {
      try {
        boolean isSuccess = syncSCMContainerInfoWithReconContainerInfo();
        if (!isSuccess) {
          LOG.debug("SCM container info sync is already running.");
        }
      } catch (Throwable t) {
        LOG.error("Unexpected exception while syncing data from SCM.", t);
      }
    },
        initialDelay,
        interval,
        TimeUnit.MILLISECONDS);
    getDatanodeProtocolServer().start();
    this.reconScmTasks.forEach(ReconScmTask::start);
  }

  /**
   * Wait until service has completed shutdown.
   */
  @Override
  public void join() {
    try {
      getDatanodeProtocolServer().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.info("Interrupted during StorageContainerManager join.");
    }
  }

  /**
   * Stop the Recon SCM subsystems.
   */
  @Override
  public void stop() {
    getDatanodeProtocolServer().stop();
    reconScmTasks.forEach(ReconScmTask::stop);
    try {
      LOG.info("Stopping SCM Event Queue.");
      eventQueue.close();
    } catch (Exception ex) {
      LOG.error("SCM Event Queue stop failed", ex);
    }
    IOUtils.cleanupWithLogger(LOG, nodeManager);
    IOUtils.cleanupWithLogger(LOG, containerManager);
    IOUtils.cleanupWithLogger(LOG, pipelineManager);
    LOG.info("Flushing container replica history to DB.");
    containerManager.flushReplicaHistoryMapToDB(true);
    try {
      dbStore.close();
    } catch (Exception e) {
      LOG.error("Can't close dbStore ", e);
    }
  }

  @Override
  public void shutDown(String message) {
    stop();
    ExitUtils.terminate(0, message, LOG);
  }

  public ReconDatanodeProtocolServer getDatanodeProtocolServer() {
    return datanodeProtocolServer;
  }

  private void initializePipelinesFromScm() {
    try {
      List<Pipeline> pipelinesFromScm = scmServiceProvider.getPipelines();
      LOG.info("Obtained {} pipelines from SCM.", pipelinesFromScm.size());
      pipelineManager.initializePipelines(pipelinesFromScm);
    } catch (IOException | TimeoutException ioEx) {
      LOG.error("Exception encountered while getting pipelines from SCM.",
          ioEx);
    }
  }

  private void initializeSCMDB() {
    try {
      long scmContainersCount = scmServiceProvider.getContainerCount();
      long reconContainerCount = containerManager.getContainers().size();
      long threshold = ozoneConfiguration.getInt(
          ReconServerConfigKeys.OZONE_RECON_SCM_CONTAINER_THRESHOLD,
          ReconServerConfigKeys.OZONE_RECON_SCM_CONTAINER_THRESHOLD_DEFAULT);

      if (Math.abs(scmContainersCount - reconContainerCount) > threshold) {
        LOG.info("Recon Container Count: {}, SCM Container Count: {}",
            reconContainerCount, scmContainersCount);
        updateReconSCMDBWithNewSnapshot();
        LOG.info("Updated Recon DB with SCM DB");
      } else {
        initializePipelinesFromScm();
      }
    } catch (IOException e) {
      LOG.error("Exception encountered while getting SCM DB.");
    }
  }

  public void updateReconSCMDBWithNewSnapshot() throws IOException {
    if (isSyncDataFromSCMRunning.compareAndSet(false, true)) {
      DBCheckpoint dbSnapshot = scmServiceProvider.getSCMDBSnapshot();
      if (dbSnapshot != null && dbSnapshot.getCheckpointLocation() != null) {
        LOG.info("Got new checkpoint from SCM : " +
            dbSnapshot.getCheckpointLocation());
        try {
          initializeNewRdbStore(dbSnapshot.getCheckpointLocation().toFile());
        } catch (IOException e) {
          LOG.error("Unable to refresh Recon SCM DB Snapshot. ", e);
        }
      } else {
        LOG.error("Null snapshot location got from SCM.");
      }
    } else {
      LOG.warn("SCM DB sync is already running.");
    }
  }

  public boolean syncSCMContainerInfoWithReconContainerInfo()
      throws IOException {
    if (isSyncDataFromSCMRunning.compareAndSet(false, true)) {
      try {
        List<ContainerInfo> containers = containerManager.getContainers();
        List<ContainerInfo> listOfContainers = scmServiceProvider.
            getListOfContainers();
        if (null != listOfContainers && listOfContainers.size() > 0) {
          LOG.info("Got list of containers frm SCM : " +
              listOfContainers.size());
          listOfContainers.forEach(containerInfo -> {
            try {
              long containerID = containerInfo.getContainerID();
              List<HddsProtos.SCMContainerReplicaProto> containerReplicas
                  = scmServiceProvider.getContainerReplicas(containerID);
              boolean isContainerPresentAtRecon =
                  containers.remove(containerID);
              if (!isContainerPresentAtRecon) {
                try {
                  ContainerWithPipeline containerWithPipeline =
                      scmServiceProvider.getContainerWithPipeline(containerID);
                  containerManager.addNewContainer(containerWithPipeline);
                } catch (IOException e) {
                  LOG.error("Could not get container with pipeline " +
                      "for container : {}", containerID);
                } catch (TimeoutException e) {
                  LOG.error("Could not add new container {} in Recon " +
                      "container manager cache.", containerID);
                }
              }
              synchronized (containerInfo) {
                containerReplicas.forEach(containerReplicaProto -> {
                  final ContainerReplica replica = buildContainerReplica(
                      containerInfo, containerReplicaProto);
                  try {
                    updateContainerState(containerInfo, replica);
                    containerManager.updateContainerReplica(
                        containerInfo.containerID(), replica);
                  } catch (ContainerNotFoundException e) {
                    LOG.error("Could not update container replica as " +
                        "container {} not found.", containerID);
                  } catch (InvalidStateTransitionException e) {
                    LOG.error("Invalid state transition for container {}",
                        containerID);
                  } catch (IOException e) {
                    LOG.error("Could not update container {} state.",
                        containerID);
                  } catch (TimeoutException e) {
                    LOG.error("Timeout while updating container {} state.",
                        containerID);
                  }
                });
              }
            } catch (IOException e) {
              LOG.error("Unable to get container replicas for container : {}",
                  containerInfo.getContainerID());
            }
          });
        } else {
          LOG.debug("SCM DB sync is already running.");
          return false;
        }
      } catch (IOException e) {
        LOG.error("Unable to refresh Recon SCM DB Snapshot. ", e);
        return false;
      }
    }
    return true;
  }

  private boolean updateContainerState(ContainerInfo container,
                                       ContainerReplica replica)
      throws InvalidStateTransitionException, IOException, TimeoutException {
    final ContainerID containerId = container.containerID();
    boolean ignored = false;
    switch (container.getState()) {
    case CLOSING:
      /*
       * When the container is in CLOSING state the replicas can be in any
       * of the following states:
       *
       * - OPEN
       * - CLOSING
       * - QUASI_CLOSED
       * - CLOSED
       *
       * If all the replica are either in OPEN or CLOSING state, do nothing.
       *
       * If the replica is in QUASI_CLOSED state, move the container to
       * QUASI_CLOSED state.
       *
       * If the replica is in CLOSED state, mark the container as CLOSED.
       *
       */

      if (replica.getState() == StorageContainerDatanodeProtocolProtos.
          ContainerReplicaProto.State.QUASI_CLOSED) {
        containerManager.updateContainerState(containerId,
            HddsProtos.LifeCycleEvent.QUASI_CLOSE);
      }

      if (replica.getState() == StorageContainerDatanodeProtocolProtos.
          ContainerReplicaProto.State.CLOSED) {
        Preconditions.checkArgument(replica.getSequenceId()
            == container.getSequenceId());
        containerManager.updateContainerState(containerId,
            HddsProtos.LifeCycleEvent.CLOSE);
      }

      break;
    case QUASI_CLOSED:
        /*
         * The container is in QUASI_CLOSED state, this means that at least
         * one of the replica was QUASI_CLOSED.
         *
         * Now replicas can be in any of the following state.
         *
         * 1. OPEN
         * 2. CLOSING
         * 3. QUASI_CLOSED
         * 4. CLOSED
         *
         * If at least one of the replica is in CLOSED state, mark the
         * container as CLOSED.
         *
         */
      if (replica.getState() == StorageContainerDatanodeProtocolProtos.
          ContainerReplicaProto.State.CLOSED) {
        Preconditions.checkArgument(replica.getSequenceId()
            == container.getSequenceId());
        containerManager.updateContainerState(containerId,
            HddsProtos.LifeCycleEvent.FORCE_CLOSE);
      }
      break;
    case CLOSED:
      /*
       * The container is already in closed state. do nothing.
       */
      break;
    case DELETING:
      /*
       * The container is under deleting. do nothing.
       */
      break;
    case DELETED:
      /*
       * The container is deleted. delete the replica do nothing
       *  as recon will not send any command to datanode
       */
      ignored = true;
      break;
    default:
      break;
    }
    return ignored;
  }

  private static ContainerReplica buildContainerReplica(
      ContainerInfo containerInfo,
      HddsProtos.SCMContainerReplicaProto containerReplicaProto) {
    final ContainerReplica replica = ContainerReplica.newBuilder()
        .setContainerID(containerInfo.containerID())
        .setContainerState(StorageContainerDatanodeProtocolProtos.
            ContainerReplicaProto.State.valueOf(
                containerReplicaProto.getState()))
        .setDatanodeDetails(DatanodeDetails.getFromProtoBuf(
            containerReplicaProto.getDatanodeDetails()))
        .setOriginNodeId(UUID.fromString(
            containerReplicaProto.getPlaceOfBirth()))
        .setSequenceId(containerReplicaProto.getSequenceID())
        .setKeyCount(containerReplicaProto.getKeyCount())
        .setReplicaIndex(containerReplicaProto.hasReplicaIndex()
            ? (int) containerReplicaProto.getReplicaIndex() : -1)
        .setBytesUsed(containerReplicaProto.getBytesUsed())
        .build();
    return replica;
  }

  private void deleteOldSCMDB() throws IOException {
    if (dbStore != null) {
      File oldDBLocation = dbStore.getDbLocation();
      if (oldDBLocation.exists()) {
        LOG.info("Cleaning up old SCM snapshot db at {}.",
            oldDBLocation.getAbsolutePath());
        FileUtils.deleteDirectory(oldDBLocation);
      }
    }
  }

  private void initializeNewRdbStore(File dbFile) throws IOException {
    try {
      DBStore newStore = createDBAndAddSCMTablesAndCodecs(
          dbFile, new ReconSCMDBDefinition());
      Table<UUID, DatanodeDetails> nodeTable =
          ReconSCMDBDefinition.NODES.getTable(dbStore);
      Table<UUID, DatanodeDetails> newNodeTable =
          ReconSCMDBDefinition.NODES.getTable(newStore);
      try (TableIterator<UUID, ? extends KeyValue<UUID,
          DatanodeDetails>> iterator = nodeTable.iterator()) {
        while (iterator.hasNext()) {
          KeyValue<UUID, DatanodeDetails> keyValue = iterator.next();
          newNodeTable.put(keyValue.getKey(), keyValue.getValue());
        }
      }
      sequenceIdGen.reinitialize(
          ReconSCMDBDefinition.SEQUENCE_ID.getTable(newStore));
      pipelineManager.reinitialize(
          ReconSCMDBDefinition.PIPELINES.getTable(newStore));
      containerManager.reinitialize(
          ReconSCMDBDefinition.CONTAINERS.getTable(newStore));
      nodeManager.reinitialize(
          ReconSCMDBDefinition.NODES.getTable(newStore));
      deleteOldSCMDB();
      setDbStore(newStore);
      File newDb = new File(dbFile.getParent() +
          OZONE_URI_DELIMITER + ReconSCMDBDefinition.RECON_SCM_DB_NAME);
      boolean success = dbFile.renameTo(newDb);
      if (success) {
        LOG.info("SCM snapshot linked to Recon DB.");
      }
      LOG.info("Created SCM DB handle from snapshot at {}.",
          dbFile.getAbsolutePath());
    } catch (IOException ioEx) {
      LOG.error("Unable to initialize Recon SCM DB snapshot store.", ioEx);
    }
  }

  private DBStore createDBAndAddSCMTablesAndCodecs(File dbFile,
      ReconSCMDBDefinition definition) throws IOException {
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

  public void setDbStore(DBStore dbStore) {
    this.dbStore = dbStore;
  }

  @Override
  public NodeManager getScmNodeManager() {
    return nodeManager;
  }

  @Override
  public BlockManager getScmBlockManager() {
    return null;
  }

  @Override
  public PipelineManager getPipelineManager() {
    return pipelineManager;
  }

  @Override
  public ContainerManager getContainerManager() {
    return containerManager;
  }

  @Override
  public ReplicationManager getReplicationManager() {
    return null;
  }

  @Override
  public ContainerBalancer getContainerBalancer() {
    return null;
  }

  @Override
  public InetSocketAddress getDatanodeRpcAddress() {
    return getDatanodeProtocolServer().getDatanodeRpcAddress();
  }

  @Override
  public SCMNodeDetails getScmNodeDetails() {
    return reconNodeDetails;
  }

  public EventQueue getEventQueue() {
    return eventQueue;
  }

  public StorageContainerServiceProvider getScmServiceProvider() {
    return scmServiceProvider;
  }
}
