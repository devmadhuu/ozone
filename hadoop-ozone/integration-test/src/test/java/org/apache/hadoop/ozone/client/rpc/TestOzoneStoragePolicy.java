/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.client.rpc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_DEFAULT_STORAGE_POLICY_DEFAULT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_DEFAULT_STORAGE_POLICY_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdds.client.DefaultReplicationConfig;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.client.OzoneStoragePolicy;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.client.StoragePolicy;
import org.apache.hadoop.hdds.client.StorageTier;
import org.apache.hadoop.hdds.client.StorageTierUtil;
import org.apache.hadoop.hdds.client.StorageTypeUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.conf.StorageUnit;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.XceiverClientGrpc;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.ContainerManager;
import org.apache.hadoop.hdds.scm.container.ContainerNotFoundException;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.scm.pipeline.PipelineNotFoundException;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.hdds.scm.storage.ContainerProtocolCalls;
import org.apache.hadoop.hdds.utils.IOUtils;
import org.apache.hadoop.ozone.ClientConfigForTesting;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.client.BucketArgs;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.io.OzoneDataStreamOutput;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.container.common.impl.ContainerData;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmMultipartInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * This class is to test the StoragePolicy related IO operation.
 */
public class TestOzoneStoragePolicy {
  private static OzoneClient ozClient = null;
  private static ObjectStore store = null;
  private static OzoneManager ozoneManager;
  private static MiniOzoneCluster cluster = null;
  private OzoneConfiguration conf = new OzoneConfiguration();
  private static ContainerManager containerManager;
  private static PipelineManager pipelineManager;

  /**
   * Create a MiniOzoneCluster for testing.
   * @param conf Configurations to start the cluster.
   * @throws Exception
   */
  static void startCluster(OzoneConfiguration conf,
      List<List<StorageType>> storageTypeList, int datanodeCount, int dataVolumesCount) throws Exception {
    conf.setBoolean(OzoneConfigKeys.HDDS_CONTAINER_RATIS_DATASTREAM_ENABLED,
        true);
    conf.setBoolean(
        OzoneConfigKeys.HDDS_CONTAINER_RATIS_DATASTREAM_RANDOM_PORT, true);
    cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(datanodeCount)
        .setNumDataVolumes(dataVolumesCount)
        .setDatanodeStorageType(storageTypeList)
        .build();
    cluster.waitForClusterToBeReady();
    ozClient = OzoneClientFactory.getRpcClient(conf);
    store = ozClient.getObjectStore();
    ozoneManager = cluster.getOzoneManager();
    containerManager = cluster.getStorageContainerManager().getContainerManager();
    pipelineManager = cluster.getStorageContainerManager().getPipelineManager();
  }

  @AfterEach
  void shutdownCluster() throws IOException {
    IOUtils.closeQuietly(ozClient);

    if (cluster != null) {
      cluster.shutdown();
    }
  }

  public static Stream<Arguments> replicaTypeAll() {
    return Stream.of(
        arguments("RATIS", "ONE", BucketLayout.FILE_SYSTEM_OPTIMIZED),
        arguments("RATIS", "ONE", BucketLayout.OBJECT_STORE),
        arguments("RATIS", "THREE", BucketLayout.FILE_SYSTEM_OPTIMIZED),
        arguments("RATIS", "THREE", BucketLayout.OBJECT_STORE),
        arguments("EC", "RS-3-2-1024k", BucketLayout.FILE_SYSTEM_OPTIMIZED),
        arguments("EC", "RS-10-4-1024k", BucketLayout.OBJECT_STORE)
    );
  }

  public static Stream<Arguments> replicaType() {
    return Stream.of(
        arguments("RATIS", "THREE", BucketLayout.FILE_SYSTEM_OPTIMIZED),
        arguments("RATIS", "THREE", BucketLayout.OBJECT_STORE),
        arguments("EC", "RS-3-2-1024k", BucketLayout.FILE_SYSTEM_OPTIMIZED),
        arguments("EC", "RS-10-4-1024k", BucketLayout.OBJECT_STORE)
    );
  }

  ReplicationConfig getReplicationConfig(String type, String replication) {
    ReplicationConfig replicationConfig;
    if (type.equals("EC")) {
      replicationConfig = new ECReplicationConfig(replication);
    } else {
      replicationConfig = ReplicationConfig
          .fromTypeAndFactor(ReplicationType.valueOf(type), ReplicationFactor.valueOf(replication));
    }
    return replicationConfig;
  }

  @ParameterizedTest
  @MethodSource("replicaTypeAll")
  public void testStoragePolicy(
      String type, String replication, BucketLayout bucketLayout) throws Exception {
    ReplicationConfig replicationConfig = getReplicationConfig(type, replication);
    final int blockSizeMb = 4;
    ClientConfigForTesting.newBuilder(StorageUnit.MB)
        .setBlockSize(blockSizeMb).applyTo(conf);

    // Create a Cluster with 3 DNs, echo DN has three different type StorageType Volumes
    List<List<StorageType>> storageTypeList = new ArrayList<>();
    for (int i = 1; i <= replicationConfig.getRequiredNodes(); i++) {
      storageTypeList.add(Arrays.asList(StorageType.DISK, StorageType.SSD, StorageType.ARCHIVE));
    }
    startCluster(conf, storageTypeList, replicationConfig.getRequiredNodes(), 3);
    List<OzoneStoragePolicy> storagePolicies = new ArrayList<>(Arrays.asList(OzoneStoragePolicy.values()));
    storagePolicies.add(null);

    for (OzoneStoragePolicy storagePolicy : storagePolicies) {
      OmKeyInfo keyInfo = createRandomNameKeyAndGet(replicationConfig, storagePolicy, false,
          bucketLayout);
      OzoneStoragePolicy expectedPolicy = getFinialStoragePolicy(storagePolicy);

      assertKeyInfo(keyInfo, 1, expectedPolicy, replicationConfig);
      assertSCMPipelineAndContainer(keyInfo, expectedPolicy.getCreationTier(), replicationConfig);
      assertDNContainerAndBlock(keyInfo, expectedPolicy.getCreationTier(), replicationConfig);

      if (replicationConfig.getReplicationType() !=
          HddsProtos.ReplicationType.EC) {
        keyInfo = createRandomNameStreamingKeyAndGet(replicationConfig,
            storagePolicy, false, bucketLayout,
            new byte[blockSizeMb * 1024 * 1024]);
        assertKeyInfo(keyInfo, 2, expectedPolicy, replicationConfig);
        assertSCMPipelineAndContainer(keyInfo,
            expectedPolicy.getCreationTier(), replicationConfig);
        assertDNContainerAndBlock(keyInfo,
            expectedPolicy.getCreationTier(), replicationConfig);

        keyInfo = createRandomNameStreamingFileAndGet(replicationConfig,
            storagePolicy, false, bucketLayout);
        assertKeyInfo(keyInfo, 1, expectedPolicy, replicationConfig);
        assertSCMPipelineAndContainer(keyInfo,
            expectedPolicy.getCreationTier(), replicationConfig);
        assertDNContainerAndBlock(keyInfo,
            expectedPolicy.getCreationTier(), replicationConfig);
      }

      keyInfo = createRandomNameFileAndGet(replicationConfig, storagePolicy, false, bucketLayout);
      assertKeyInfo(keyInfo, 1, expectedPolicy, replicationConfig);
      assertSCMPipelineAndContainer(keyInfo, expectedPolicy.getCreationTier(), replicationConfig);
      assertDNContainerAndBlock(keyInfo, expectedPolicy.getCreationTier(), replicationConfig);
    }
  }

  @ParameterizedTest
  @MethodSource("replicaType")
  public void testStoragePolicyWithDefaultVolume(
      String type, String replication, BucketLayout bucketLayout) throws Exception {
    ReplicationConfig replicationConfig = getReplicationConfig(type, replication);

    // Create a Cluster with 3 DNs, echo DN has three default (DISK) StorageType Volumes
    List<List<StorageType>> storageTypeList = new ArrayList<>();
    for (int i = 1; i <= replicationConfig.getRequiredNodes(); i++) {
      storageTypeList.add(Arrays.asList(StorageType.DISK, StorageType.DISK, StorageType.DISK));
    }
    startCluster(conf, storageTypeList, replicationConfig.getRequiredNodes(), 3);

    List<OzoneStoragePolicy> storagePolicies = new ArrayList<>(Arrays.asList(OzoneStoragePolicy.values()));
    StorageTier expectedStorageTier;
    for (OzoneStoragePolicy storagePolicy : storagePolicies) {
      OmKeyInfo keyInfo;
      if (storagePolicy == OzoneStoragePolicy.WARM) {
        keyInfo = createRandomNameKeyAndGet(replicationConfig, storagePolicy, false, bucketLayout);
        expectedStorageTier = storagePolicy.getCreationTier();
      } else {
        // Only Disk StorageType Volume, so others storagePolicy will fail
        // except the OzoneStoragePolicy.WARM
        assertThrows(OMException.class,
            () -> createRandomNameKeyAndGet(replicationConfig, storagePolicy, false, bucketLayout));
        if (storagePolicy.getCreationFallbackTier() == StorageTier.DISK) {
          // If the CreationFallbackTier is StorageTier.DISK and enable the fallback, then the
          // key creation should succeed
          keyInfo = createRandomNameKeyAndGet(replicationConfig, storagePolicy, true, bucketLayout);
          expectedStorageTier = storagePolicy.getCreationFallbackTier();
        } else {
          continue;
        }
      }

      assertKeyInfo(keyInfo, 1, storagePolicy, replicationConfig);
      assertSCMPipelineAndContainer(keyInfo, expectedStorageTier, replicationConfig);
      assertDNContainerAndBlock(keyInfo, expectedStorageTier, replicationConfig);
    }
  }

  @ParameterizedTest
  @MethodSource("replicaType")
  public void testBucketStoragePolicy(
      String type, String replication, BucketLayout bucketLayout) throws Exception {
    ReplicationConfig replicationConfig = getReplicationConfig(type, replication);

    // Create a Cluster with 3 DNs, echo DN has three different type StorageType Volumes
    List<List<StorageType>> storageTypeList = new ArrayList<>();
    for (int i = 1; i <= replicationConfig.getRequiredNodes(); i++) {
      storageTypeList.add(Arrays.asList(StorageType.DISK, StorageType.SSD, StorageType.ARCHIVE));
    }
    startCluster(conf, storageTypeList, replicationConfig.getRequiredNodes(), 3);

    List<OzoneStoragePolicy> storagePolicies = new ArrayList<>(Arrays.asList(OzoneStoragePolicy.values()));
    for (OzoneStoragePolicy bucketStoragePolicy : storagePolicies) {
      // If no StoragePolicy is specified in keyArgs when writing a key,
      // then the key's StoragePolicy inherits the bucket Object StoragePolicy.
      OzoneBucket ozoneBucket = createBucketWithStoragePolicyAndGet(bucketStoragePolicy, replicationConfig,
          bucketLayout);
      assertEquals(bucketStoragePolicy, ozoneBucket.getStoragePolicy());
      OmKeyInfo keyInfo = createKeyWithStoragePolicyAndGet(ozoneBucket, null, null);
      assertKeyInfo(keyInfo, 1, bucketStoragePolicy, replicationConfig);

      // If a StoragePolicy is specified in keyArgs when writing a key,
      // the StoragePolicy of keyArgs takes precedence.
      int currentIndex = storagePolicies.indexOf(bucketStoragePolicy);
      // Create key with another StoragePolicy
      OzoneStoragePolicy anotherStoragePolicy = storagePolicies.get((currentIndex + 1) % storagePolicies.size());
      keyInfo = createKeyWithStoragePolicyAndGet(ozoneBucket, anotherStoragePolicy, null);
      assertKeyInfo(keyInfo, 1, anotherStoragePolicy, replicationConfig);
      assertSCMPipelineAndContainer(keyInfo, anotherStoragePolicy.getCreationTier(), replicationConfig);
      assertDNContainerAndBlock(keyInfo, anotherStoragePolicy.getCreationTier(), replicationConfig);
    }
  }

  @ParameterizedTest
  @MethodSource("replicaType")
  public void testClosePipelineThenWriteKeyMultipleTimes(
      String type, String replication, BucketLayout bucketLayout) throws Exception {
    ReplicationConfig replicationConfig = getReplicationConfig(type, replication);
    int times = 10;

    // Create a Cluster with 3 DNs, echo DN has three different type StorageType Volumes
    List<List<StorageType>> storageTypeList = new ArrayList<>();
    for (int i = 1; i <= replicationConfig.getRequiredNodes(); i++) {
      storageTypeList.add(Arrays.asList(StorageType.DISK, StorageType.SSD, StorageType.ARCHIVE));
    }
    startCluster(conf, storageTypeList, replicationConfig.getRequiredNodes(), 3);
    List<OzoneStoragePolicy> storagePolicies = new ArrayList<>(Arrays.asList(OzoneStoragePolicy.values()));
    closeAllPipelines(replicationConfig);

    for (OzoneStoragePolicy storagePolicy : storagePolicies) {
      OzoneBucket ozoneBucket = createBucketWithStoragePolicyAndGet(storagePolicy, replicationConfig,
          bucketLayout);
      assertEquals(storagePolicy, ozoneBucket.getStoragePolicy());
      for (int i = 0; i < times; i++) {
        OmKeyInfo keyInfo = createKeyWithStoragePolicyAndGet(ozoneBucket, storagePolicy, replicationConfig);

        assertKeyInfo(keyInfo, 1, storagePolicy, replicationConfig);
        assertSCMPipelineAndContainer(keyInfo, storagePolicy.getCreationTier(), replicationConfig);
        assertDNContainerAndBlock(keyInfo, storagePolicy.getCreationTier(), replicationConfig);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("replicaType")
  public void testWriteKeyMultipleTimesWithStoragePolicy(
      String type, String replication, BucketLayout bucketLayout) throws Exception {
    ReplicationConfig replicationConfig = getReplicationConfig(type, replication);
    int times = 10;

    // Create a Cluster with 3 DNs, echo DN has three different type StorageType Volumes
    List<List<StorageType>> storageTypeList = new ArrayList<>();
    for (int i = 1; i <= replicationConfig.getRequiredNodes(); i++) {
      storageTypeList.add(Arrays.asList(StorageType.DISK, StorageType.SSD, StorageType.ARCHIVE));
    }
    startCluster(conf, storageTypeList, replicationConfig.getRequiredNodes(), 3);
    List<OzoneStoragePolicy> storagePolicies = new ArrayList<>(Arrays.asList(OzoneStoragePolicy.values()));

    for (OzoneStoragePolicy storagePolicy : storagePolicies) {
      OzoneBucket ozoneBucket = createBucketWithStoragePolicyAndGet(storagePolicy, replicationConfig,
          bucketLayout);
      assertEquals(storagePolicy, ozoneBucket.getStoragePolicy());
      for (int i = 0; i < times; i++) {
        OmKeyInfo keyInfo = createKeyWithStoragePolicyAndGet(ozoneBucket, storagePolicy, replicationConfig);

        assertKeyInfo(keyInfo, 1, storagePolicy, replicationConfig);
        assertSCMPipelineAndContainer(keyInfo, storagePolicy.getCreationTier(), replicationConfig);
        assertDNContainerAndBlock(keyInfo, storagePolicy.getCreationTier(), replicationConfig);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("replicaType")
  public void testMultipartUploadWithStoragePolicy(
      String type, String replication, BucketLayout bucketLayout) throws Exception {
    ReplicationConfig replicationConfig = getReplicationConfig(type, replication);
    ClientConfigForTesting.newBuilder(StorageUnit.MB).setBlockSize(16).applyTo(conf);
    List<List<StorageType>> storageTypeList = new ArrayList<>();
    for (int i = 0; i < replicationConfig.getRequiredNodes(); i++) {
      storageTypeList.add(Arrays.asList(StorageType.DISK, StorageType.SSD, StorageType.ARCHIVE));
    }
    startCluster(conf, storageTypeList, replicationConfig.getRequiredNodes(), 3);

    OzoneBucket bucket =
        createBucketWithStoragePolicyAndGet(OzoneStoragePolicy.HOT, replicationConfig, bucketLayout);
    for (boolean streamingWrite : Arrays.asList(false, true)) {
      if (replicationConfig.getReplicationType() ==
          HddsProtos.ReplicationType.EC &&
          streamingWrite) {
        continue;
      }
      OmKeyInfo keyInfo = createMultipartKeyAndGet(
          bucket, replicationConfig, null, streamingWrite);
      assertKeyInfo(keyInfo, 2, OzoneStoragePolicy.HOT, replicationConfig);
      assertDNContainerAndBlock(keyInfo,
          OzoneStoragePolicy.HOT.getCreationTier(), replicationConfig);

      keyInfo = createMultipartKeyAndGet(
          bucket, replicationConfig, OzoneStoragePolicy.COLD, streamingWrite);
      assertKeyInfo(keyInfo, 2, OzoneStoragePolicy.COLD, replicationConfig);
      assertDNContainerAndBlock(keyInfo,
          OzoneStoragePolicy.COLD.getCreationTier(), replicationConfig);
    }
  }

  private void closeAllPipelines(ReplicationConfig replicationConfig) throws Exception {
    StorageContainerManager scm = cluster.getStorageContainerManager();
    scm.getPipelineManager().getPipelines(replicationConfig, Pipeline.PipelineState.OPEN)
        .forEach(p -> {
          try {
            scm.getPipelineManager().closePipeline(p.getId());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private OzoneStoragePolicy getFinialStoragePolicy(OzoneStoragePolicy storagePolicy) {
    OzoneStoragePolicy policy;
    if (storagePolicy == null) {
      // If not specific storagePolicy in the key and bucket,
      // then the storagePolicy will be default storagePolicy;
      policy = OzoneStoragePolicy.valueOf(conf.get(
          OZONE_DEFAULT_STORAGE_POLICY_KEY, OZONE_DEFAULT_STORAGE_POLICY_DEFAULT));
    } else {
      policy = storagePolicy;
    }
    return policy;
  }

  private void assertKeyInfo(OmKeyInfo keyInfo, int expectedBlockCount, OzoneStoragePolicy expectedPolicy,
      ReplicationConfig expectedReplication) {
    assertEquals(1, keyInfo.getKeyLocationVersions().size());
    assertNotNull(keyInfo.getLatestVersionLocations().getBlocksLatestVersionOnly());
    assertEquals(expectedBlockCount, keyInfo.getLatestVersionLocations().getBlocksLatestVersionOnly().size());
    assertEquals(expectedPolicy, keyInfo.getStoragePolicy());
    assertEquals(expectedReplication, keyInfo.getReplicationConfig());
  }

  private void assertSCMPipelineAndContainer(OmKeyInfo keyInfo, StorageTier exceptedStorageTier,
      ReplicationConfig expectedReplication)
      throws ContainerNotFoundException, PipelineNotFoundException {
    OmKeyLocationInfo omKeyLocationInfo = keyInfo.getLatestVersionLocations().getBlocksLatestVersionOnly().get(0);
    long containerID = omKeyLocationInfo.getContainerID();
    ContainerInfo scmContainerInfo = containerManager.getContainer(ContainerID.valueOf(containerID));
    Pipeline pipeline = pipelineManager.getPipeline(scmContainerInfo.getPipelineID());
    // Assert SCM Container and Pipeline
    assertEquals(expectedReplication, pipeline.getReplicationConfig());
    assertEquals(exceptedStorageTier, scmContainerInfo.getStorageTier(), "pipeline" + pipeline.getId());
    assertEquals(Pipeline.PipelineState.OPEN, pipeline.getPipelineState());
    assertEquals(expectedReplication.getRequiredNodes(), pipeline.getNodeSet().size());
    assertEquals(exceptedStorageTier, pipeline.getSupportedStorageTier());
  }

  private void assertDNContainerAndBlock(OmKeyInfo keyInfo,
      StorageTier expectedStorageTier, ReplicationConfig expectedReplication) throws IOException {
    StorageType expectedStorageType =
        StorageTierUtil.getStorageTypeForUniformStorageTier(expectedStorageTier);
    OmKeyLocationInfo omKeyLocationInfo =
        keyInfo.getLatestVersionLocations().getBlocksLatestVersionOnly().get(0);
    long containerID = omKeyLocationInfo.getContainerID();
    ContainerInfo scmContainerInfo =
        containerManager.getContainer(ContainerID.valueOf(containerID));
    Pipeline pipeline = pipelineManager.getPipeline(scmContainerInfo.getPipelineID());

    XceiverClientGrpc client = new XceiverClientGrpc(pipeline, conf);
    int keyNodeCount = 0;
    for (HddsDatanodeService hddsDatanode : cluster.getHddsDatanodes()) {
      // Assert Datanode Container replica
      Container<?> datanodeContainer = hddsDatanode.getDatanodeStateMachine()
          .getContainer().getContainerSet().getContainer(containerID);
      if (datanodeContainer == null) {
        continue;
      }
      ContainerData containerData = datanodeContainer.getContainerData();
      assertEquals(expectedStorageType, containerData.getStorageType());
      assertEquals(expectedStorageType, containerData.getVolume().getStorageType());
      assertTrue(pipeline.getNodeSet().contains(hddsDatanode.getDatanodeDetails()));
      // Assert Datanode Block replica
      ContainerProtos.BlockData blockData =
          ContainerProtocolCalls.getBlock(client,
              omKeyLocationInfo.getBlockID(), null,
              client.getPipeline().getReplicaIndexes()).getBlockData();
      assertEquals(expectedStorageType,
          StorageTypeUtils.getStorageTypeFromID(blockData.getBlockID().getStorageTypeID()));
      keyNodeCount++;
    }
    assertEquals(keyNodeCount, expectedReplication.getRequiredNodes());
    client.close();
  }

  private OzoneBucket createBucketWithStoragePolicyAndGet(StoragePolicy storagePolicy,
      ReplicationConfig replicationConfig, BucketLayout bucketLayout) throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    BucketArgs bucketArgs = BucketArgs.newBuilder()
        .setStoragePolicy(storagePolicy)
        .setDefaultReplicationConfig(new DefaultReplicationConfig(replicationConfig))
        .setBucketLayout(bucketLayout)
        .build();
    store.getVolume(volumeName).createBucket(bucketName, bucketArgs);
    return store.getVolume(volumeName).getBucket(bucketName);
  }

  private OmKeyInfo createKeyWithStoragePolicyAndGet(
      OzoneBucket ozoneBucket, StoragePolicy storagePolicy, ReplicationConfig replicationConfig) throws IOException {
    String keyValue = "value";
    String keyName = UUID.randomUUID().toString();
    OzoneOutputStream out = ozoneBucket.createKey(keyName,
        keyValue.getBytes(UTF_8).length, replicationConfig,
        new HashMap<>(), new HashMap<>(), storagePolicy);
    out.write(keyValue.getBytes(UTF_8));
    out.close();

    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(ozoneBucket.getVolumeName())
        .setBucketName(ozoneBucket.getName())
        .setKeyName(keyName)
        .build();
    return ozoneManager.lookupKey(keyArgs);
  }

  private OmKeyInfo createRandomNameKeyAndGet(ReplicationConfig replicationConfig,
      StoragePolicy storagePolicy, boolean allowFallback, BucketLayout bucketLayout) throws IOException {
    return createRandomKeyOrFile(storagePolicy, allowFallback, bucketLayout,
        null,
        (bucket, keyName, length, policy) -> bucket.createKey(keyName, length,
            replicationConfig, new HashMap<>(), new HashMap<>(), policy));
  }

  private OmKeyInfo createRandomNameStreamingKeyAndGet(
      ReplicationConfig replicationConfig, StoragePolicy storagePolicy,
      boolean allowFallback, BucketLayout bucketLayout, byte[] extraData)
      throws IOException {
    return createRandomKeyOrFile(storagePolicy, allowFallback, bucketLayout,
        extraData,
        (bucket, keyName, length, policy) -> bucket.createStreamKey(keyName,
            length, replicationConfig, new HashMap<>(), new HashMap<>(),
            policy));
  }

  private OmKeyInfo createRandomNameFileAndGet(ReplicationConfig replicationConfig,
      StoragePolicy storagePolicy, boolean allowFallback, BucketLayout bucketLayout) throws IOException {
    return createRandomKeyOrFile(storagePolicy, allowFallback, bucketLayout,
        null,
        (bucket, keyName, length, policy) -> bucket.createFile(keyName, length,
            replicationConfig, false, false, policy));
  }

  private OmKeyInfo createRandomNameStreamingFileAndGet(
      ReplicationConfig replicationConfig, StoragePolicy storagePolicy,
      boolean allowFallback, BucketLayout bucketLayout) throws IOException {
    return createRandomKeyOrFile(storagePolicy, allowFallback, bucketLayout,
        null,
        (bucket, keyName, length, policy) -> bucket.createStreamFile(keyName,
            length, replicationConfig, false, false, policy));
  }

  private OmKeyInfo createRandomKeyOrFile(StoragePolicy storagePolicy,
      boolean allowFallback, BucketLayout bucketLayout, byte[] extraData,
      KeyCreator keyCreator) throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();
    String keyValue = "value";
    store.createVolume(volumeName);
    BucketArgs bucketArgs = BucketArgs.newBuilder()
        .setAllowFallbackStoragePolicy(allowFallback)
        .setBucketLayout(bucketLayout)
        .build();
    store.getVolume(volumeName).createBucket(bucketName, bucketArgs);
    OzoneBucket bucket = store.getVolume(volumeName).getBucket(bucketName);

    OutputStream out = keyCreator.create(bucket, keyName,
        keyValue.getBytes(UTF_8).length, storagePolicy);
    out.write(keyValue.getBytes(UTF_8));
    if (extraData != null) {
      out.write(extraData);
    }
    out.close();

    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(keyName)
        .build();
    return ozoneManager.lookupKey(keyArgs);
  }

  @FunctionalInterface
  private interface KeyCreator {
    OutputStream create(OzoneBucket bucket, String keyName, int length,
        StoragePolicy storagePolicy) throws IOException;
  }

  private OmKeyInfo createMultipartKeyAndGet(OzoneBucket bucket,
      ReplicationConfig replicationConfig, StoragePolicy storagePolicy,
      boolean streamingWrite) throws IOException {
    String keyName = UUID.randomUUID().toString();
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(
        keyName, replicationConfig, Collections.emptyMap(), Collections.emptyMap(), storagePolicy);
    Map<Integer, String> parts = new TreeMap<>();
    byte[] data = new byte[OzoneConsts.OM_MULTIPART_MIN_SIZE];
    Arrays.fill(data, (byte) 1);
    for (int partNumber = 1; partNumber <= 2; partNumber++) {
      if (streamingWrite) {
        OzoneDataStreamOutput out = bucket.createMultipartStreamKey(
            keyName, data.length, partNumber, multipartInfo.getUploadID());
        out.write(data);
        out.getMetadata().put(OzoneConsts.ETAG, DigestUtils.md5Hex(data));
        out.close();
        parts.put(partNumber, out.getCommitUploadPartInfo().getETag());
      } else {
        OzoneOutputStream out = bucket.createMultipartKey(
            keyName, data.length, partNumber, multipartInfo.getUploadID());
        out.write(data);
        out.getMetadata().put(OzoneConsts.ETAG, DigestUtils.md5Hex(data));
        out.close();
        parts.put(partNumber, out.getCommitUploadPartInfo().getETag());
      }
    }
    bucket.completeMultipartUpload(keyName, multipartInfo.getUploadID(), parts);

    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(bucket.getVolumeName())
        .setBucketName(bucket.getName())
        .setKeyName(keyName)
        .build();
    return ozoneManager.lookupKey(keyArgs);
  }
}
